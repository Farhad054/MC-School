import { useRef, useState, useLayoutEffect } from 'react';
import { lessonPreparationApi } from '../../api/lessonPreparation';
import { usePdf, usePdfPage } from './usePdf';
import type { PdfDoc } from './pdfDocument';
import { usePinchZoom } from './usePinchZoom';

/** Render each solution sheet separately to keep React hooks stable. */
function SolutionPage({ doc, index, zoom }: {
  doc: PdfDoc;
  index: number;
  zoom: number;
}) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const page = usePdfPage(doc, index, 800);
  useLayoutEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas || !page) return;
    canvas.width = page.canvas.width;
    canvas.height = page.canvas.height;
    canvas.getContext('2d')?.drawImage(page.canvas, 0, 0);
  }, [page]);
  return <section className="classroom-solutions__sheet" aria-label={`Страница ${index + 1}`}>
    <small>Страница {index + 1}</small>
    {page ? <canvas ref={canvasRef} className="classroom-homework__page"
      style={{ width: `${zoom * 100}%`, maxWidth: 'none', height: 'auto', display: 'block' }} /> : <p>Загрузка страницы…</p>}
  </section>;
}

/** Teacher-only answer key. Pages scroll vertically with independent zoom. */
export function SolutionsDrawer({ eventId, onClose }: { eventId?: string; onClose: () => void }) {
  const [revision, setRevision] = useState(0);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [zoom, setZoom] = useState(1);
  const inputRef = useRef<HTMLInputElement>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  usePinchZoom(scrollRef, zoom, setZoom, .6, 3);
  const pdf = usePdf(eventId ? `solutions:${eventId}:${revision}` : null, async () => {
    if (!eventId) throw new Error('Нет календарного события урока');
    return lessonPreparationApi.answersBytes(eventId);
  });
  const doc = pdf.status === 'ready' ? pdf.doc : null;

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
      setRevision(v => v + 1);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Не удалось загрузить решения');
    } finally {
      setBusy(false);
    }
  }

  return (
    <aside id="classroom-homework-panel" className="classroom-homework classroom-solutions" aria-label="Решения">
      <header className="classroom-homework__header">
        <strong>Решения {doc ? `· ${doc.pageCount} стр.` : ''}</strong>
        <button type="button" className="classroom-homework__close" onClick={onClose} aria-label="Закрыть решения">×</button>
      </header>
      <div className="classroom-solutions__toolbar">
        <button type="button" disabled={busy || !eventId} onClick={() => inputRef.current?.click()}>
          {busy ? 'Загрузка…' : '＋ Загрузить PDF решений'}
        </button>
        {doc && <div className="classroom-solutions__zoom" role="group" aria-label="Масштаб решений">
          <button type="button" onClick={() => setZoom(z => Math.max(.6, +(z - .2).toFixed(2)))} disabled={zoom <= .6}>−</button>
          <span>{Math.round(zoom * 100)}%</span>
          <button type="button" onClick={() => setZoom(z => Math.min(3, +(z + .2).toFixed(2)))} disabled={zoom >= 3}>+</button>
        </div>}
      </div>
      <input ref={inputRef} type="file" accept=".pdf,application/pdf" style={{ display: 'none' }}
        onChange={e => { const file = e.target.files?.[0]; if (file) void upload(file); e.target.value = ''; }} />
      {error && <p role="alert">{error}</p>}
      {pdf.status === 'loading' && <p role="status">Загрузка решений…</p>}
      {pdf.status === 'error' && <p role="alert">Не удалось открыть PDF: {pdf.message}</p>}
      <div ref={scrollRef} className="classroom-solutions__scroller">
        {doc && Array.from({ length: doc.pageCount }, (_, index) =>
          <SolutionPage key={index} doc={doc} index={index} zoom={zoom} />)}
      </div>
    </aside>
  );
}
