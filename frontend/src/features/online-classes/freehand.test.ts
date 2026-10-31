import { describe, expect, it, vi } from 'vitest';
import { getStroke } from 'perfect-freehand';
import { toPixels } from './annotations';
import { penOutline, strokeWidthPixels, tracePenOutline } from './freehand';

const viewport = { width: 1000, height: 600 };
const points: [number, number][] = [[0.1, 0.2], [0.2, 0.2], [0.3, 0.25], [0.4, 0.3]];

function bounds(outline: number[][]) {
  const left = Math.min(...outline.map(([x]) => x));
  const right = Math.max(...outline.map(([x]) => x));
  const top = Math.min(...outline.map(([, y]) => y));
  const bottom = Math.max(...outline.map(([, y]) => y));
  return { width: right - left, height: bottom - top, x: (left + right) / 2, y: (top + bottom) / 2 };
}

describe('Perfect Freehand pen', () => {
  it('smooths existing two-coordinate lesson ink', () => {
    const outline = penOutline({ points, width: 0.008 }, viewport, null);
    expect(outline.length).toBeGreaterThan(3);
    expect(outline.every((pair) => pair.every(Number.isFinite))).toBe(true);
  });

  it('uses the page itself for consistent pen thickness on teacher and pupil screens', () => {
    // Both page boxes are 490 x 700, even though the teacher's panel is wider.
    const teacher = { width: 1400, height: 700 };
    const pupil = { width: 490, height: 700 };
    const thickness = strokeWidthPixels(0.01, teacher, 0.7);
    expect(thickness).toBeCloseTo(4.9);
    expect(strokeWidthPixels(0.01, pupil, 0.7)).toBeCloseTo(thickness);
    // Zoomed/retina pages retain the same normalized thickness relative to paper.
    expect(strokeWidthPixels(0.01, { width: 980, height: 1400 }, 0.7)).toBeCloseTo(2 * thickness);
  });

  it('varies its contour by stylus pressure', () => {
    const light = penOutline({ points, width: 0.008, pressures: [0.2, 0.2, 0.2, 0.2] }, viewport, null);
    const firm = penOutline({ points, width: 0.008, pressures: [0.9, 0.9, 0.9, 0.9] }, viewport, null);
    expect(light).not.toEqual(firm);
  });

  it.each([
    { pressure: 0, diameter: 0.45 },
    { pressure: 0.05, diameter: 0.505 },
    { pressure: 0.1, diameter: 0.56 },
    { pressure: 0.5, diameter: 1 },
    { pressure: 1, diameter: 1.55 },
  ])('keeps a thin tap centred and pressure-sized at pressure $pressure', ({ pressure, diameter }) => {
    const outline = penOutline({ points: [[0.1, 0.2]], width: 0.001, pressures: [pressure] }, viewport, null);
    const box = bounds(outline);
    expect(box.x).toBeCloseTo(100);
    expect(box.y).toBeCloseTo(120);
    expect(box.width).toBeCloseTo(diameter);
    expect(box.height).toBeCloseTo(diameter);
    // Every vertex belongs to the same centred circle; no synthetic diagonal.
    for (const [x, y] of outline) expect(Math.hypot(x - 100, y - 120)).toBeCloseTo(diameter / 2);
  });

  it('uses the legacy initial pressure for a tap without pressure samples', () => {
    const legacy = penOutline({ points: [[0.1, 0.2]], width: 0.001 }, viewport, null);
    const explicit = penOutline({ points: [[0.1, 0.2]], width: 0.001, pressures: [0.25] }, viewport, null);
    expect(legacy).toEqual(explicit);
    expect(bounds(legacy).width).toBeCloseTo(0.725);
    expect(penOutline({ points: [[0.1, 0.2]], width: 0.001, pressures: [NaN] }, viewport, null)).toEqual(legacy);
  });

  it('places a tap on the actual letterboxed page and scales it with the page', () => {
    const shape = { points: [[0.2, 0.25]] as [number, number][], width: 0.01, pressures: [0.1] };
    const outline = penOutline(shape, viewport, 0.7);
    const box = bounds(outline);
    expect(box.x).toBeCloseTo(374);
    expect(box.y).toBeCloseTo(150);
    expect(box.width).toBeCloseTo(2.352);
    const enlarged = penOutline(shape, { width: 2000, height: 1200 }, 0.7);
    enlarged.forEach(([x, y], index) => {
      expect(x).toBeCloseTo(2 * outline[index][0]);
      expect(y).toBeCloseTo(2 * outline[index][1]);
    });
  });

  it.each([
    { pressure: 0.05, diameter: 0.505 },
    { pressure: 0.1, diameter: 0.56 },
  ])('retains light pressure $pressure through a two-point stroke', ({ pressure, diameter }) => {
    const outline = penOutline({
      points: [[0.1, 0.2], [0.11, 0.2]], width: 0.001, pressures: [pressure, pressure],
    }, viewport, null);
    expect(bounds(outline).height).toBeCloseTo(diameter);
  });

  it.each([
    { pressure: 0.05, diameter: 0.505 },
    { pressure: 0.1, diameter: 0.56 },
  ])('keeps pressure $pressure steady from a tap through tiny movements', ({ pressure, diameter }) => {
    for (const length of [0, 0.1, 0.5, 1, 2, 2.99, 3, 3.01]) {
      for (const count of [2, 3, 4]) {
        const sampleCount = length === 0 ? 1 : count;
        const strokePoints: [number, number][] = Array.from({ length: sampleCount }, (_, index) => [
          0.1 + (sampleCount === 1 ? 0 : length * index / (sampleCount - 1) / viewport.width), 0.2,
        ]);
        const outline = penOutline({
          points: strokePoints, width: 0.001, pressures: Array(sampleCount).fill(pressure),
        }, viewport, null);
        const box = bounds(outline);
        const context = `${sampleCount} samples spanning ${length}px`;
        expect(box.height, context).toBeCloseTo(diameter);
        expect(box.width, context).toBeLessThanOrEqual(length + diameter + 1e-8);
        expect(box.y, context).toBeCloseTo(120);
      }
    }
  });

  it('has no thickness jump at the short-stroke cutoff and preserves the regular outline above it', () => {
    for (const length of [2.99, 3, 3.01]) {
      // Repeated starting positions leave PF's effective length equal to the
      // distance moved, so these cases straddle its exact 3px threshold.
      const strokePoints: [number, number][] = [[0.1, 0.2], [0.1, 0.2], [0.1 + length / viewport.width, 0.2]];
      const outline = penOutline({ points: strokePoints, width: 0.001, pressures: [0.1, 0.1, 0.1] }, viewport, null);
      expect(bounds(outline).height).toBeCloseTo(0.56);
      if (length >= 3) {
        const original = getStroke(strokePoints.map(([x, y]) => [x * viewport.width, y * viewport.height, 0.1]), {
          size: 1, thinning: 0.55, smoothing: 0.6, streamline: 0.5, simulatePressure: false, last: true,
        });
        expect(outline).toEqual(original);
      }
    }
  });

  it('keeps coincident pressure samples finite and bounded like a tap', () => {
    for (const count of [2, 3]) {
      const outline = penOutline({
        points: Array.from({ length: count }, () => [0.1, 0.2]), width: 0.001, pressures: Array(count).fill(0.05),
      }, viewport, null);
      expect(outline.length).toBeGreaterThan(3);
      for (const [x, y] of outline) expect(Math.hypot(x - 100, y - 120)).toBeCloseTo(0.2525);
    }
  });

  it('retains changing pressure at both ends of a two-point stroke', () => {
    const outline = penOutline({
      points: [[0.1, 0.2], [0.11, 0.2]], width: 0.001, pressures: [0.05, 0.9],
    }, viewport, null);
    const start = outline.find(([x, y]) => x === 100 && y < 120);
    const end = outline.find(([x, y]) => x === 110 && y < 120);
    expect(start?.[1]).toBeCloseTo(119.7475);
    expect(end?.[1]).toBeCloseTo(119.28);
    expect(bounds(outline).height).toBeCloseTo(1.44);
  });

  it.each([2, 3, 4])('keeps %i-point legacy contours exactly unchanged', (length) => {
    const legacyPoints = points.slice(0, length);
    const original = getStroke(legacyPoints.map(([x, y]) => [x * viewport.width, y * viewport.height]), {
      size: 8, thinning: 0.55, smoothing: 0.6, streamline: 0.5, simulatePressure: true, last: true,
    });
    expect(penOutline({ points: legacyPoints, width: 0.008 }, viewport, null)).toEqual(original);
    expect(penOutline({ points: legacyPoints, width: 0.008, pressures: [2] }, viewport, null)).toEqual(original);
  });

  it.each([3, 4])('keeps %i-point pressure contours exactly unchanged', (length) => {
    const strokePoints = points.slice(0, length);
    const pressures = [0.05, 0.1, 0.9, 0.2].slice(0, length);
    const original = getStroke(strokePoints.map(([x, y], index) => {
      const pixel = toPixels({ x, y }, viewport, 0.7);
      return [pixel.x, pixel.y, pressures[index]];
    }), {
      size: 3.36, thinning: 0.55, smoothing: 0.6, streamline: 0.5, simulatePressure: false, last: true,
    });
    expect(penOutline({ points: strokePoints, width: 0.008, pressures }, viewport, 0.7)).toEqual(original);
  });

  it('does not crash on no points or malformed pressures', () => {
    expect(penOutline({ points: [] }, viewport, null)).toEqual([]);
    expect(penOutline({ points, pressures: [2] }, viewport, null).length).toBeGreaterThan(0);
  });

  it('traces a fillable path suitable for on-screen rendering and PDF', () => {
    const context = { beginPath: vi.fn(), moveTo: vi.fn(), quadraticCurveTo: vi.fn(), closePath: vi.fn() };
    expect(tracePenOutline(context, [])).toBe(false);
    expect(tracePenOutline(context, [[0, 0], [10, 0], [10, 10], [0, 10]])).toBe(true);
    expect(context.beginPath).toHaveBeenCalledOnce();
    expect(context.quadraticCurveTo).toHaveBeenCalledTimes(3);
    expect(context.closePath).toHaveBeenCalledOnce();
  });
});
