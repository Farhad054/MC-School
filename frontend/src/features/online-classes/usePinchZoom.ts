import { useEffect, useRef, type RefObject } from 'react';

/**
 * Two-finger zoom of a PDF reference pane only. The classroom canvas keeps
 * its own zoom and pen coordinates, independent from teacher solutions.
 *
 * Native touch listeners are required on iPad Safari so two-finger gestures
 * can cancel the browser's page zoom without interrupting single-finger scroll.
 */
export function usePinchZoom(
  elementRef: RefObject<HTMLDivElement | null>,
  zoom: number,
  onZoom: (value: number) => void,
  minZoom: number,
  maxZoom: number,
) {
  const latest = useRef({ zoom, onZoom, minZoom, maxZoom });
  latest.current = { zoom, onZoom, minZoom, maxZoom };

  useEffect(() => {
    const element = elementRef.current;
    if (!element) return;

    let gesture: { distance: number; zoom: number } | null = null;

    const distance = (touches: TouchList) => {
      if (touches.length < 2) return 0;
      const a = touches.item(0);
      const b = touches.item(1);
      if (!a || !b) return 0;
      return Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY);
    };

    const start = (event: TouchEvent) => {
      if (event.touches.length !== 2) {
        gesture = null;
        return;
      }
      gesture = { distance: distance(event.touches), zoom: latest.current.zoom };
    };

    const move = (event: TouchEvent) => {
      if (event.touches.length !== 2) {
        gesture = null;
        return;
      }
      if (!gesture) {
        start(event);
        return;
      }
      if (gesture.distance < 10) return;
      // Stop only the two-finger browser zoom. One-finger vertical PDF scroll
      // continues to use the browser's native momentum scrolling.
      if (event.cancelable) event.preventDefault();
      const { minZoom, maxZoom, onZoom, zoom } = latest.current;
      const scale = distance(event.touches) / gesture.distance;
      const next = Math.min(maxZoom, Math.max(minZoom, Math.round(gesture.zoom * scale * 20) / 20));
      if (Number.isFinite(next) && Math.abs(next - zoom) >= 0.025) {
        onZoom(next);
      }
    };

    const end = (event: TouchEvent) => {
      if (event.touches.length < 2) gesture = null;
    };

    element.addEventListener('touchstart', start, { passive: true });
    element.addEventListener('touchmove', move, { passive: false });
    element.addEventListener('touchend', end, { passive: true });
    element.addEventListener('touchcancel', end, { passive: true });
    return () => {
      element.removeEventListener('touchstart', start);
      element.removeEventListener('touchmove', move);
      element.removeEventListener('touchend', end);
      element.removeEventListener('touchcancel', end);
    };
  }, [elementRef]);
}
