import { beforeAll, describe, expect, it } from 'vitest';
import type { CameraModule, NotebookDocumentView } from '../notebook-camera/cameraModules';
import { letterbox } from './annotations';
import { createNotebookProjection, notebookRawToScreen, notebookScreenToRaw } from './notebookCameraGeometry';
import { notebookStagePresentation } from './notebookStagePresentation';

let runtime: CameraModule;
beforeAll(async () => {
  const path = '../../../public/notebook-camera/signal.mjs';
  runtime = await import(path);
});

const crop = [{ x: .1, y: .1 }, { x: .9, y: .2 }, { x: .8, y: .9 }, { x: .2, y: .8 }];
const strongCrop = [{ x: .48, y: .05 }, { x: .52, y: .05 }, { x: .99, y: .99 }, { x: .01, y: .99 }];
const inputs = [
  { rawWidth: 1920, rawHeight: 1080 }, { rawWidth: 1080, rawHeight: 1920 },
  { rawWidth: 4032, rawHeight: 3024 }, { rawWidth: 1933, rawHeight: 1011 },
];
const viewports = [{ width: 1000, height: 500 }, { width: 500, height: 900 }];
const views = [{ zoom: 1, panX: 0, panY: 0 }, { zoom: 2.25, panX: -135, panY: -80 }];

function projected(turn = 0, corners: NotebookDocumentView['corners'] = null, source = inputs[0]) {
  return createNotebookProjection({ ...source, renderer: runtime,
    view: { type: 'document-view', version: 1, turn, corners, ratio: corners ? 210 / 297 : null,
      sourceAspect: source.rawWidth / source.rawHeight } })!;
}

function applyCss(matrix: number[], point: { x: number; y: number }) {
  const w = matrix[3] * point.x + matrix[7] * point.y + matrix[15];
  expect(w).toBeGreaterThan(0);
  return { x: (matrix[0] * point.x + matrix[4] * point.y + matrix[12]) / w,
    y: (matrix[1] * point.x + matrix[5] * point.y + matrix[13]) / w };
}

describe('notebook live presentation uses the ordinary board layers', () => {
  it.each([0, 1, 2, 3])('matches the existing input/export mapping for turn=%i, crop, zoom and rounded raw sizes', turn => {
    for (const source of inputs) for (const corners of [null, crop, strongCrop]) {
      const projection = projected(turn, corners, source), before = JSON.stringify(projection.view);
      const aspect = source.rawWidth / source.rawHeight;
      const rawBox = letterbox(projection.rawSize, aspect);
      for (const viewport of viewports) for (const view of views) {
        const style = notebookStagePresentation(projection, viewport, view, aspect)!;
        const matrix = String(style.transform).slice(9, -1).split(',').map(Number);
        expect(matrix).toHaveLength(16);
        expect(style).toMatchObject({ position: 'absolute', left: 0, top: 0,
          width: projection.rawSize.width, height: projection.rawSize.height,
          transformOrigin: '0 0', pointerEvents: 'none' });
        expect(style.visibility).not.toBe('hidden');
        expect(style.clipPath).toMatch(/^polygon\(/);
        for (let y = 0; y <= 4; y++) for (let x = 0; x <= 4; x++) {
          const raw = projection.outputToCamera({ x: x / 4, y: y / 4 });
          const actual = applyCss(matrix, { x: rawBox.left + raw.x * rawBox.width, y: rawBox.top + raw.y * rawBox.height });
          const expected = notebookRawToScreen(raw, projection, viewport, view);
          expect(actual.x).toBeCloseTo(expected.x, 7); expect(actual.y).toBeCloseTo(expected.y, 7);
          const input = notebookScreenToRaw(actual, projection, viewport, view);
          expect(input.x).toBeCloseTo(raw.x, 7); expect(input.y).toBeCloseTo(raw.y, 7);
        }
      }
      expect(JSON.stringify(projection.view)).toBe(before);
    }
  });

  it('clips to the selected paper rather than displaying cropped marks in the margins', () => {
    const projection = projected(2, crop), style = notebookStagePresentation(projection, viewports[0], views[0])!;
    const vertices = String(style.clipPath).slice(8, -1).split(',').map(pair => pair.trim().split(/\s+/).map(parseFloat));
    const expected = [{ x: 0, y: 0 }, { x: 1, y: 0 }, { x: 1, y: 1 }, { x: 0, y: 1 }].map(projection.outputToCamera);
    expect(vertices).toHaveLength(4);
    vertices.forEach(([x, y], i) => {
      expect(x).toBeCloseTo(expected[i].x * 100, 7);
      expect(y).toBeCloseTo(expected[i].y * 100, 7);
    });
  });

  it('does not expose invalid geometry or a hidden zero-size viewport as a drawing surface', () => {
    const projection = projected();
    expect(notebookStagePresentation(null, viewports[0], views[0])).toBeNull();
    expect(notebookStagePresentation(projection, { width: 0, height: 500 }, views[0])).toBeNull();
    expect(notebookStagePresentation(projection, viewports[0], { ...views[0], zoom: 0 })).toBeNull();
    expect(notebookStagePresentation(projection, viewports[0], { ...views[0], panX: NaN })).toBeNull();
    expect(notebookStagePresentation({ ...projection, outputToCamera: () => ({ x: NaN, y: 0 }) }, viewports[0], views[0])).toBeNull();
    expect(notebookStagePresentation({ ...projection, outputToCamera: () => ({ x: .5, y: .5 }) }, viewports[0], views[0])).toBeNull();
  });
});
