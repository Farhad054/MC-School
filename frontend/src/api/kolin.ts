import { request } from './client';

export interface KolinContext {
  targetType?: 'student' | 'group';
  studentId?: string;
  currentWorkbookPage?: number;
}

export interface KolinHistoryMessage {
  role: 'user' | 'assistant';
  text: string;
}

export interface KolinTranscriptContext {
  state: 'included' | 'empty' | 'unavailable';
  segmentCount: number;
  truncated: boolean;
  latestSegmentAt: string | null;
  fromMs: number | null;
  toMs: number | null;
  transcriptionActive: boolean;
}

export interface KolinResponse { type: 'text'; text: string; transcriptContext?: KolinTranscriptContext }

/** Optional server metadata never prevents an otherwise valid text answer. */
export function parseKolinTranscriptContext(value: unknown): KolinTranscriptContext | undefined {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return;
  const source = value as Record<string, unknown>;
  if (source.state !== 'included' && source.state !== 'empty' && source.state !== 'unavailable') return;
  if (typeof source.segmentCount !== 'number' || !Number.isSafeInteger(source.segmentCount) || source.segmentCount < 0
      || typeof source.truncated !== 'boolean' || typeof source.transcriptionActive !== 'boolean') return;
  const offset = (item: unknown): item is number | null => item === null
    || typeof item === 'number' && Number.isSafeInteger(item) && item >= 0;
  if (!offset(source.fromMs) || !offset(source.toMs)
      || source.fromMs !== null && source.toMs !== null && source.fromMs > source.toMs) return;
  if (source.latestSegmentAt !== null && (typeof source.latestSegmentAt !== 'string'
      || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$/.test(source.latestSegmentAt)
      || !Number.isFinite(Date.parse(source.latestSegmentAt)))) return;
  return { state: source.state, segmentCount: source.segmentCount, truncated: source.truncated,
    latestSegmentAt: source.latestSegmentAt, fromMs: source.fromMs, toMs: source.toMs,
    transcriptionActive: source.transcriptionActive };
}

const base = (classId: string) => `/online-classes/${encodeURIComponent(classId)}/kolin`;

export const kolinApi = {
  status: (classId: string) => request<{ configured: boolean }>('GET', `${base(classId)}/status`),
  send: (classId: string, text: string, history: KolinHistoryMessage[], context: KolinContext) =>
    request<KolinResponse>('POST', `${base(classId)}/messages`, {
      text,
      history: history.slice(-8).map(message => ({ role: message.role, text: message.text })),
      context: {
        ...(context.targetType ? { targetType: context.targetType } : {}),
        ...(context.studentId ? { studentId: context.studentId } : {}),
        ...(context.currentWorkbookPage !== undefined ? { currentWorkbookPage: context.currentWorkbookPage } : {}),
      },
    }),
};
