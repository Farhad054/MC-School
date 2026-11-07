import { beforeEach, describe, expect, it, vi } from 'vitest';
import { request } from './client';
import { kolinApi, parseKolinTranscriptContext, type KolinContext, type KolinHistoryMessage } from './kolin';

vi.mock('./client', () => ({ request: vi.fn() }));
beforeEach(() => vi.mocked(request).mockReset().mockResolvedValue({ type: 'text', text: 'Answer' }));

describe('compact Kolin API request', () => {
  it('whitelists context and history fields and sends only the last eight messages', async () => {
    const history = Array.from({ length: 12 }, (_, index) => ({ role: index % 2 ? 'assistant' : 'user', text: String(index), pdf: 'must not leave', transcript: 'private' })) as KolinHistoryMessage[];
    const context = { targetType: 'student', studentId: 'pupil-1', currentWorkbookPage: 3,
      workbook: new Blob(['PDF']), answers: 'secret', transcript: 'must not leave', skills: [1, 2], screenshot: 'data:image/png;base64,AAA' } as KolinContext;
    await kolinApi.send('class-1', 'Make the second harder', history, context);
    expect(request).toHaveBeenCalledExactlyOnceWith('POST', '/online-classes/class-1/kolin/messages', {
      text: 'Make the second harder',
      history: history.slice(-8).map(({ role, text }) => ({ role, text })),
      context: { targetType: 'student', studentId: 'pupil-1', currentWorkbookPage: 3 },
    });
    const payload = JSON.stringify(vi.mocked(request).mock.calls[0][2]);
    expect(payload).not.toMatch(/PDF|transcript|answers|screenshot|skills|image\/png|must not leave/);
  });
});

describe('optional Kolin transcript metadata', () => {
  const valid = { state: 'included', segmentCount: 12, truncated: true,
    latestSegmentAt: '2026-11-07T15:45:00.123456789Z', fromMs: 120000, toMs: 525000, transcriptionActive: false };

  it('retains only known metadata fields and supports a valid nullable empty state', () => {
    expect(parseKolinTranscriptContext({ ...valid, transcript: 'never store raw text' })).toEqual(valid);
    const empty = { ...valid, state: 'empty', segmentCount: 0, truncated: false, latestSegmentAt: null, fromMs: null, toMs: null };
    expect(parseKolinTranscriptContext(empty)).toEqual(empty);
  });

  it.each([undefined, null, [], {}, { ...valid, state: 'future' }, { ...valid, segmentCount: -1 },
    { ...valid, segmentCount: 1.5 }, { ...valid, truncated: 'false' }, { ...valid, transcriptionActive: 'true' },
    { ...valid, fromMs: -1 }, { ...valid, toMs: NaN }, { ...valid, fromMs: 600000 },
    { ...valid, latestSegmentAt: '<script>' }, { ...valid, latestSegmentAt: '2026-99-99T99:99:99Z' },
  ])('ignores malformed metadata %#', value => {
    expect(parseKolinTranscriptContext(value)).toBeUndefined();
  });
});
