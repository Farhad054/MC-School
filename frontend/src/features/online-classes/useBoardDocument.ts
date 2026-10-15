import { useEffect, useState } from 'react';
import { onlineClassesApi } from '../../api/onlineClasses';
import type { PdfDoc } from './pdfDocument';
import { usePdf } from './usePdf';

/**
 * The lesson's workbook as the board's document, for everyone in the class.
 *
 * A lesson without a workbook (or a failed download) simply yields no document
 * and the board stays blank — the lesson never waits on it.
 */
export function useBoardDocument(classId: string): PdfDoc | null {
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    const refresh = () => setRevision((value) => value + 1);
    window.addEventListener('mc-workbook-updated', refresh);
    return () => { window.removeEventListener('mc-workbook-updated', refresh); };
  }, [classId]);
  const state = usePdf(`${classId}:${revision}`, async () => {
    const bytes = await onlineClassesApi.documentBytes(classId);
    if (!bytes) throw new Error('no document');
    return bytes;
  });
  useEffect(() => {
    if (state.status === 'error') window.dispatchEvent(new CustomEvent('mc-workbook-render-error', { detail: `Не удалось открыть PDF на доске: ${state.message}` }));
  }, [state.status]);
  return state.status === 'ready' ? state.doc : null;
}
