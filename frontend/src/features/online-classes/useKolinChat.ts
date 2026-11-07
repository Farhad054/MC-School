import { useCallback, useMemo, useRef, useState } from 'react';
import { kolinApi, type KolinContext, type KolinHistoryMessage } from '../../api/kolin';

export interface KolinMessage extends KolinHistoryMessage {
  id: string;
  type: 'text';
  status: 'complete' | 'pending' | 'failed';
}

interface Submission {
  messageId: string;
  text: string;
  history: KolinHistoryMessage[];
  context: KolinContext;
}

interface Session {
  token: object;
  open: boolean;
  draft: string;
  messages: KolinMessage[];
  availability: 'idle' | 'checking' | 'ready' | 'unconfigured' | 'error';
  pending: boolean;
  error: 'connection' | 'response' | null;
  failed: Submission | null;
}

const initial = (token: object): Session => ({ token, open: false, draft: '', messages: [], availability: 'idle', pending: false, error: null, failed: null });

/** One private, in-page teacher thread. Closing/resizing/changing pupil never changes its scope. */
export function useKolinChat(classId: string, teacherId: string, enabled: boolean) {
  const token = useMemo(() => ({}), [classId, teacherId, enabled]);
  const latest = useRef(token);
  latest.current = token;
  const [stored, setStored] = useState<Session>(() => initial(token));
  const session = stored.token === token ? stored : initial(token);
  const state = useRef(session);
  state.current = session;
  const statusFlight = useRef<object | null>(null);
  const responseFlight = useRef<object | null>(null);
  const change = useCallback((update: (current: Session) => Session) => {
    if (latest.current !== token) return;
    setStored(current => update(current.token === token ? current : initial(token)));
  }, [token]);

  const checkAvailability = useCallback(async () => {
    if (!enabled || statusFlight.current === token) return;
    statusFlight.current = token;
    change(current => ({ ...current, availability: 'checking', error: null }));
    try {
      const result = await kolinApi.status(classId);
      change(current => ({ ...current, availability: result.configured ? 'ready' : 'unconfigured' }));
    } catch {
      change(current => ({ ...current, availability: 'error', error: 'connection' }));
    } finally {
      if (statusFlight.current === token) statusFlight.current = null;
    }
  }, [classId, enabled, token, change]);

  const toggle = useCallback(() => {
    if (!enabled) return;
    const current = state.current;
    change(value => ({ ...value, open: !current.open }));
    if (!current.open && (current.availability === 'idle' || current.availability === 'error')) void checkAvailability();
  }, [enabled, change, checkAvailability]);
  const close = useCallback(() => change(current => ({ ...current, open: false })), [change]);
  const setDraft = useCallback((draft: string) => change(current => ({ ...current, draft })), [change]);

  const run = useCallback(async (submission: Submission, append: boolean) => {
    if (!enabled || responseFlight.current === token || state.current.availability !== 'ready') return;
    responseFlight.current = token;
    change(current => ({ ...current, pending: true, error: null, failed: null,
      messages: append ? [...current.messages, { id: submission.messageId, role: 'user', type: 'text', text: submission.text, status: 'pending' }]
        : current.messages.map(message => message.id === submission.messageId ? { ...message, status: 'pending' } : message),
    }));
    try {
      const result = await kolinApi.send(classId, submission.text, submission.history, submission.context);
      if (result.type !== 'text' || typeof result.text !== 'string' || !result.text.trim()) throw new Error('Empty response');
      change(current => ({ ...current, pending: false,
        draft: current.draft.trim() === submission.text ? '' : current.draft,
        messages: [...current.messages.map(message => message.id === submission.messageId ? { ...message, status: 'complete' as const } : message),
          { id: crypto.randomUUID(), role: 'assistant', type: 'text', text: result.text, status: 'complete' }],
      }));
    } catch {
      change(current => ({ ...current, pending: false, error: 'response', failed: submission,
        messages: current.messages.map(message => message.id === submission.messageId ? { ...message, status: 'failed' } : message),
      }));
    } finally {
      if (responseFlight.current === token) responseFlight.current = null;
    }
  }, [classId, enabled, token, change]);

  const send = useCallback((context: KolinContext) => {
    const current = state.current;
    const text = current.draft.trim();
    if (!enabled || !text || text.length > 2000 || current.pending) return;
    if (current.failed?.text === text && current.failed.context.studentId === context.studentId
        && current.failed.context.targetType === context.targetType
        && current.failed.context.currentWorkbookPage === context.currentWorkbookPage) return run(current.failed, false);
    const history = current.messages.filter(message => message.status === 'complete')
      .slice(-8).map(message => ({ role: message.role, text: message.text }));
    return run({ messageId: crypto.randomUUID(), text, history, context: { ...context } }, true);
  }, [enabled, run]);

  const retry = useCallback(() => {
    if (state.current.error === 'connection') return checkAvailability();
    if (state.current.failed) return run(state.current.failed, false);
  }, [run, checkAvailability]);

  return { ...session, toggle, close, setDraft, send, retry };
}
