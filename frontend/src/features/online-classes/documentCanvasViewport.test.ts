import { describe, expect, it } from 'vitest';
import { canvasBackingRatio, MAX_CANVAS_PIXELS, MAX_CANVAS_SIDE } from './canvasBacking';
import {
  computeDocumentCanvasViewport,
  DOCUMENT_CANVAS_OVERSCAN,
  DOCUMENT_CANVAS_QUANTUM,
  type CanvasClientRect,
} from './documentCanvasViewport';

const viewportRect = { left: 80, top: 120, width: 1000, height: 800 };
const largePage = { width: 7000, height: 9900 };

function compute(pageRect: CanvasClientRect, viewport = viewportRect, extra = {}) {
  return computeDocumentCanvasViewport({ page: largePage, pageRect, viewportRect: viewport, devicePixelRatio: 2, ...extra })!;
}

describe('continuous document visible raster window', () => {
  it('keeps retina density for visible handwriting on a 7000×9900 page without allocating the whole sheet', () => {
    const crop = compute({ ...largePage, left: -1500, top: -3500 });
    expect(crop.visible).toBe(true);
    expect(crop.pixelRatio).toBe(2);
    expect(crop.width).toBeLessThanOrEqual(viewportRect.width + 2 * DOCUMENT_CANVAS_OVERSCAN + 2 * DOCUMENT_CANVAS_QUANTUM);
    expect(crop.height).toBeLessThanOrEqual(viewportRect.height + 2 * DOCUMENT_CANVAS_OVERSCAN + 2 * DOCUMENT_CANVAS_QUANTUM);
    expect(canvasBackingRatio(crop.width, crop.height, crop.pixelRatio)).toBe(2);
    expect(crop.width * crop.height * crop.pixelRatio ** 2).toBeLessThan(MAX_CANVAS_PIXELS);
    expect(Math.max(crop.width, crop.height) * crop.pixelRatio).toBeLessThan(MAX_CANVAS_SIDE);
    // The old full-page backing budget cannot retain even one physical pixel
    // per logical pixel at this zoom; the visible window retains two.
    expect(canvasBackingRatio(largePage.width, largePage.height, 2)).toBeLessThan(1);
  });

  it('covers the visible page plus overscan and stays within its logical bounds', () => {
    const pageRect = { ...largePage, left: -1500, top: -3500 };
    const crop = compute(pageRect);
    const visibleX = viewportRect.left - pageRect.left;
    const visibleY = viewportRect.top - pageRect.top;
    expect(visibleX - crop.x).toBeGreaterThanOrEqual(DOCUMENT_CANVAS_OVERSCAN);
    expect(visibleY - crop.y).toBeGreaterThanOrEqual(DOCUMENT_CANVAS_OVERSCAN);
    expect(crop.x + crop.width - visibleX - viewportRect.width).toBeGreaterThanOrEqual(DOCUMENT_CANVAS_OVERSCAN);
    expect(crop.y + crop.height - visibleY - viewportRect.height).toBeGreaterThanOrEqual(DOCUMENT_CANVAS_OVERSCAN);
    expect(crop.x).toBeGreaterThanOrEqual(0);
    expect(crop.y).toBeGreaterThanOrEqual(0);
    expect(crop.x + crop.width).toBeLessThanOrEqual(largePage.width);
    expect(crop.y + crop.height).toBeLessThanOrEqual(largePage.height);
  });

  it('does not reallocate for each scroll pixel and moves its origin only across quantized buckets', () => {
    const initial = compute({ ...largePage, left: -1500, top: -3500 });
    const withinBucket = compute({ ...largePage, left: -1501, top: -3501 });
    expect(withinBucket).toEqual(initial);
    const nextBucket = compute({ ...largePage, left: -1500 - DOCUMENT_CANVAS_QUANTUM, top: -3500 - DOCUMENT_CANVAS_QUANTUM });
    expect(nextBucket.width).toBe(initial.width);
    expect(nextBucket.height).toBe(initial.height);
    expect(nextBucket.x - initial.x).toBe(DOCUMENT_CANVAS_QUANTUM);
    expect(nextBucket.y - initial.y).toBe(DOCUMENT_CANVAS_QUANTUM);
  });

  it('keeps raster dimensions stable when reaching a page edge or when the viewport changes by one pixel', () => {
    const middle = compute({ ...largePage, left: -1500, top: -3500 });
    const edge = compute({ ...largePage, left: -6500, top: -9500 });
    expect(edge.visible).toBe(true);
    expect(edge.width).toBe(middle.width);
    expect(edge.height).toBe(middle.height);
    expect(edge.x + edge.width).toBe(largePage.width);
    expect(edge.y + edge.height).toBe(largePage.height);
    const resized = compute({ ...largePage, left: -1500, top: -3500 }, { ...viewportRect, width: viewportRect.width + 1 });
    expect(resized.width).toBe(middle.width);
    expect(resized.height).toBe(middle.height);
  });

  it('limits the crop to a small page rather than allocating a full-screen buffer around it', () => {
    const page = { width: 320, height: 450 };
    const crop = computeDocumentCanvasViewport({ page, pageRect: { ...page, left: 200, top: 150 }, viewportRect, devicePixelRatio: 2 })!;
    expect(crop).toEqual({ x: 0, y: 0, ...page, pixelRatio: 2, visible: true });
  });

  it('converts a shrunken follow frame back to logical page coordinates without oversampling every teacher tile', () => {
    const page = { width: 4000, height: 6000 };
    const pageRect = { left: -500, top: -1000, width: 2000, height: 3000 };
    const viewport = { left: 100, top: 150, width: 500, height: 400 };
    const crop = computeDocumentCanvasViewport({ page, pageRect, viewportRect: viewport, devicePixelRatio: 2 })!;
    expect(crop.pixelRatio).toBe(1);
    const visibleX = (viewport.left - pageRect.left) / .5;
    const visibleY = (viewport.top - pageRect.top) / .5;
    expect((visibleX - crop.x) * .5).toBeGreaterThanOrEqual(DOCUMENT_CANVAS_OVERSCAN);
    expect((visibleY - crop.y) * .5).toBeGreaterThanOrEqual(DOCUMENT_CANVAS_OVERSCAN);
    expect(crop.x + crop.width).toBeGreaterThanOrEqual(visibleX + viewport.width / .5);
    expect(crop.y + crop.height).toBeGreaterThanOrEqual(visibleY + viewport.height / .5);
    // Buffer extent corresponds to the visible screen area at device density,
    // even though the mirrored document's logical coordinates are much larger.
    expect(crop.width * crop.pixelRatio).toBeLessThanOrEqual((viewport.width + 192) * 2);
    expect(crop.height * crop.pixelRatio).toBeLessThanOrEqual((viewport.height + 192) * 2);
  });

  it('uses final screen density when the outer presentation enlarges the source viewport', () => {
    const crop = computeDocumentCanvasViewport({ page: { width: 1000, height: 1500 },
      pageRect: { left: -100, top: -300, width: 1500, height: 2250 }, viewportRect, devicePixelRatio: 2 })!;
    expect(crop.pixelRatio).toBe(3);
    expect(crop.width * 1.5).toBeLessThanOrEqual(viewportRect.width + 192);
    expect(crop.height * 1.5).toBeLessThanOrEqual(viewportRect.height + 192);
  });

  it.each([
    { left: 5000, top: 5000, ...largePage },
    { left: -8000, top: -11000, ...largePage },
    { left: 1080, top: 120, ...largePage },
  ])('releases the backing for pages outside the visible window: $left,$top', pageRect => {
    expect(compute(pageRect)).toEqual({ x: 0, y: 0, width: 1, height: 1, pixelRatio: 1, visible: false });
  });

  it('releases a hidden or inactive retained page, even before it has a display rect', () => {
    expect(compute({ left: -1500, top: -3500, ...largePage }, viewportRect, { active: false }))
      .toEqual({ x: 0, y: 0, width: 1, height: 1, pixelRatio: 1, visible: false });
    expect(compute({ left: 0, top: 0, width: 0, height: 0 }).visible).toBe(false);
    expect(compute({ left: 0, top: 0, ...largePage }, { left: 0, top: 0, width: 0, height: 0 }).visible).toBe(false);
  });

  it('rejects unusable geometry and safely falls back from an invalid device ratio', () => {
    const input = { page: largePage, pageRect: { left: 0, top: 0, ...largePage }, viewportRect, devicePixelRatio: 2 };
    expect(computeDocumentCanvasViewport({ ...input, page: { width: 0, height: 100 } })).toBeNull();
    expect(computeDocumentCanvasViewport({ ...input, page: { width: Infinity, height: 100 } })).toBeNull();
    expect(computeDocumentCanvasViewport({ ...input, viewportRect: { ...viewportRect, left: Number.NaN } })).toBeNull();
    expect(computeDocumentCanvasViewport({ ...input, devicePixelRatio: Number.NaN })?.pixelRatio).toBe(1);
    expect(computeDocumentCanvasViewport({ ...input, devicePixelRatio: -1 })?.pixelRatio).toBe(1);
  });
});
