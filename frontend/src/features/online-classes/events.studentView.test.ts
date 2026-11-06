import { describe, expect, it } from 'vitest';
import { encodeEvent, parseClassEvent, studentViewEvent, type StudentViewSnapshot } from './events';

const snapshot: StudentViewSnapshot = {
  page: 2, pageCount: 4, layout: 'continuous',
  viewport: { width: 1000, height: 700 },
  region: { x: -.2, y: .3, width: .7, height: .4 },
  pageGap: .025,
};
const event = () => studentViewEvent('class-1', {
  ...snapshot, boardId: 'personal-pupil-1', targetType: 'WHITEBOARD', sessionId: 'session-1', revision: 3,
});
const parse = (value: unknown) => parseClassEvent(new TextEncoder().encode(JSON.stringify(value)), 'class-1');

describe('private pupil view protocol', () => {
  it.each(['WHITEBOARD', 'NOTEBOOK_CAMERA'] as const)('round-trips an exact visible rectangle for %s', targetType => {
    const packet = { ...event(), targetType };
    expect(parseClassEvent(encodeEvent(packet), 'class-1')).toEqual(packet);
    expect(parseClassEvent(encodeEvent(packet), 'other-class')).toBeNull();
  });

  it('accepts legacy snapshots without a page gap and the complete page in paged mode', () => {
    expect(parse({ ...event(), layout: 'paged', pageGap: undefined,
      region: { x: 0, y: 0, width: 1, height: 1 } })).not.toBeNull();
  });

  it.each([
    { page: -1 }, { page: 1.5 }, { page: 4 }, { pageCount: 0 }, { pageCount: 10001 },
    { layout: 'unknown' }, { boardId: 'board-1' }, { boardId: 'personal-' },
    { targetType: 'SCREEN_SHARE' }, { sessionId: '' }, { sessionId: '<script>' },
    { revision: -1 }, { revision: 1.5 }, { at: null },
    { viewport: { width: 0, height: 700 } }, { viewport: { width: 1000, height: 1e9 } },
    { region: { x: 0, y: 0, width: 0, height: 1 } },
    { region: { x: 0, y: 0, width: 0.0001, height: 1 } },
    { region: { x: 0, y: 0, width: 1, height: 0.049 } },
    { region: { x: 0, y: 0, width: 1, height: -1 } },
    { region: { x: 101, y: 0, width: 1, height: 1 } },
    { region: { x: 0, y: 0, width: 1e9, height: 1 } },
    { region: [] }, { viewport: [] }, { pageGap: -1 }, { pageGap: 11 }, { pageGap: null },
  ])('rejects malformed viewport data: %j', patch => {
    expect(parse({ ...event(), ...patch })).toBeNull();
  });
});
