import { describe, expect, it } from 'vitest';
import { letterbox, type Viewport } from './annotations';
import { clampView, type PageView } from './boardView';
import {
  captureContinuousRegion,
  capturePagedRegion,
  documentScrollForStudentRegion,
  documentZoomForStudentRegion,
  fitStudentViewport,
  viewForStudentRegion,
  type StudentViewRegion,
} from './studentViewGeometry';

const A4 = 210 / 297;

function expectSameRegion(actual: StudentViewRegion | null, expected: StudentViewRegion) {
  expect(actual).not.toBeNull();
  for (const key of ['x', 'y', 'width', 'height'] as const) expect(actual![key]).toBeCloseTo(expected[key], 9);
}

function screenPoint(x: number, y: number, viewport: Viewport, aspect: number, view: PageView) {
  const paper = letterbox(viewport, aspect);
  return {
    x: (paper.left + x * paper.width) * view.zoom + view.panX,
    y: (paper.top + y * paper.height) * view.zoom + view.panY,
  };
}

describe('pupil viewport geometry', () => {
  it.each([
    { source: { width: 1200, height: 800 }, target: { width: 420, height: 700 }, aspect: A4 },
    { source: { width: 768, height: 1024 }, target: { width: 650, height: 350 }, aspect: A4 },
    { source: { width: 1024, height: 768 }, target: { width: 300, height: 700 }, aspect: 16 / 9 },
  ])('keeps the exact crop and proportional ink after fitting $source into $target', ({ source, target, aspect }) => {
    const sourceView = { zoom: 2.5, panX: -source.width * .8, panY: -source.height * .6 };
    const region = capturePagedRegion(sourceView, source, aspect)!;
    const frame = fitStudentViewport(source, target)!;
    expect(frame.width).toBeLessThanOrEqual(target.width);
    expect(frame.height).toBeLessThanOrEqual(target.height);
    expect(frame.width / frame.height).toBeCloseTo(source.width / source.height, 12);
    const followed = viewForStudentRegion(region, frame, aspect)!;
    expectSameRegion(capturePagedRegion(followed, frame, aspect), region);

    // A point on the paper must occupy the same fraction of each viewer's
    // clear viewport; this catches pan/letterbox errors in addition to zoom.
    const pupilPoint = screenPoint(.61, .44, source, aspect, sourceView);
    const teacherPoint = screenPoint(.61, .44, frame, aspect, followed);
    expect(teacherPoint.x / frame.width).toBeCloseTo(pupilPoint.x / source.width, 10);
    expect(teacherPoint.y / frame.height).toBeCloseTo(pupilPoint.y / source.height, 10);
  });

  it('retains margins outside the page instead of clamping them into the handwriting', () => {
    const source = { width: 1400, height: 700 };
    const region = capturePagedRegion({ zoom: 1, panX: 0, panY: 0 }, source, .7)!;
    expect(region.x).toBeLessThan(0);
    expect(region.width).toBeGreaterThan(1);
    expect(region.y).toBeCloseTo(0);
    const frame = fitStudentViewport(source, { width: 400, height: 500 })!;
    const view = viewForStudentRegion(region, frame, .7)!;
    expectSameRegion(capturePagedRegion(view, frame, .7), region);
  });

  it('keeps the same crop when the teacher switches between a small tile and a full workspace', () => {
    const source = { width: 1200, height: 800 };
    const region = capturePagedRegion({ zoom: 4, panX: -1650, panY: -970 }, source, A4)!;
    for (const available of [{ width: 400, height: 300 }, { width: 1800, height: 900 }, { width: 350, height: 850 }]) {
      const frame = fitStudentViewport(source, available)!;
      const view = viewForStudentRegion(region, frame, A4)!;
      expectSameRegion(capturePagedRegion(view, frame, A4), region);
    }
  });

  it('centres the whole requested region if a caller has not matched the source aspect ratio', () => {
    const region = { x: .2, y: .3, width: .4, height: .4 };
    const viewport = { width: 900, height: 300 };
    const view = viewForStudentRegion(region, viewport, 1)!;
    const topLeft = screenPoint(region.x, region.y, viewport, 1, view);
    const bottomRight = screenPoint(region.x + region.width, region.y + region.height, viewport, 1, view);
    expect(topLeft.x).toBeCloseTo(300);
    expect(topLeft.y).toBeCloseTo(0);
    expect(bottomRight.x).toBeCloseTo(600);
    expect(bottomRight.y).toBeCloseTo(300);
  });

  it('captures continuous scroll including the root offset and negative page margins', () => {
    const region = captureContinuousRegion(
      { left: 120, top: 180, width: 900, height: 600 },
      { left: 250, top: -300, width: 840, height: 1200 },
    )!;
    expectSameRegion(region, { x: -130 / 840, y: .4, width: 900 / 840, height: .5 });
  });

  it('reconstructs a continuous crop after zooming and applies scrolling without assuming padding', () => {
    const source = { width: 900, height: 600 };
    const region = captureContinuousRegion(
      { left: 120, top: 180, ...source },
      { left: 250, top: -300, width: 840, height: 1200 },
    )!;
    const frame = fitStudentViewport(source, { width: 450, height: 800 })!;
    const base = { width: 210, height: 300 };
    const zoom = documentZoomForStudentRegion(region, frame, base)!;
    expect(zoom).toBeCloseTo(2);
    const viewport = { left: 100, top: 250, width: frame.width, height: frame.height };
    const page = { left: 270, top: -40, width: base.width * zoom, height: base.height * zoom };
    const before = { left: 35, top: 710 };
    const after = documentScrollForStudentRegion(region, viewport, page, before)!;
    const movedPage = { ...page, left: page.left - (after.left - before.left), top: page.top - (after.top - before.top) };
    expectSameRegion(captureContinuousRegion(viewport, movedPage), region);
  });

  it('keeps remote crop scales outside local gesture limits while local navigation still clamps to 1..5', () => {
    const viewport = { width: 500, height: 500 };
    const wideRegion = { x: -.5, y: -.5, width: 2, height: 2 };
    const closeRegion = { x: .4, y: .4, width: .1, height: .1 };
    const wide = viewForStudentRegion(wideRegion, viewport, 1)!;
    const close = viewForStudentRegion(closeRegion, viewport, 1)!;
    expect(wide.zoom).toBeCloseTo(.5);
    expect(close.zoom).toBeCloseTo(10);
    expect(wide.panX).toBeGreaterThan(0);
    expectSameRegion(capturePagedRegion(wide, viewport, 1), wideRegion);
    expectSameRegion(capturePagedRegion(close, viewport, 1), closeRegion);
    expect(documentZoomForStudentRegion(wideRegion, viewport, viewport)).toBeCloseTo(.5);
    expect(documentZoomForStudentRegion(closeRegion, viewport, viewport)).toBeCloseTo(10);
    expect(clampView(wide, viewport).zoom).toBe(1);
    expect(clampView(close, viewport).zoom).toBe(5);
  });

  it('does not silently erase a requested document edge by clamping its scroll', () => {
    const result = documentScrollForStudentRegion(
      { x: -.2, y: -.1, width: 1, height: 1 },
      { left: 10, top: 30, width: 500, height: 500 },
      { left: 10, top: 30, width: 500, height: 500 },
      { left: 0, top: 0 },
    );
    expect(result).toEqual({ left: -100, top: -50 });
  });

  it('waits for usable measured geometry and rejects nonfinite inputs', () => {
    const region = { x: 0, y: 0, width: 1, height: 1 };
    const size = { width: 500, height: 600 };
    const rect = { ...size, left: 0, top: 0 };
    const view = { zoom: 1, panX: 0, panY: 0 };
    expect(capturePagedRegion(view, { width: 0, height: 600 }, A4)).toBeNull();
    expect(capturePagedRegion({ ...view, zoom: 0 }, size, A4)).toBeNull();
    expect(capturePagedRegion(view, size, Number.NaN)).toBeNull();
    expect(captureContinuousRegion(rect, { ...rect, height: 0 })).toBeNull();
    expect(captureContinuousRegion({ ...rect, left: Number.NaN }, rect)).toBeNull();
    expect(fitStudentViewport(size, { width: 0, height: 0 })).toBeNull();
    expect(fitStudentViewport({ width: Infinity, height: 500 }, size)).toBeNull();
    expect(viewForStudentRegion({ ...region, width: 0 }, size, A4)).toBeNull();
    expect(viewForStudentRegion({ ...region, x: Infinity }, size, A4)).toBeNull();
    expect(documentZoomForStudentRegion(region, size, { width: 0, height: 500 })).toBeNull();
    expect(documentScrollForStudentRegion(region, rect, rect, { left: Infinity, top: 0 })).toBeNull();
  });
});
