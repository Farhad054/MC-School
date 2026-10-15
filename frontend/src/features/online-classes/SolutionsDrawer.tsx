import { useLayoutEffect, useRef, useState } from 'react';
import { lessonPreparationApi } from '../../api/lessonPreparation';
import { usePdf, usePdfPage } from './usePdf';

/** Teacher-only answer key. Never served through the student workbook endpoint. */
export function SolutionsDrawer({ eventId, onClose }: { eventId?: string; onClose: () => void }) {
  const [revision, setRevision] = useState(0);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [pageIndex, setPageIndex] = useState(0);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const pdf = usePdf(eventId ? `solutions:${eventId}:${revision}` : null, async () => {
    if (!eventId) throw new Error('Нет календарного события урока');
    return lessonPreparationApi.answersBytes(eventId);
  });
  const doc = pdf.status === 'ready' ? pdf.doc : null;
  const page = usePdfPage(doc, Math.min(pageIndex, Math.max(0, (doc?.pageCount ?? 1) - 1)), 600);
  useLayoutEffect(() => {
    if (!page || !canvasRef.current) return;
    const canvas = canvasRef.current;
    canvas.width = page.canvas.width;
    canvas.height = page.canvas.height;
    canvas.getContext('2d')?.drawImage(page.canvas, 0, 0);
  }, [page]);

  async function upload(file: File) {
    if (!eventId) { setError('Урок не связан с календарём'); return; }
    if (!file.name.toLowerCase().endsWith('.pdf') && file.type !== 'application/pdf') {
      setError('Для решений нужен PDF'); return;
    }
    setBusy(true);
    setError(null);
    try {
      await lessonPreparationApi.uploadAnswers(eventId, file);
      const bytes = await lessonPreparationApi.answersBytes(eventId);
      if (!bytes.byteLength) throw new Error('Сервер вернул пустой PDF');
      setPageIndex(0);
      setRevision(v => v + 1);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Не удалось загрузить решения');
    } finally {
      setBusy(false);
    }
  }

  return (
    <aside id="classroom-homework-panel" className="classroom-homework" aria-label="Решения">
      <header className="classroom-homework__header">
        <strong>Решения</strong>
        <button type="button" className="classroom-homework__close" onClick={onClose} aria-label="Закрыть решения">×</button>
      </header>
      <div className="classroom-homework__body">
        <button type="button" disabled={busy || !eventId} onClick={() => inputRef.current?.click()}>
          {busy ? 'Загрузка…' : '＋ Загрузить PDF решений'}
        </button>
        <input ref={inputRef} type="file" accept=".pdf,application/pdf" style={{ display: 'none' }}
          onChange={e => { const file = e.target.files?.[0]; if (file) void upload(file); e.target.value = ''; }} />
        {error && <p role="alert">{error}</p>}
        {pdf.status === 'loading' && <p role="status">Загрузка решений…</p>}
        {pdf.status === 'error' && revision > 0 && <p role="alert">Не удалось открыть PDF: {pdf.message}</p>}
        {doc && <canvas ref={canvasRef} className="classroom-homework__page" aria-label="Страница решений" style={{ width: '100%', height: 'auto' }} />}
      </div>
      {doc && doc.pageCount > 1 && <nav className="classroom-homework__pagination">
        <button type="button" disabled={pageIndex === 0} onClick={() => setPageIndex(p => Math.max(0, p - 1))}>‹</button>
        <span>{pageIndex + 1} / {doc.pageCount}</span>
        <button type="button" disabled={pageIndex >= doc.pageCount - 1} onClick={() => setPageIndex(p => Math.min(doc.pageCount - 1, p + 1))}>›</button>
      </nav>}
    </aside>
  );
}
