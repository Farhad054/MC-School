import type { CSSProperties } from 'react';
import { letterbox, type Viewport } from './annotations';
import type { PageView, Point } from './boardView';
import type { NotebookProjection } from './notebookCameraGeometry';

const UNIT: Point[] = [{ x: 0, y: 0 }, { x: 1, y: 0 }, { x: 1, y: 1 }, { x: 0, y: 1 }];

/** Solve the presentation homography from the SAME mapping used for pen input. */
function presentationMatrix(projection: NotebookProjection): { h: number[]; corners: Point[] } | null {
  const corners = UNIT.map(projection.outputToCamera);
  if (corners.some(p => !Number.isFinite(p.x) || !Number.isFinite(p.y))) return null;
  const rows: number[][] = [];
  corners.forEach((p, i) => {
    const q = UNIT[i];
    rows.push([p.x, p.y, 1, 0, 0, 0, -p.x * q.x, -p.y * q.x, q.x]);
    rows.push([0, 0, 0, p.x, p.y, 1, -p.x * q.y, -p.y * q.y, q.y]);
  });
  for (let column = 0; column < 8; column++) {
    let pivot = column;
    for (let row = column + 1; row < 8; row++) {
      if (Math.abs(rows[row][column]) > Math.abs(rows[pivot][column])) pivot = row;
    }
    if (Math.abs(rows[pivot][column]) < 1e-12) return null;
    [rows[column], rows[pivot]] = [rows[pivot], rows[column]];
    const divisor = rows[column][column];
    for (let j = column; j <= 8; j++) rows[column][j] /= divisor;
    for (let row = 0; row < 8; row++) {
      if (row === column) continue;
      const factor = rows[row][column];
      for (let j = column; j <= 8; j++) rows[row][j] -= factor * rows[column][j];
    }
  }
  const h = [...rows.map(row => row[8]), 1];
  // CSS clips negative homogeneous W. Pick the equivalent sign which puts the
  // visible paper in front of the camera, including strong perspective crops.
  const center = projection.outputToCamera({ x: .5, y: .5 });
  const w = h[6] * center.x + h[7] * center.y + h[8];
  if (!Number.isFinite(w) || Math.abs(w) < 1e-12) return null;
  if (w < 0) for (let i = 0; i < h.length; i++) h[i] = -h[i];
  if (h.some(value => !Number.isFinite(value)) ||
    corners.some(p => h[6] * p.x + h[7] * p.y + h[8] <= 0)) return null;
  return { h, corners };
}

/**
 * Present the ordinary Konva layers directly. Only the background layer needs
 * animation; ink never waits for a video frame or a CPU/WebGL image warp.
 * Raw annotation coordinates, erasing, undo and export remain unchanged.
 */
export function notebookStagePresentation(
  projection: NotebookProjection | null,
  viewport: Viewport,
  view: PageView,
  rawAspect = projection ? projection.rawSize.width / projection.rawSize.height : 1,
): CSSProperties | null {
  if (!projection || ![viewport.width, viewport.height, projection.rawSize.width,
    projection.rawSize.height, projection.aspect, rawAspect, view.zoom].every(value => Number.isFinite(value) && value > 0)
    || !Number.isFinite(view.panX) || !Number.isFinite(view.panY)) return null;
  const solved = presentationMatrix(projection);
  if (!solved) return null;
  const { h, corners } = solved;
  const box = letterbox(viewport, projection.aspect);
  const sx = box.width * view.zoom, sy = box.height * view.zoom;
  const tx = box.left * view.zoom + view.panX, ty = box.top * view.zoom + view.panY;
  const { width, height } = projection.rawSize;
  const rawBox = letterbox(projection.rawSize, rawAspect);
  // Konva uses a letterbox even for rounded source dimensions. Apply that
  // inverse as well so unusual camera resolutions do not introduce an offset.
  const a = h[0] / rawBox.width, b = h[1] / rawBox.height;
  const c = h[2] - a * rawBox.left - b * rawBox.top;
  const d = h[3] / rawBox.width, e = h[4] / rawBox.height;
  const f = h[5] - d * rawBox.left - e * rawBox.top;
  const g = h[6] / rawBox.width, j = h[7] / rawBox.height;
  const k = h[8] - g * rawBox.left - j * rawBox.top;
  // CSS matrix3d is column-major; input coordinates here are raw CSS pixels.
  const matrix = [
    sx * a + tx * g, sy * d + ty * g, 0, g,
    sx * b + tx * j, sy * e + ty * j, 0, j,
    0, 0, 1, 0,
    sx * c + tx * k, sy * f + ty * k, 0, k,
  ];
  if (matrix.some(value => !Number.isFinite(value))) return null;
  return {
    position: 'absolute', left: 0, top: 0, width, height,
    transformOrigin: '0 0', transform: `matrix3d(${matrix.join(',')})`,
    // Clip in raw space before projecting: marks outside the selected paper
    // must not leak into the letterbox or cross a projective horizon.
    clipPath: `polygon(${corners.map(p => `${(rawBox.left + p.x * rawBox.width) / width * 100}% ${(rawBox.top + p.y * rawBox.height) / height * 100}%`).join(',')})`,
    pointerEvents: 'none',
  };
}
