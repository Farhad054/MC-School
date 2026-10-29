import { getStrokeOutlinePoints, getStrokePoints, type StrokePoint } from 'perfect-freehand';
import { type Shape, type Viewport, letterbox, toPixels } from './annotations';

const PEN_THINNING = 0.55;
const TAP_OUTLINE_POINTS = 32;
const END_NOISE_THRESHOLD = 3;

/** Match ink thickness to the actual sheet, not a viewer's surrounding letterbox. */
export function strokeWidthPixels(width: number | undefined, viewport: Viewport, aspect: number | null): number {
  return Math.max(1, (width ?? 0.004) * letterbox(viewport, aspect).width);
}

/** Same pressure-aware ink geometry for whiteboard, previews and PDF export. */
export function penOutline(
  shape: Pick<Shape, 'points' | 'pressures' | 'width'>,
  viewport: Viewport,
  aspect: number | null,
): number[][] {
  const points = shape.points ?? [];
  if (!points.length || viewport.width <= 0 || viewport.height <= 0) return [];
  const pressure = shape.pressures;
  const hasPressure = pressure?.length === points.length &&
    pressure.every((value) => Number.isFinite(value) && value >= 0 && value <= 1);
  const size = strokeWidthPixels(shape.width, viewport, aspect);
  let samples = points.map(([x, y], index) => {
    const pixel = toPixels({ x, y }, viewport, aspect);
    return hasPressure ? [pixel.x, pixel.y, pressure![index]] : [pixel.x, pixel.y];
  });
  if (samples.length === 1) {
    // PF adds a synthetic (+1, +1) segment to a lone point, enlarging the first
    // contact at high zoom. Use its pressure radius without inventing movement.
    // A legacy [x, y] sample starts with pressure 0.25 in PF.
    const [x, y] = samples[0];
    const radius = size * (0.5 - PEN_THINNING * (0.5 - (hasPressure ? pressure![0] : 0.25)));
    return Array.from({ length: TAP_OUTLINE_POINTS }, (_, index) => {
      const angle = index * 2 * Math.PI / TAP_OUTLINE_POINTS;
      return [x + Math.cos(angle) * radius, y + Math.sin(angle) * radius];
    });
  }
  if (samples.length === 2 && hasPressure) {
    // Match PF's two-point subdivision while retaining pressure; its built-in
    // x/y interpolation drops it and gives the new samples pressure 0.5.
    const [start, end] = samples;
    samples = [start, ...Array.from({ length: 4 }, (_, index) => {
      const t = (index + 1) / 4;
      return start.map((value, axis) => value + (end[axis] - value) * t);
    })];
  }
  const options = {
    size,
    thinning: PEN_THINNING,
    smoothing: 0.6,
    streamline: 0.5,
    simulatePressure: !hasPressure,
    last: true,
  };
  const stroke = getStrokePoints(samples, options);
  const length = stroke[stroke.length - 1].runningLength;
  if (stroke.length > 1 && length > 0 && length < END_NOISE_THRESHOLD) {
    // PF skips non-final outline points within 3px of the end. On a tiny stroke
    // this skips the start too, making its cap as wide as the whole movement.
    // Enlarge only the outline calculation past that threshold, preserving all
    // proportions and pressure, then map it back to the original coordinates.
    const scale = (END_NOISE_THRESHOLD + 0.01) / length;
    const [originX, originY] = stroke[0].point;
    const enlarged = stroke.map<StrokePoint>((sample) => ({
      ...sample,
      point: [(sample.point[0] - originX) * scale, (sample.point[1] - originY) * scale],
      distance: sample.distance * scale,
      runningLength: sample.runningLength * scale,
    }));
    return getStrokeOutlinePoints(enlarged, { ...options, size: size * scale })
      .map(([x, y]) => [originX + x / scale, originY + y / scale]);
  }
  return getStrokeOutlinePoints(stroke, options);
}

export function tracePenOutline(
  ctx: {
    beginPath: () => void;
    moveTo: (x: number, y: number) => void;
    quadraticCurveTo: (cx: number, cy: number, x: number, y: number) => void;
    closePath: () => void;
  },
  outline: number[][],
): boolean {
  if (!outline.length) return false;
  ctx.beginPath();
  ctx.moveTo(outline[0][0], outline[0][1]);
  for (let i = 1; i < outline.length; i++) {
    const [x, y] = outline[i];
    const [nextX, nextY] = outline[(i + 1) % outline.length];
    ctx.quadraticCurveTo(x, y, (x + nextX) / 2, (y + nextY) / 2);
  }
  ctx.closePath();
  return true;
}
