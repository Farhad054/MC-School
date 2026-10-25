import { describe, expect, it } from 'vitest';
import type { OnlineClass } from '../../api/onlineClasses';
import { berlinLessonDate, lessonEntry } from './lessonEntry';

const start = '2026-10-24T12:00:00Z';
const room = (patch: Partial<OnlineClass> = {}) => ({
  scheduledStartAt: start, status: 'LIVE', joinWindowOpen: true, ...patch,
}) as OnlineClass;
const at = (time: string) => Date.parse(time);

describe('lesson entry calendar policy', () => {
  it('uses Berlin instead of the browser timezone', () => {
    expect(berlinLessonDate('2026-10-23T22:00:00Z')).toBe('2026-10-24');
    expect(berlinLessonDate('invalid')).toBe('');
  });
  it('is inclusive at midnight and exclusive at the next midnight', () => {
    expect(lessonEntry(room(), start, at('2026-10-23T21:59:59Z')).canJoin).toBe(false);
    expect(lessonEntry(room(), start, at('2026-10-23T22:00:00Z')).state).toBe('OPEN');
    expect(lessonEntry(room(), start, at('2026-10-24T21:59:59Z')).canJoin).toBe(true);
    expect(lessonEntry(room(), start, at('2026-10-24T22:00:00Z')).state).toBe('DAY_CLOSED');
  });
  it('requires teacher opening even on the right date', () => {
    expect(lessonEntry(undefined, start, at(start)).state).toBe('WAITING_FOR_TEACHER');
    expect(lessonEntry(room({ status: 'SCHEDULED', joinWindowOpen: true }), start, at(start)).canJoin).toBe(false);
  });
  it('does not mistake late opening or admission for previous entry', () => {
    const late = room({ actualStartAt: '2026-10-25T12:00:00Z', viewerHasJoined: false });
    expect(lessonEntry(late, start, at('2026-10-25T12:00:00Z')).canJoin).toBe(false);
  });
  it('permits returning users after midnight only while the server permits it', () => {
    const returning = room({ viewerHasJoined: true });
    expect(lessonEntry(returning, start, at('2026-10-25T12:00:00Z')).state).toBe('RECONNECT');
    expect(lessonEntry({ ...returning, joinWindowOpen: false }, start, at('2026-10-25T12:00:00Z')).canJoin).toBe(false);
  });
  it('ends an open room at Monday midnight, even without a teacher ending it', () => {
    expect(lessonEntry(room({ viewerHasJoined: true }), start, at('2026-10-25T22:59:59Z')).canJoin).toBe(true);
    expect(lessonEntry(room({ viewerHasJoined: true }), start, at('2026-10-25T23:00:00Z')).state).toBe('ENDED');
  });
  it.each(['ENDED', 'CANCELLED'] as const)('never reconnects a %s room', status => {
    expect(lessonEntry(room({ status, viewerHasJoined: true }), start, at(start)).state).toBe('ENDED');
  });
  it('closes stale grants after the weekly deadline is reported', () => {
    expect(lessonEntry(room({ entryState: 'CLOSED' }), start, at(start)).canJoin).toBe(false);
  });
  it.each([
    ['2026-03-29T12:00:00Z', '2026-03-28T23:00:00Z', '2026-03-29T22:00:00Z'],
    ['2026-10-25T12:00:00Z', '2026-10-24T22:00:00Z', '2026-10-25T23:00:00Z'],
  ])('respects both DST transitions (%s)', (scheduledStartAt, opens, closes) => {
    const current = room({ scheduledStartAt });
    expect(lessonEntry(current, scheduledStartAt, at(opens)).canJoin).toBe(true);
    expect(lessonEntry(current, scheduledStartAt, at(closes) - 1).canJoin).toBe(true);
    expect(lessonEntry(current, scheduledStartAt, at(closes)).canJoin).toBe(false);
  });
});
