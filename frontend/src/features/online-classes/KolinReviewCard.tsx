import { useRef, useState } from 'react';
import type { KolinBoardContext } from '../../api/kolin';

export function KolinReviewCard({ context, preview, pages, de }: { context: KolinBoardContext; preview?: string;
  pages?: { pageNumber: number; imageDataUrl: string }[]; de: boolean }) {
  const dialog = useRef<HTMLDialogElement>(null);
  const [open, setOpen] = useState(false);
  const [selected, setSelected] = useState(context.pageNumber);
  const title = `${context.studentName}, ${de ? 'Seite' : 'страница'} ${selected}`;
  const image = pages?.find(page => page.pageNumber === selected)?.imageDataUrl ?? preview;
  const timestamp = new Date(context.capturedAt).toLocaleTimeString(de ? 'de-DE' : 'ru-RU', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
  return <div className="kolin-review">
    <strong>{context.scope === 'workbook' ? `${context.studentName}: ${context.totalPageCount} ${de ? 'Seiten' : 'страниц тетради'}` : title}</strong>
    <span>{de ? 'Gespeicherter Stand' : 'Сохранённое состояние'}: {timestamp}</span>
    {preview && <button type="button" className="kolin-review__thumbnail" aria-label={`${de ? 'Geprüfte Seite öffnen' : 'Открыть проверенную страницу'}: ${title}`}
      onClick={() => { dialog.current?.showModal(); setOpen(true); }}>
      <img src={image} alt={title} />
    </button>}
    {preview && <dialog ref={dialog} className="kolin-review__dialog" aria-label={title}
      onKeyDown={event => event.stopPropagation()}
      onClose={() => setOpen(false)} onClick={event => { if (event.target === event.currentTarget) dialog.current?.close(); }}>
      <header><strong>{title}, {timestamp}</strong><button type="button" autoFocus onClick={() => dialog.current?.close()}>
        {de ? 'Schließen' : 'Закрыть'}</button></header>
      {pages && pages.length > 1 && <select aria-label={de ? 'Geprüfte Seite' : 'Проверенная страница'} value={selected} onChange={event => setSelected(Number(event.target.value))}>
        {pages.map(page => <option key={page.pageNumber} value={page.pageNumber}>{de ? 'Seite' : 'Страница'} {page.pageNumber}</option>)}
      </select>}
      {open && <img src={image} alt={title} />}
    </dialog>}
    <small>{de ? 'Übermittelt wurden PDF und gespeicherte Notizen. Noch nicht gespeicherte Striche können fehlen.'
      : 'Переданы PDF и сохранённые записи. Ещё не сохранённые штрихи могли не попасть в снимок.'}</small>
  </div>;
}
