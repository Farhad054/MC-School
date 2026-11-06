import { useCallback, useEffect, useRef, useState } from 'react';
import { personalBoardOwner } from './boards';
import {
  STUDENT_VIEW_TOPIC,
  isStudentViewSnapshot,
  studentViewEvent,
  type StudentViewEvent,
  type StudentViewSnapshot,
} from './events';
import { useClassEvents } from './useClassEvents';

export const STUDENT_VIEW_HEARTBEAT_MS = 2000;
export const STUDENT_VIEW_STALE_MS = 6000;
export const STUDENT_VIEW_SEND_INTERVAL_MS = 80;

type StreamOrder = { sessionId: string; revision: number; at: number; retired: Set<string> };
type ReceivedView = { scope: string; snapshot: StudentViewSnapshot };

function sameSnapshot(a: StudentViewSnapshot, b: StudentViewSnapshot): boolean {
  return a.page === b.page && a.pageCount === b.pageCount && a.layout === b.layout && a.pageGap === b.pageGap
    && a.viewport.width === b.viewport.width && a.viewport.height === b.viewport.height
    && a.region.x === b.region.x && a.region.y === b.region.y
    && a.region.width === b.region.width && a.region.height === b.region.height;
}

function sessionId(): string {
  return typeof crypto?.randomUUID === 'function'
    ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

/**
 * Private, one-way pupil viewport telemetry. The teacher's switch only changes
 * their own rendering; it never publishes a view or updates the pupil's board.
 */
export function useStudentView({
  classId,
  boardId,
  targetType,
  actorId,
  isHost,
  hostUserId,
  active = true,
  followEnabled = false,
  snapshot = null,
}: {
  classId: string;
  boardId: string;
  targetType: StudentViewEvent['targetType'];
  actorId: string;
  isHost: boolean;
  hostUserId: string | null;
  active?: boolean;
  followEnabled?: boolean;
  snapshot?: StudentViewSnapshot | null;
}) {
  const ownerId = personalBoardOwner(boardId);
  const scope = JSON.stringify([classId, boardId, targetType, actorId, isHost, hostUserId]);
  const receiving = active && isHost && Boolean(ownerId && hostUserId && actorId === hostUserId);
  const sending = active && !isHost && Boolean(ownerId && actorId === ownerId && hostUserId && hostUserId !== actorId);
  const [received, setReceived] = useState<ReceivedView | null>(null);
  const [freshScope, setFreshScope] = useState<string | null>(null);
  const lastArrival = useRef<{ scope: string; at: number } | null>(null);
  const freshnessTimer = useRef<number | undefined>(undefined);
  const streams = useRef<{ scope: string; senders: Map<string, StreamOrder> }>({ scope, senders: new Map() });
  const source = useRef<{ scope: string; sessionId: string; revision: number; sentAt: number } | null>(null);
  // Scope is checked synchronously, so a new board cannot briefly display an
  // earlier pupil's viewport while effects are waiting to run.
  if (streams.current.scope !== scope) streams.current = { scope, senders: new Map() };
  if (source.current?.scope !== scope) source.current = { scope, sessionId: sessionId(), revision: 0, sentAt: Number.NEGATIVE_INFINITY };
  const latest = useRef({ scope, sending, snapshot, hostUserId });
  latest.current = { scope, sending, snapshot, hostUserId };

  const refreshFreshness = useCallback((receivedAt: number) => {
    window.clearTimeout(freshnessTimer.current);
    const remaining = STUDENT_VIEW_STALE_MS - (Date.now() - receivedAt);
    if (remaining <= 0) {
      setFreshScope(null);
      return;
    }
    setFreshScope(scope);
    // One deadline per pupil replaces a polling clock that would otherwise
    // repaint every board in the overview even while nobody is moving.
    freshnessTimer.current = window.setTimeout(() => {
      if (latest.current.scope === scope) setFreshScope(current => current === scope ? null : current);
    }, remaining);
  }, [scope]);

  const { publish } = useClassEvents(classId, STUDENT_VIEW_TOPIC, ({ event, senderIdentity }) => {
    if (!receiving || event.type !== 'student-view' || event.classId !== classId
        || event.boardId !== boardId || event.targetType !== targetType
        || !senderIdentity || senderIdentity.split('|')[0] !== ownerId
        || !isStudentViewSnapshot(event)) return;
    const previous = streams.current.senders.get(senderIdentity);
    if (previous?.sessionId === event.sessionId && event.revision <= previous.revision) return;
    if (previous?.retired.has(event.sessionId)) return;
    // Timestamps are compared only within one authenticated sender/device,
    // never against the teacher's clock. This also rejects an old session that
    // had not arrived before a newer remount's first packet.
    if (previous && previous.sessionId !== event.sessionId && event.at < previous.at) return;
    const retired = previous?.retired ?? new Set<string>();
    if (previous && previous.sessionId !== event.sessionId) {
      retired.add(previous.sessionId);
      // Bound memory across very long lessons and repeated source remounts.
      if (retired.size > 32) retired.delete(retired.values().next().value!);
    }
    streams.current.senders.set(senderIdentity, { sessionId: event.sessionId, revision: event.revision, at: event.at, retired });
    const now = Date.now();
    const incoming: StudentViewSnapshot = {
      page: event.page, pageCount: event.pageCount, layout: event.layout,
      viewport: { ...event.viewport }, region: { ...event.region },
      ...(event.pageGap !== undefined ? { pageGap: event.pageGap } : {}),
    };
    lastArrival.current = { scope, at: now };
    // Heartbeats refresh liveness without replacing the geometry object and
    // reapplying the same scroll/zoom throughout the entire React subtree.
    setReceived(previous => previous?.scope === scope && sameSnapshot(previous.snapshot, incoming)
      ? previous : { scope, snapshot: incoming });
    refreshFreshness(now);
  }, receiving);

  const send = useCallback(() => {
    const current = latest.current;
    if (!current.sending || current.scope !== source.current?.scope || !current.hostUserId
        || !isStudentViewSnapshot(current.snapshot)) return;
    const state = source.current;
    state.sentAt = Date.now();
    state.revision += 1;
    void publish(studentViewEvent(classId, {
      ...current.snapshot, boardId, targetType, sessionId: state.sessionId, revision: state.revision,
    }), true, [current.hostUserId]);
  }, [boardId, classId, publish, targetType]);

  const timer = useRef<number | undefined>(undefined);
  const signature = snapshot && isStudentViewSnapshot(snapshot) ? JSON.stringify(snapshot) : null;
  useEffect(() => {
    if (!sending || signature === null) {
      window.clearTimeout(timer.current);
      timer.current = undefined;
      return;
    }
    // Throttle rather than debounce: a continuous pinch still produces live
    // positions, and the trailing callback always sends the latest geometry.
    if (timer.current !== undefined) return;
    const delay = Math.max(0, STUDENT_VIEW_SEND_INTERVAL_MS - (Date.now() - source.current!.sentAt));
    timer.current = window.setTimeout(() => {
      timer.current = undefined;
      send();
    }, delay);
  }, [sending, signature, scope, send]);

  useEffect(() => {
    if (!sending) return;
    const heartbeat = window.setInterval(send, STUDENT_VIEW_HEARTBEAT_MS);
    return () => window.clearInterval(heartbeat);
  }, [sending, scope, send]);

  useEffect(() => {
    // Drop a pending callback before a remount or a different identity can use
    // it. The next snapshot effect establishes a fresh timer for that scope.
    return () => { window.clearTimeout(timer.current); timer.current = undefined; };
  }, [scope]);

  useEffect(() => {
    if (receiving && lastArrival.current?.scope === scope) refreshFreshness(lastArrival.current.at);
    else setFreshScope(null);
    return () => window.clearTimeout(freshnessTimer.current);
  }, [receiving, scope, refreshFreshness]);

  const current = received?.scope === scope ? received : null;
  const isFresh = Boolean(receiving && current && freshScope === scope);
  return {
    // Cache while the switch is off so enabling it applies the latest view
    // immediately. A stale view stays put until new source data arrives.
    remoteView: receiving && followEnabled ? current?.snapshot ?? null : null,
    isFresh,
  };
}
