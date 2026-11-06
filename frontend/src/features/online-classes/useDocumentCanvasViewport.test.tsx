import { useLayoutEffect } from 'react';
import { act, cleanup, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { DocumentCanvasViewport } from './documentCanvasViewport';
import { useDocumentCanvasViewport } from './useDocumentCanvasViewport';

class ControlledResizeObserver {
  static instances: ControlledResizeObserver[] = [];
  observed = new Set<Element>();
  constructor(private callback: ResizeObserverCallback) {
    ControlledResizeObserver.instances.push(this);
  }
  observe = (element: Element) => { this.observed.add(element); };
  disconnect = vi.fn(() => { this.observed.clear(); });
  static resize(element: Element) {
    for (const observer of this.instances) {
      if (observer.observed.has(element)) observer.callback([], observer as unknown as ResizeObserver);
    }
  }
}

const frames = new Map<number, FrameRequestCallback>();
const fixtures: HTMLElement[] = [];
const page = { width: 6000, height: 8000 };
let frameId = 0;

function flushFrame() {
  act(() => {
    const pending = [...frames.values()];
    frames.clear();
    pending.forEach(callback => callback(0));
  });
}

function createDocument() {
  const board = document.createElement('div');
  board.className = 'whiteboard';
  const frame = board.appendChild(document.createElement('div'));
  frame.className = 'whiteboard__view-frame';
  const scroller = frame.appendChild(document.createElement('div'));
  scroller.className = 'whiteboard__pages--continuous';
  const surface = scroller.appendChild(document.createElement('div'));
  document.body.appendChild(board);
  fixtures.push(board);

  // jsdom has no layout: model the DOM measurements, including the scroller's
  // borders/scrollbars and an ancestor transform that leaves client sizes intact.
  const geometry = { scale: 1, width: 800, height: 600, pageLeft: -1000, pageTop: -2000 };
  Object.defineProperties(scroller, {
    clientWidth: { get: () => geometry.width },
    clientHeight: { get: () => geometry.height },
    offsetWidth: { get: () => geometry.width + 24 },
    offsetHeight: { get: () => geometry.height + 28 },
    clientLeft: { value: 4 },
    clientTop: { value: 2 },
  });
  vi.spyOn(scroller, 'getBoundingClientRect').mockImplementation(() => new DOMRect(
    100, 80, scroller.offsetWidth * geometry.scale, scroller.offsetHeight * geometry.scale,
  ));
  vi.spyOn(surface, 'getBoundingClientRect').mockImplementation(() => new DOMRect(
    100 + geometry.pageLeft * geometry.scale, 80 + geometry.pageTop * geometry.scale,
    page.width * geometry.scale, page.height * geometry.scale,
  ));
  return { board, frame, scroller, surface, ref: { current: surface }, geometry };
}

beforeEach(() => {
  frameId = 0;
  frames.clear();
  ControlledResizeObserver.instances = [];
  vi.stubGlobal('ResizeObserver', ControlledResizeObserver);
  vi.stubGlobal('devicePixelRatio', 2);
  vi.stubGlobal('requestAnimationFrame', vi.fn((callback: FrameRequestCallback) => {
    const id = ++frameId;
    frames.set(id, callback);
    return id;
  }));
  vi.stubGlobal('cancelAnimationFrame', vi.fn((id: number) => { frames.delete(id); }));
});

afterEach(() => {
  cleanup();
  fixtures.splice(0).forEach(element => element.remove());
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('useDocumentCanvasViewport', () => {
  it('moves only the visible raster window on scroll and prepares backing buffers before committing the new viewport', () => {
    const documentView = createDocument();
    const order: string[] = [];
    const previousDuringPrepare: Array<DocumentCanvasViewport | null> = [];
    let committed: DocumentCanvasViewport | null = null;
    const beforeChange = vi.fn(() => {
      previousDuringPrepare.push(committed);
      order.push('prepare');
    });
    const { result } = renderHook(() => {
      const viewport = useDocumentCanvasViewport(documentView.ref, true, page, true, beforeChange);
      useLayoutEffect(() => {
        if (viewport) { committed = viewport; order.push('commit'); }
      }, [viewport]);
      return viewport;
    });
    const initial = result.current!;
    expect(initial.visible).toBe(true);
    expect(initial.width).toBeLessThan(page.width / 2);
    expect(initial.height).toBeLessThan(page.height / 2);
    expect(order).toEqual(['prepare', 'commit']);

    act(() => {
      documentView.geometry.pageLeft -= 160;
      documentView.geometry.pageTop -= 320;
      for (let i = 0; i < 3; i++) documentView.scroller.dispatchEvent(new Event('scroll'));
    });
    expect(frames.size).toBe(1);
    expect(result.current).toBe(initial);
    flushFrame();

    expect(result.current).toMatchObject({ width: initial.width, height: initial.height, visible: true });
    expect(result.current!.x).toBeGreaterThan(initial.x);
    expect(result.current!.y).toBeGreaterThan(initial.y);
    expect(beforeChange).toHaveBeenCalledTimes(2);
    expect(previousDuringPrepare).toEqual([null, initial]);
    expect(order).toEqual(['prepare', 'commit', 'prepare', 'commit']);

    act(() => documentView.scroller.dispatchEvent(new Event('scroll')));
    flushFrame();
    expect(beforeChange).toHaveBeenCalledTimes(2);
  });

  it('remeasures an externally scaled follow frame through its real style observer and reacts to container resize', async () => {
    const documentView = createDocument();
    const beforeChange = vi.fn();
    const { result } = renderHook(() => useDocumentCanvasViewport(documentView.ref, true, page, true, beforeChange));
    const initial = result.current!;
    expect(initial.pixelRatio).toBe(2);

    // No React rerender, page resize, or scroll event: only the teacher tile's
    // ancestor transform changes. Let jsdom's real MutationObserver deliver it.
    await act(async () => {
      documentView.geometry.scale = .5;
      documentView.frame.style.transform = 'scale(0.5)';
    });
    expect(frames.size).toBe(1);
    flushFrame();
    const scaled = result.current!;
    expect(scaled.pixelRatio).toBe(1);
    expect(scaled.visible).toBe(true);
    // The source viewport keeps its logical size; only overscan grows in source
    // coordinates. Using unscaled clientWidth would nearly double this window.
    expect(scaled.width).toBeLessThan(initial.width * 1.5);

    act(() => {
      documentView.geometry.width = 400;
      ControlledResizeObserver.resize(documentView.board);
    });
    flushFrame();
    expect(result.current!.width).toBeLessThan(scaled.width);

    const beforeWindowResize = result.current!;
    act(() => {
      documentView.geometry.height = 300;
      window.dispatchEvent(new Event('resize'));
    });
    flushFrame();
    expect(result.current!.height).toBeLessThan(beforeWindowResize.height);
  });

  it('cancels queued work and removes scroll, resize, and presentation subscriptions on unmount', async () => {
    const documentView = createDocument();
    const beforeChange = vi.fn();
    const disconnectPresentation = vi.spyOn(MutationObserver.prototype, 'disconnect');
    const { unmount } = renderHook(() => useDocumentCanvasViewport(documentView.ref, true, page, true, beforeChange));
    act(() => {
      documentView.geometry.pageTop -= 320;
      documentView.scroller.dispatchEvent(new Event('scroll'));
    });
    const pending = [...frames.keys()][0];
    expect(pending).toBeDefined();
    unmount();
    expect(window.cancelAnimationFrame).toHaveBeenCalledWith(pending);
    expect(frames.size).toBe(0);
    expect(ControlledResizeObserver.instances[0].disconnect).toHaveBeenCalledOnce();
    expect(disconnectPresentation).toHaveBeenCalledOnce();

    const scheduled = vi.mocked(window.requestAnimationFrame).mock.calls.length;
    await act(async () => {
      documentView.scroller.dispatchEvent(new Event('scroll'));
      window.dispatchEvent(new Event('resize'));
      ControlledResizeObserver.resize(documentView.board);
      documentView.frame.style.transform = 'scale(0.25)';
    });
    flushFrame();
    expect(window.requestAnimationFrame).toHaveBeenCalledTimes(scheduled);
    expect(beforeChange).toHaveBeenCalledOnce();
  });
});
