import type { Viewport } from './annotations';

export interface CanvasClientRect extends Viewport {
  left: number;
  top: number;
}

/** A small raster window positioned inside the full logical document page. */
export interface DocumentCanvasViewport extends Viewport {
  x: number;
  y: number;
  /** Requested density; the caller still applies the canvas memory limits. */
  pixelRatio: number;
  visible: boolean;
}

/** Screen CSS pixels, before accounting for a teacher's externally scaled tile. */
export const DOCUMENT_CANVAS_OVERSCAN = 64;
export const DOCUMENT_CANVAS_QUANTUM = 32;

function validSize(size: Viewport) {
  return Number.isFinite(size.width) && size.width > 0
    && Number.isFinite(size.height) && size.height > 0;
}

function validRectPosition(rect: CanvasClientRect) {
  return Number.isFinite(rect.left) && Number.isFinite(rect.top)
    && Number.isFinite(rect.width) && rect.width >= 0
    && Number.isFinite(rect.height) && rect.height >= 0;
}

function dormant(pixelRatio = 1): DocumentCanvasViewport {
  return { x: 0, y: 0, width: 1, height: 1, pixelRatio: Math.min(1, pixelRatio), visible: false };
}

/**
 * Render only the visible part of an enlarged page at its final screen density.
 * The full DOM page and normalized input coordinates remain unchanged. Both
 * rectangles are client-space measurements, so this also handles the external
 * CSS scale used to mirror a pupil's viewport inside a small teacher tile.
 *
 * Raster dimensions depend on the viewport, not the page intersection or scroll
 * offset. A small amount of overscan and quantized origins let ordinary panning
 * reuse the same backing dimensions instead of reallocating on each CSS pixel.
 */
export function computeDocumentCanvasViewport({
  page,
  pageRect,
  viewportRect,
  devicePixelRatio,
  active = true,
}: {
  page: Viewport;
  pageRect: CanvasClientRect;
  viewportRect: CanvasClientRect;
  devicePixelRatio: number;
  active?: boolean;
}): DocumentCanvasViewport | null {
  if (!validSize(page)) return null;
  if (!active) return dormant();
  if (!validRectPosition(pageRect) || !validRectPosition(viewportRect)) return null;
  if (!validSize(pageRect) || !validSize(viewportRect)) return dormant();

  const scaleX = pageRect.width / page.width;
  const scaleY = pageRect.height / page.height;
  const dpr = Number.isFinite(devicePixelRatio) && devicePixelRatio > 0 ? devicePixelRatio : 1;
  // Normally the scale is uniform. Taking the larger axis avoids undersampling
  // if fractional layout or an ancestor transform gives slightly different axes.
  const pixelRatio = dpr * Math.max(scaleX, scaleY);
  if (![scaleX, scaleY, pixelRatio].every(value => Number.isFinite(value) && value > 0)) return null;

  const left = Math.max(pageRect.left, viewportRect.left);
  const top = Math.max(pageRect.top, viewportRect.top);
  const right = Math.min(pageRect.left + pageRect.width, viewportRect.left + viewportRect.width);
  const bottom = Math.min(pageRect.top + pageRect.height, viewportRect.top + viewportRect.height);
  if (right <= left || bottom <= top) return dormant(pixelRatio);

  const axis = (pageLength: number, viewportLength: number, visibleStart: number, pageStart: number, scale: number) => {
    const quantum = DOCUMENT_CANVAS_QUANTUM / scale;
    const overscan = DOCUMENT_CANVAS_OVERSCAN / scale;
    // Reserve one extra quantum because the origin rounds down. This keeps at
    // least the requested overscan at the far edge, including between buckets.
    const screenLength = Math.ceil((viewportLength + 2 * DOCUMENT_CANVAS_OVERSCAN
      + DOCUMENT_CANVAS_QUANTUM) / DOCUMENT_CANVAS_QUANTUM) * DOCUMENT_CANVAS_QUANTUM;
    const length = Math.min(pageLength, screenLength / scale);
    const first = (visibleStart - pageStart) / scale;
    const origin = Math.min(pageLength - length, Math.max(0, Math.floor((first - overscan) / quantum) * quantum));
    return { origin, length };
  };
  const horizontal = axis(page.width, viewportRect.width, left, pageRect.left, scaleX);
  const vertical = axis(page.height, viewportRect.height, top, pageRect.top, scaleY);
  const result = {
    x: horizontal.origin,
    y: vertical.origin,
    width: horizontal.length,
    height: vertical.length,
    pixelRatio,
    visible: true,
  };
  return [result.x, result.y, result.width, result.height].every(Number.isFinite) ? result : null;
}
