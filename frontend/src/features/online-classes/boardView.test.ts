import { describe, expect, it } from 'vitest';
import {
  addPage,
  clampPage,
  clampView,
  resizeView,
  DEFAULT_VIEW,
  ensurePageCount,
  goToPage,
  initialPages,
  nextPage,
  pageLabel,
  panBy,
  pinchView,
  previousPage,
  screenToBoard,
  viewOf,
  withView,
  zoomAbout,
} from './boardView';

const board = { width: 800, height: 600 };

describe('page navigation', () => {
  it('starts on page 1 of 1 and labels it', () => {
    expect(pageLabel(initialPages())).toBe('1 / 1');
  });

  it('never leaves the valid range', () => {
    const pages = initialPages(3);
    expect(nextPage(goToPage(pages, 2)).current).toBe(2);
    expect(previousPage(pages).current).toBe(0);
    expect(clampPage(99, 3)).toBe(2);
    expect(clampPage(-4, 3)).toBe(0);
    expect(clampPage(Number.NaN, 3)).toBe(0);
  });

  it('adds a page after the last and opens it', () => {
    const added = addPage(initialPages(2));
    expect(added.count).toBe(3);
    expect(added.current).toBe(2);
    expect(pageLabel(added)).toBe('3 / 3');
  });

  it('only ever raises the count when told the document is longer', () => {
    const pages = initialPages(4);
    expect(ensurePageCount(pages, 2)).toBe(pages);
    expect(ensurePageCount(pages, 9).count).toBe(9);
  });

  it('keeps zoom and pan separately per page', () => {
    let pages = initialPages(2);
    pages = withView(pages, { zoom: 2, panX: -100, panY: -50 });
    pages = goToPage(pages, 1);
    expect(viewOf(pages)).toEqual(DEFAULT_VIEW);
    pages = goToPage(pages, 0);
    expect(viewOf(pages)).toEqual({ zoom: 2, panX: -100, panY: -50 });
  });
});

describe('view clamping', () => {
  it('allows no pan at zoom 1', () => {
    expect(clampView({ zoom: 1, panX: 40, panY: -40 }, board)).toEqual({ zoom: 1, panX: 0, panY: 0 });
  });

  it('keeps the page edge from passing the board edge when zoomed', () => {
    expect(clampView({ zoom: 2, panX: -5000, panY: 30 }, board)).toEqual({ zoom: 2, panX: -800, panY: 0 });
  });

  it('limits zoom to the supported range and survives garbage', () => {
    expect(clampView({ zoom: 99, panX: 0, panY: 0 }, board).zoom).toBe(5);
    expect(clampView({ zoom: Number.NaN, panX: Number.NaN, panY: 0 }, board)).toEqual(DEFAULT_VIEW);
  });
});

describe('teacher answers sidebar resize', () => {
  it('keeps the entire worksheet visible at normal zoom', () => {
    expect(resizeView(DEFAULT_VIEW, { width: 1024, height: 700 }, { width: 745, height: 700 }))
      .toEqual(DEFAULT_VIEW);
  });

  it('preserves the relative position of a zoomed page', () => {
    const old = { zoom: 2, panX: -500, panY: -180 };
    const next = resizeView(old, { width: 1000, height: 600 }, { width: 700, height: 600 });
    expect(next).toEqual({ zoom: 2, panX: -350, panY: -180 });
    expect(resizeView(next, { width: 700, height: 600 }, { width: 1000, height: 600 })).toEqual(old);
  });

  it('clamps old pan offsets within the smaller board', () => {
    expect(resizeView({ zoom: 2, panX: -9000, panY: -8000 },
      { width: 1000, height: 700 }, { width: 650, height: 500 }))
      .toEqual({ zoom: 2, panX: -650, panY: -500 });
  });
});

describe('gestures', () => {
  it('keeps the pinched point anchored under the fingers', () => {
    const start = DEFAULT_VIEW;
    const a0 = { x: 300, y: 300 };
    const b0 = { x: 500, y: 300 };
    // Fingers spread to double the distance around the same midpoint.
    const view = pinchView(start, a0, b0, { x: 200, y: 300 }, { x: 600, y: 300 }, board);
    expect(view.zoom).toBe(2);
    expect(screenToBoard({ x: 400, y: 300 }, view)).toEqual({ x: 400, y: 300 });
  });

  it('pans with two fingers moving together without changing zoom', () => {
    const start = { zoom: 2, panX: -200, panY: -100 };
    const view = pinchView(
      start,
      { x: 300, y: 300 },
      { x: 400, y: 300 },
      { x: 330, y: 320 },
      { x: 430, y: 320 },
      board,
    );
    expect(view.zoom).toBe(2);
    expect(view.panX).toBe(-170);
    expect(view.panY).toBe(-80);
  });

  it('zooms about a point and pans by deltas within bounds', () => {
    const zoomed = zoomAbout(DEFAULT_VIEW, 2, { x: 400, y: 300 }, board);
    expect(zoomed.zoom).toBe(2);
    expect(panBy(zoomed, 10000, 10000, board)).toEqual({ zoom: 2, panX: 0, panY: 0 });
  });
});
