import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest';
import type { Homework } from '../../api/types';
import type { TabletInkCallbacks } from '../../lib/tabletInkInput';
import { PdfHomeworkPage } from './PdfHomeworkPage';

const mocks = vi.hoisted(() => ({
  homeworks: vi.fn(), page: vi.fn(), submit: vi.fn(), worksheet: vi.fn(),
  callbacks: new Map<HTMLElement, TabletInkCallbacks>(),
  visible: new Map<Element, IntersectionObserverCallback>(),
  t: (key: string) => key,
}));

vi.mock('../../api/client', async (importOriginal) => ({
  ...await importOriginal<typeof import('../../api/client')>(), api: { study: {
  homeworks: mocks.homeworks, worksheetPageDataUrl: mocks.page,
  submitPdfHomework: mocks.submit, worksheet: mocks.worksheet,
} } }));
vi.mock('../../i18n/I18nContext', () => ({ useI18n: () => ({ language: 'RU', t: mocks.t }) }));
vi.mock('../../lib/homeworkInk', () => ({ renderHomeworkInkStroke: vi.fn() }));
vi.mock('../../lib/tabletInkInput', () => ({
  attachTabletInkInput: (element: HTMLElement, callbacks: TabletInkCallbacks) => {
    mocks.callbacks.set(element, callbacks);
    return { destroy: () => mocks.callbacks.delete(element), backend: 'pointer' };
  },
}));

const homework: Homework = {
  id: 'hw-1', studentId: 'student-1', startDate: '2026-11-02', createdAt: '2026-11-02',
  totalCards: 0, notStarted: 0, inProgress: 0, learned: 0, status: 'ACTIVE',
  hasWorksheet: true, worksheetFilename: 'Mathematik.pdf', worksheetPageCount: 3,
  submitted: false, submittedAt: null, deadlineAt: '2026-11-03', overdue: false, submittedLate: false,
};

function open() {
  return render(<MemoryRouter initialEntries={['/homeworks/hw-1/pdf']}>
    <Routes><Route path="/homeworks/:homeworkId/pdf" element={<PdfHomeworkPage />} /></Routes>
  </MemoryRouter>);
}

function reveal(page: number) {
  const target = screen.getByRole('region', { name: `Страница ${page + 1}` });
  const rect = target.getBoundingClientRect();
  act(() => mocks.visible.get(target)?.([{
    target, isIntersecting: true, intersectionRatio: 1, boundingClientRect: rect,
    intersectionRect: rect, rootBounds: null, time: 0,
  }], {} as IntersectionObserver));
}

function ready(page: number) {
  const image = within(screen.getByRole('region', { name: `Страница ${page + 1}` })).getByRole('img');
  Object.defineProperties(image, { naturalWidth: { value: 1000 }, naturalHeight: { value: 1400 } });
  fireEvent.load(image);
}

function draw(page: number, x: number) {
  const canvas = screen.getByTestId(`homework-ink-${page}`);
  const callbacks = mocks.callbacks.get(canvas)!;
  const point = { clientX: x, clientY: 50, pressure: 0.5, timeStamp: 1 };
  const meta = { source: 'pointer' as const, device: 'pen' as const, inputId: 1 };
  act(() => {
    callbacks.onStart(point, meta);
    callbacks.onEnd({ ...point, clientX: x + 20 }, meta, false);
  });
}

beforeEach(() => {
  vi.clearAllMocks();
  mocks.callbacks.clear();
  mocks.visible.clear();
  mocks.homeworks.mockResolvedValue([homework]);
  mocks.page.mockImplementation(async (_id: string, page: number) => `data:image/png;base64,page-${page}`);
  mocks.submit.mockResolvedValue(undefined);
  vi.stubGlobal('IntersectionObserver', class {
    constructor(private callback: IntersectionObserverCallback) {}
    observe(target: Element) { mocks.visible.set(target, this.callback); }
    disconnect() {}
  });
  vi.spyOn(window, 'matchMedia').mockImplementation((query) => ({
    matches: query === '(pointer: fine)', media: query, addEventListener() {}, removeEventListener() {},
  }) as unknown as MediaQueryList);
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({ clearRect: vi.fn() } as unknown as CanvasRenderingContext2D);
  vi.spyOn(HTMLCanvasElement.prototype, 'getBoundingClientRect').mockReturnValue({
    left: 0, top: 0, width: 1000, height: 1400, right: 1000, bottom: 1400,
    x: 0, y: 0, toJSON() {},
  });
  vi.spyOn(HTMLCanvasElement.prototype, 'toBlob').mockImplementation((callback) => callback(new Blob(['png'], { type: 'image/png' })));
  vi.spyOn(window, 'confirm').mockReturnValue(true);
});

afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); });

describe('PDF homework as one vertical document', () => {
  it('keeps every page in document order and loads images only near the viewport', async () => {
    open();
    await screen.findByRole('img', { name: 'Страница PDF 1' });
    expect(screen.getAllByRole('region').map((section) => section.getAttribute('data-page-index')))
      .toEqual(['0', '1', '2']);
    expect(mocks.page).toHaveBeenCalledTimes(1);
    const first = screen.getByTestId('homework-ink-0');

    reveal(1);
    await screen.findByRole('img', { name: 'Страница PDF 2' });
    expect(mocks.page).not.toHaveBeenCalledWith('hw-1', 2);
    reveal(2);
    await screen.findByRole('img', { name: 'Страница PDF 3' });
    expect(screen.getAllByRole('img')).toHaveLength(3);
    expect(screen.getByTestId('homework-ink-0')).toBe(first);
  });

  it('retries a failed page without replacing another page or its ink surface', async () => {
    mocks.page.mockImplementation(async (_id: string, page: number) => {
      if (page === 1 && mocks.page.mock.calls.filter((call) => call[1] === 1).length === 1) {
        throw new Error('Unavailable page');
      }
      return `data:image/png;base64,page-${page}`;
    });
    open();
    const firstImage = await screen.findByRole('img', { name: 'Страница PDF 1' });
    reveal(1);
    const second = screen.getByRole('region', { name: 'Страница 2' });
    fireEvent.click(await within(second).findByRole('button', { name: 'Загрузить страницу ещё раз' }));
    await within(second).findByRole('img', { name: 'Страница PDF 2' });
    expect(screen.getByRole('img', { name: 'Страница PDF 1' })).toBe(firstImage);
    expect(mocks.page.mock.calls.filter((call) => call[1] === 1)).toHaveLength(2);
  });

  it('allows native finger scrolling and keeps one mobile toolbar for the active page', async () => {
    vi.spyOn(window, 'matchMedia').mockImplementation((query) => ({
      matches: false, media: query, addEventListener() {}, removeEventListener() {},
    }) as unknown as MediaQueryList);
    open();
    await screen.findByRole('img', { name: 'Страница PDF 1' });
    reveal(1);
    await screen.findByRole('img', { name: 'Страница PDF 2' });
    expect(screen.getAllByLabelText('Инструменты')).toHaveLength(1);
    expect(screen.getByTitle('Android: писать стилусом/пальцем')).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByTestId('homework-ink-0').style.touchAction).toContain('pan-y');
    fireEvent.click(screen.getByTitle('Android: писать стилусом/пальцем'));
    expect(screen.getByTitle('Android: писать стилусом/пальцем')).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByTestId('homework-ink-0').style.touchAction).toBe('pinch-zoom');
    expect(screen.getByTestId('homework-ink-1').style.touchAction).toBe('pinch-zoom');
    fireEvent.click(screen.getByTitle('Android: писать стилусом/пальцем'));
    expect(screen.getByTestId('homework-ink-0').style.touchAction).toContain('pan-y');
    fireEvent.pointerDown(screen.getByRole('region', { name: 'Страница 2' }));
    expect(screen.getAllByLabelText('Инструменты')).toHaveLength(1);
    expect(screen.getByTitle('Предыдущая страница')).toBeEnabled();
  });

  it('keeps undo and clear on their own pages and submits overlays with their original page indices', async () => {
    open();
    await screen.findByRole('img', { name: 'Страница PDF 1' });
    reveal(1);
    await screen.findByRole('img', { name: 'Страница PDF 2' });
    ready(0); ready(1);
    draw(0, 10); draw(0, 100); draw(1, 250);
    const first = screen.getByRole('region', { name: 'Страница 1' });
    const second = screen.getByRole('region', { name: 'Страница 2' });
    fireEvent.click(within(first).getByRole('button', { name: '↶ Шаг назад' }));
    expect(within(first).getByRole('button', { name: 'Очистить страницу' })).toBeEnabled();
    expect(within(second).getByRole('button', { name: 'Очистить страницу' })).toBeEnabled();
    fireEvent.click(within(second).getByRole('button', { name: 'Очистить страницу' }));
    expect(within(second).getByRole('button', { name: '↶ Шаг назад' })).toBeDisabled();
    expect(within(first).getByRole('button', { name: '↶ Шаг назад' })).toBeEnabled();
    mocks.homeworks.mockResolvedValue([{ ...homework, submitted: true, submittedAt: '2026-11-02T12:00:00Z' }]);

    fireEvent.click(screen.getByRole('button', { name: 'Сдать домашку' }));

    await waitFor(() => expect(mocks.submit).toHaveBeenCalledWith('hw-1', [
      { pageIndex: 0, imageBase64: expect.stringMatching(/^data:image\/png;base64,/) },
    ]));
    await screen.findByRole('img', { name: 'Сданная страница PDF 1' });
    expect(screen.queryByTestId('homework-ink-0')).not.toBeInTheDocument();
  });
});
