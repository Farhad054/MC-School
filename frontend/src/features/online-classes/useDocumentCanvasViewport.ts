import { useLayoutEffect, useRef, useState, type RefObject } from 'react';
import type { Viewport } from './annotations';
import { computeDocumentCanvasViewport, type CanvasClientRect, type DocumentCanvasViewport } from './documentCanvasViewport';

function sameViewport(a: DocumentCanvasViewport | null, b: DocumentCanvasViewport | null) {
  return a === b || Boolean(a && b && a.x === b.x && a.y === b.y && a.width === b.width
    && a.height === b.height && a.pixelRatio === b.pixelRatio && a.visible === b.visible);
}

/** The scroller's visible client area, excluding its border and scrollbars. */
function clientViewport(element: HTMLElement): CanvasClientRect {
  const rect = element.getBoundingClientRect();
  const scaleX = element.offsetWidth > 0 ? rect.width / element.offsetWidth : 1;
  const scaleY = element.offsetHeight > 0 ? rect.height / element.offsetHeight : 1;
  return {
    left: rect.left + element.clientLeft * scaleX,
    top: rect.top + element.clientTop * scaleY,
    width: element.clientWidth * scaleX,
    height: element.clientHeight * scaleY,
  };
}

/** Follow the visible page window without changing the page or input geometry. */
export function useDocumentCanvasViewport(
  surface: RefObject<HTMLElement>,
  enabled: boolean,
  page: Viewport,
  active: boolean,
  beforeChange: (next: DocumentCanvasViewport) => void,
) {
  const [viewport, setViewport] = useState<DocumentCanvasViewport | null>(null);
  const current = useRef<DocumentCanvasViewport | null>(null);
  const latest = useRef({ page, active, beforeChange });
  latest.current = { page, active, beforeChange };
  const measureNow = useRef<(() => void) | null>(null);

  useLayoutEffect(() => {
    const element = surface.current;
    if (!enabled || !element) {
      current.current = null;
      setViewport(null);
      return;
    }
    const scroller = element.closest<HTMLElement>('.whiteboard__pages--continuous');
    const board = element.closest<HTMLElement>('.whiteboard');
    const frame = element.closest<HTMLElement>('.whiteboard__view-frame');
    const measure = () => {
      const state = latest.current;
      const next = computeDocumentCanvasViewport({
        page: state.page,
        pageRect: element.getBoundingClientRect(),
        viewportRect: scroller ? clientViewport(scroller)
          : { left: 0, top: 0, width: window.innerWidth, height: window.innerHeight },
        devicePixelRatio: window.devicePixelRatio,
        active: state.active,
      });
      if (sameViewport(current.current, next)) return;
      // Reduce backing density before React-Konva enlarges its window. This
      // prevents a transient oversized allocation when a mirror changes size.
      if (next) state.beforeChange(next);
      current.current = next;
      setViewport(next);
    };
    let request = 0;
    const schedule = () => {
      if (request) return;
      request = window.requestAnimationFrame(() => { request = 0; measure(); });
    };
    measureNow.current = measure;
    measure();
    scroller?.addEventListener('scroll', schedule, { passive: true });
    window.addEventListener('resize', schedule);
    const resize = scroller || board ? new ResizeObserver(schedule) : null;
    if (scroller) resize?.observe(scroller);
    if (board) resize?.observe(board);
    // A pupil mirror can change its outer CSS scale without changing the
    // logical dimensions of either its scroller or its page.
    const presentation = frame ? new MutationObserver(schedule) : null;
    if (frame) presentation?.observe(frame, { attributes: true, attributeFilter: ['style'] });
    return () => {
      window.cancelAnimationFrame(request);
      scroller?.removeEventListener('scroll', schedule);
      window.removeEventListener('resize', schedule);
      resize?.disconnect();
      presentation?.disconnect();
      measureNow.current = null;
    };
  }, [enabled, surface]);

  // Page resize/pinch and activation must update before paint, rather than
  // briefly showing a stretched or empty canvas until the next scroll event.
  useLayoutEffect(() => { measureNow.current?.(); }, [enabled, page.width, page.height, active]);

  return enabled ? viewport : null;
}
