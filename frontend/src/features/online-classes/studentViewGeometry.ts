import { letterbox, type Viewport } from './annotations';
import type { PageView } from './boardView';
import type { DocumentScroll } from './documentBoardView';

/** Visible window expressed relative to one page, including the surrounding paper margins. */
export interface StudentViewRegion {
  x: number;
  y: number;
  width: number;
  height: number;
}

/** Both rectangles must use the same coordinate space, for example getBoundingClientRect(). */
export interface StudentViewRect extends Viewport {
  left: number;
  top: number;
}

function validSize(size: Viewport): boolean {
  return Number.isFinite(size.width) && size.width > 0
    && Number.isFinite(size.height) && size.height > 0;
}

function validRect(rect: StudentViewRect): boolean {
  return validSize(rect) && Number.isFinite(rect.left) && Number.isFinite(rect.top);
}

function validRegion(region: StudentViewRegion): boolean {
  return validSize(region) && Number.isFinite(region.x) && Number.isFinite(region.y);
}

function validAspect(aspect: number | null): boolean {
  return aspect === null || (Number.isFinite(aspect) && aspect > 0);
}

/**
 * Capture the entire clear canvas viewport, undoing the same zoom and paper
 * letterbox used to draw it. Do not clamp to 0..1: a pupil may see margins or
 * the neighbouring page, and silently removing them would change the crop.
 */
export function capturePagedRegion(
  view: PageView,
  viewport: Viewport,
  pageAspect: number | null,
): StudentViewRegion | null {
  if (!validSize(viewport) || !validAspect(pageAspect) || !Number.isFinite(view.zoom) || view.zoom <= 0
    || !Number.isFinite(view.panX) || !Number.isFinite(view.panY)) return null;
  const page = letterbox(viewport, pageAspect);
  return captureContinuousRegion({ left: 0, top: 0, width: viewport.width, height: viewport.height }, {
    left: view.panX + page.left * view.zoom,
    top: view.panY + page.top * view.zoom,
    width: page.width * view.zoom,
    height: page.height * view.zoom,
  });
}

/**
 * Capture a scrolled document using the visible content rect and the active
 * page's live DOM rect. Browser scrolling is already reflected in those rects.
 * Exclude toolbars, padding and scrollbars from viewportRect before calling.
 */
export function captureContinuousRegion(
  viewportRect: StudentViewRect,
  pageRect: StudentViewRect,
): StudentViewRegion | null {
  if (!validRect(viewportRect) || !validRect(pageRect)) return null;
  const region = {
    x: (viewportRect.left - pageRect.left) / pageRect.width,
    y: (viewportRect.top - pageRect.top) / pageRect.height,
    width: viewportRect.width / pageRect.width,
    height: viewportRect.height / pageRect.height,
  };
  return validRegion(region) ? region : null;
}

/**
 * The teacher's clear follow frame must have the pupil viewport's aspect ratio.
 * Fit that frame inside the tile, leaving surrounding space instead of stretching
 * handwriting or showing an additional strip that the pupil cannot see.
 */
export function fitStudentViewport(sourceViewport: Viewport, availableViewport: Viewport): StudentViewRect | null {
  if (!validSize(sourceViewport) || !validSize(availableViewport)) return null;
  const scale = Math.min(availableViewport.width / sourceViewport.width, availableViewport.height / sourceViewport.height);
  const width = sourceViewport.width * scale;
  const height = sourceViewport.height * scale;
  const rect = { left: (availableViewport.width - width) / 2, top: (availableViewport.height - height) / 2, width, height };
  return validRect(rect) ? rect : null;
}

/**
 * Fit and centre the same normalized region in a paged canvas. A target frame
 * from fitStudentViewport makes the crop exact; a different ratio instead shows
 * the entire requested region with spare space. This is a remote presentation,
 * so local gesture clamps (zoom 1..5 and negative-only pan) must not be applied.
 */
export function viewForStudentRegion(
  region: StudentViewRegion,
  targetViewport: Viewport,
  pageAspect: number | null,
): PageView | null {
  if (!validRegion(region) || !validSize(targetViewport) || !validAspect(pageAspect)) return null;
  const page = letterbox(targetViewport, pageAspect);
  const zoom = documentZoomForStudentRegion(region, targetViewport, page);
  if (zoom === null) return null;
  const result = {
    zoom,
    panX: targetViewport.width / 2 - (page.left + (region.x + region.width / 2) * page.width) * zoom,
    panY: targetViewport.height / 2 - (page.top + (region.y + region.height / 2) * page.height) * zoom,
  };
  return Number.isFinite(result.panX) && Number.isFinite(result.panY) ? result : null;
}

/** Solve a continuous document's scale from its unzoomed page and the visible region. */
export function documentZoomForStudentRegion(
  region: StudentViewRegion,
  targetViewport: Viewport,
  basePageSize: Viewport,
): number | null {
  if (!validRegion(region) || !validSize(targetViewport) || !validSize(basePageSize)) return null;
  const zoom = Math.min(
    targetViewport.width / (region.width * basePageSize.width),
    targetViewport.height / (region.height * basePageSize.height),
  );
  return Number.isFinite(zoom) && zoom > 0 ? zoom : null;
}

/**
 * After applying documentZoomForStudentRegion, align the requested page region
 * to the clear viewport. Using current DOM rects accounts for scroll, padding,
 * borders and page offsets without guessing their CSS values. Return the raw
 * requested scroll: clamping it here would hide an impossible-to-match edge;
 * the caller owns the document's available scroll space and margins.
 */
export function documentScrollForStudentRegion(
  region: StudentViewRegion,
  viewportRect: StudentViewRect,
  pageRect: StudentViewRect,
  currentScroll: DocumentScroll,
): DocumentScroll | null {
  if (!validRegion(region) || !validRect(viewportRect) || !validRect(pageRect)
    || !Number.isFinite(currentScroll.left) || !Number.isFinite(currentScroll.top)) return null;
  const result = {
    left: currentScroll.left + pageRect.left + (region.x + region.width / 2) * pageRect.width
      - viewportRect.left - viewportRect.width / 2,
    top: currentScroll.top + pageRect.top + (region.y + region.height / 2) * pageRect.height
      - viewportRect.top - viewportRect.height / 2,
  };
  return Number.isFinite(result.left) && Number.isFinite(result.top) ? result : null;
}
