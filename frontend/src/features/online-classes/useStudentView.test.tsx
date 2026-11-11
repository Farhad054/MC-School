import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  STUDENT_VIEW_TOPIC, studentViewEvent, type ClassEvent, type StudentViewEvent, type StudentViewSnapshot,
} from './events';
import {
  STUDENT_VIEW_HEARTBEAT_MS, STUDENT_VIEW_SEND_INTERVAL_MS, STUDENT_VIEW_STALE_MS, useStudentView,
} from './useStudentView';

const publish = vi.fn().mockResolvedValue(undefined);
let handler: (received: { event: ClassEvent; senderIdentity?: string }) => void = () => {};
let listening = false;
vi.mock('./useClassEvents', () => ({
  useClassEvents: (_classId: string, topic: string, callback: typeof handler, listen: boolean) => {
    expect(topic).toBe(STUDENT_VIEW_TOPIC);
    handler = callback;
    listening = listen;
    return { publish };
  },
}));

type Props = Parameters<typeof useStudentView>[0];
const snapshot: StudentViewSnapshot = {
  page: 2, pageCount: 4, layout: 'continuous',
  viewport: { width: 1000, height: 700 },
  region: { x: .1, y: .3, width: .7, height: .4 }, pageGap: .025,
};
const props = (patch: Partial<Props> = {}): Props => ({
  classId: 'c1', boardId: 'personal-pupil', targetType: 'WHITEBOARD',
  actorId: 'teacher', isHost: true, hostUserId: 'teacher', active: true, followEnabled: true,
  ...patch,
});
const pupil = (patch: Partial<Props> = {}): Props => props({ actorId: 'pupil', isHost: false, snapshot, ...patch });
const packet = (patch: Partial<StudentViewEvent> = {}) => ({ ...studentViewEvent('c1', {
  ...snapshot, boardId: 'personal-pupil', targetType: 'WHITEBOARD', sessionId: 'source-1', revision: 1,
}), ...patch });
const receive = (event = packet(), senderIdentity = 'pupil|tablet') => act(() => handler({ event, senderIdentity }));
const flush = (ms = 0) => act(() => vi.advanceTimersByTime(ms));
const mount = (initialProps = props()) => renderHook(useStudentView, { initialProps });

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(10_000);
  publish.mockClear();
  listening = false;
});
afterEach(() => vi.useRealTimers());

describe('useStudentView source', () => {
  it('sends the owner’s exact viewport only to the current host, never to the class', () => {
    mount(pupil());
    flush();
    expect(publish).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({
      type: 'student-view', classId: 'c1', boardId: 'personal-pupil', targetType: 'WHITEBOARD',
      ...snapshot, sessionId: expect.any(String), revision: 1,
    }), true, ['teacher']);
    expect(listening).toBe(false);
  });

  it('throttles continuous movement and publishes the last position without waiting for movement to stop', () => {
    const view = mount(pupil());
    flush();
    const next = (y: number) => pupil({ snapshot: { ...snapshot, region: { ...snapshot.region, y } } });
    view.rerender(next(.4));
    flush(30);
    view.rerender(next(.5));
    flush(30);
    view.rerender(next(.6));
    expect(publish).toHaveBeenCalledOnce();
    flush(STUDENT_VIEW_SEND_INTERVAL_MS - 60);
    expect(publish).toHaveBeenCalledTimes(2);
    expect(publish.mock.calls[1][0]).toMatchObject({ revision: 2, region: { y: .6 } });
    view.rerender(next(.7));
    flush(STUDENT_VIEW_SEND_INTERVAL_MS);
    expect(publish.mock.calls[2][0]).toMatchObject({ revision: 3, region: { y: .7 } });
  });

  it('repeats the current view for a late-joining teacher and stops while hidden or unmounted', () => {
    const view = mount(pupil());
    flush();
    const session = publish.mock.calls[0][0].sessionId;
    flush(STUDENT_VIEW_HEARTBEAT_MS);
    expect(publish).toHaveBeenCalledTimes(2);
    expect(publish.mock.calls[1][0]).toMatchObject({ sessionId: session, revision: 2 });
    view.rerender(pupil({ active: false }));
    flush(STUDENT_VIEW_HEARTBEAT_MS * 3);
    expect(publish).toHaveBeenCalledTimes(2);
    view.rerender(pupil());
    flush();
    expect(publish).toHaveBeenCalledTimes(3);
    view.unmount();
    flush(STUDENT_VIEW_HEARTBEAT_MS * 3);
    expect(publish).toHaveBeenCalledTimes(3);
  });

  it.each([
    { hostUserId: null }, { actorId: 'other-pupil' }, { boardId: 'board-1' },
    { isHost: true }, { active: false }, { snapshot: null },
    { snapshot: { ...snapshot, viewport: { width: 0, height: 0 } } },
  ])('never publishes without a visible owner board and a known teacher: %j', patch => {
    mount(pupil(patch));
    flush(STUDENT_VIEW_HEARTBEAT_MS * 3);
    expect(publish).not.toHaveBeenCalled();
  });

  it('cancels a pending old-board update when the target changes', () => {
    const view = mount(pupil());
    flush();
    const original = publish.mock.calls[0][0];
    view.rerender(pupil({ snapshot: { ...snapshot, page: 3 } }));
    view.rerender(pupil({ targetType: 'NOTEBOOK_CAMERA', snapshot: { ...snapshot, layout: 'paged', page: 0 } }));
    flush(STUDENT_VIEW_SEND_INTERVAL_MS);
    expect(publish).toHaveBeenCalledTimes(2);
    expect(publish.mock.calls[1][0]).toMatchObject({ targetType: 'NOTEBOOK_CAMERA', page: 0, revision: 1 });
    expect(publish.mock.calls[1][0].sessionId).not.toBe(original.sessionId);
  });
});

describe('useStudentView teacher', () => {
  it('caches authenticated views while follow is off and applies the latest one immediately when enabled', () => {
    const view = mount(props({ followEnabled: false }));
    receive();
    expect(listening).toBe(true);
    expect(view.result.current.remoteView).toBeNull();
    expect(view.result.current.isFresh).toBe(true);
    view.rerender(props());
    expect(view.result.current.remoteView).toEqual(snapshot);
    view.rerender(props({ followEnabled: false }));
    expect(view.result.current.remoteView).toBeNull();
    receive(packet({ revision: 2, page: 3 }));
    view.rerender(props());
    expect(view.result.current.remoteView?.page).toBe(3);
    expect(publish).not.toHaveBeenCalled();
  });

  it.each([
    ['another pupil', 'other-pupil|tablet', {}, {}],
    ['a host claiming the pupil board', 'teacher|tablet', {}, {}],
    ['another board', 'pupil|tablet', { boardId: 'personal-other' }, {}],
    ['another classroom', 'pupil|tablet', { classId: 'other-class' }, {}],
    ['camera traffic on the paper board', 'pupil|tablet', { targetType: 'NOTEBOOK_CAMERA' }, {}],
    ['unknown host', 'pupil|tablet', {}, { hostUserId: null }],
    ['a viewer claiming host role', 'pupil|tablet', {}, { actorId: 'another-user' }],
    ['a student viewer', 'pupil|tablet', {}, { isHost: false }],
    ['hidden board', 'pupil|tablet', {}, { active: false }],
  ] as const)('ignores %s', (_name, identity, eventPatch, propsPatch) => {
    const { result } = mount(props(propsPatch));
    receive(packet(eventPatch), identity);
    expect(result.current.remoteView).toBeNull();
    expect(result.current.isFresh).toBe(false);
    expect(publish).not.toHaveBeenCalled();
  });

  it('ignores delayed/duplicate revisions and a retired session after a source remount', () => {
    const { result } = mount();
    receive(packet({ revision: 8, page: 3 }));
    receive(packet({ revision: 7, page: 1 }));
    receive(packet({ revision: 8, page: 0 }));
    expect(result.current.remoteView?.page).toBe(3);
    receive(packet({ sessionId: 'source-2', revision: 1, page: 1 }));
    expect(result.current.remoteView?.page).toBe(1);
    receive(packet({ sessionId: 'source-1', revision: 9, page: 3 }));
    expect(result.current.remoteView?.page).toBe(1);
  });

  it('does not jump to an unseen older session that arrives after a newer source mount', () => {
    const { result } = mount();
    receive(packet({ sessionId: 'new-session', revision: 1, page: 3, at: 5000 }));
    receive(packet({ sessionId: 'old-session', revision: 99, page: 0, at: 4000 }));
    expect(result.current.remoteView?.page).toBe(3);
    // Device time can differ from teacher time; only source ordering matters.
    expect(result.current.isFresh).toBe(true);
    receive(packet({ sessionId: 'next-session', revision: 1, page: 1, at: 5001 }));
    expect(result.current.remoteView?.page).toBe(1);
  });

  it('keeps the last view when the pupil stops sending, reports it stale, and resumes on fresh data', () => {
    const { result } = mount();
    receive();
    flush(STUDENT_VIEW_STALE_MS + 500);
    expect(result.current.remoteView).toEqual(snapshot);
    expect(result.current.isFresh).toBe(false);
    receive(packet({ revision: 2, page: 3 }));
    expect(result.current.remoteView?.page).toBe(3);
    expect(result.current.isFresh).toBe(true);
  });

  it('keeps identical heartbeat geometry stable and expires only after the last accepted heartbeat', () => {
    const { result } = mount();
    receive();
    const firstView = result.current.remoteView;
    flush(STUDENT_VIEW_STALE_MS - 1000);
    receive(packet({ revision: 2 }));
    expect(result.current.remoteView).toBe(firstView);
    flush(1500);
    expect(result.current.isFresh).toBe(true);
    flush(STUDENT_VIEW_STALE_MS - 1500);
    expect(result.current.isFresh).toBe(false);
    expect(result.current.remoteView).toBe(firstView);
  });

  it('does not rerender an idle overview on a polling clock', () => {
    let renders = 0;
    const { result } = renderHook(() => { renders += 1; return useStudentView(props()); });
    receive();
    const afterReceive = renders;
    flush(STUDENT_VIEW_STALE_MS - 1);
    expect(renders).toBe(afterReceive);
    expect(result.current.isFresh).toBe(true);
    flush(1);
    expect(result.current.isFresh).toBe(false);
  });

  it('does not let replayed packets keep a departed pupil fresh', () => {
    const { result } = mount();
    const original = packet();
    receive(original);
    flush(STUDENT_VIEW_STALE_MS - 500);
    receive(original);
    flush(1000);
    expect(result.current.isFresh).toBe(false);
  });

  it.each([
    { boardId: 'personal-another' }, { classId: 'c2' }, { targetType: 'NOTEBOOK_CAMERA' as const },
    { actorId: 'another-teacher', hostUserId: 'another-teacher' },
  ])('never exposes cached data to a new scope: %j', patch => {
    const view = mount();
    receive();
    expect(view.result.current.remoteView).not.toBeNull();
    view.rerender(props(patch));
    expect(view.result.current.remoteView).toBeNull();
    expect(view.result.current.isFresh).toBe(false);
  });
});
