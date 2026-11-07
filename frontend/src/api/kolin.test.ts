import { beforeEach, describe, expect, it, vi } from 'vitest';
import { request } from './client';
import { kolinApi, type KolinContext, type KolinHistoryMessage } from './kolin';

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
