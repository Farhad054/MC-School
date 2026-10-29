import { afterEach, describe, expect, it, vi } from 'vitest';
import { attachTabletInkInput, detectTabletInkBackend, type TabletInkBackend } from './tabletInkInput';

const mounted: Array<{ sheet: HTMLElement; driver: ReturnType<typeof attachTabletInkInput> }> = [];
afterEach(() => {
  for (const { sheet, driver } of mounted.splice(0)) {
    driver.destroy();
    sheet.remove();
  }
});

function attach(mode: TabletInkBackend) {
  const sheet = document.createElement('div');
  document.body.appendChild(sheet);
  const callbacks = { onStart: vi.fn(), onMove: vi.fn(), onEnd: vi.fn() };
  const driver = attachTabletInkInput(sheet, callbacks, { mode, pointerCapture: false });
  mounted.push({ sheet, driver });
  return { sheet, ...callbacks };
}

function pointer(sheet: HTMLElement, type: string, patch: PointerEventInit = {}) {
  const event = new Event(type, { bubbles: true, cancelable: true });
  const properties = { pointerId: 7, pointerType: 'pen', clientX: 10, clientY: 20,
    button: 0, width: 1, height: 1, pressure: .5, ...patch };
  for (const [key, value] of Object.entries(properties)) Object.defineProperty(event, key, { value });
  sheet.dispatchEvent(event);
}

function touch(sheet: HTMLElement, type: string, patch: { clientX?: number; force?: number } = {}) {
  const event = new Event(type, { bubbles: true, cancelable: true });
  Object.defineProperty(event, 'changedTouches', { value: [
    { identifier: 7, clientX: 10, clientY: 20, touchType: 'stylus', force: .5, ...patch },
  ] });
  sheet.dispatchEvent(event);
}

describe('cross-platform ink backend selection', () => {
  it('uses Touch Events on classic iPads with Apple Pencil', () => {
    expect(detectTabletInkBackend({
      userAgent: 'Mozilla/5.0 (iPad; CPU OS 17_0 like Mac OS X) AppleWebKit/605.1.15',
      maxTouchPoints: 5, supportsPointer: true, supportsTouch: true,
    })).toBe('ios-touch');
  });
  it('recognizes iPadOS desktop Safari user agent', () => {
    expect(detectTabletInkBackend({
      userAgent: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15',
      maxTouchPoints: 5, supportsPointer: true, supportsTouch: true,
    })).toBe('ios-touch');
  });
  it('uses Pointer Events for Samsung Android tablets and S Pen', () => {
    expect(detectTabletInkBackend({
      userAgent: 'Mozilla/5.0 (Linux; Android 15; SM-X910) AppleWebKit/537.36 Chrome/130',
      maxTouchPoints: 10, supportsPointer: true, supportsTouch: true,
    })).toBe('pointer');
  });
  it('uses Pointer Events for desktop browser mice', () => {
    expect(detectTabletInkBackend({
      userAgent: 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/130',
      maxTouchPoints: 0, supportsPointer: true, supportsTouch: false,
    })).toBe('pointer');
  });
  it('falls back to Touch Events on older mobile browsers', () => {
    expect(detectTabletInkBackend({
      userAgent: 'Mozilla/5.0 (Linux; Android 7; Tablet)',
      maxTouchPoints: 5, supportsPointer: false, supportsTouch: true,
    })).toBe('legacy-touch');
  });
});

describe('tablet ink pressure and event ownership', () => {
  it.each([
    ['zero contact', 0, 0],
    ['light contact', .1, .1],
    ['ordinary contact', .5, .5],
    ['maximum contact', 1, 1],
    ['negative outlier', -.2, 0],
    ['high outlier', 1.2, 1],
    ['missing pressure', undefined, .5],
    ['NaN pressure', Number.NaN, .5],
    ['infinite pressure', Number.POSITIVE_INFINITY, .5],
  ] as const)('preserves or bounds %s on both native Pencil and pointer input', (_name, raw, expected) => {
    const native = attach('ios-touch');
    touch(native.sheet, 'touchstart', { force: raw });
    expect(native.onStart).toHaveBeenCalledExactlyOnceWith(
      expect.objectContaining({ pressure: expected }),
      { source: 'touch', device: 'pen', inputId: 7 },
    );

    const standard = attach('pointer');
    pointer(standard.sheet, 'pointerdown', { pressure: raw });
    expect(standard.onStart).toHaveBeenCalledExactlyOnceWith(
      expect.objectContaining({ pressure: expected }),
      { source: 'pointer', device: 'pen', inputId: 7 },
    );
  });

  it('keeps native Pencil pressure through contact and release without duplicating its pointer events', () => {
    const input = attach('ios-touch');
    touch(input.sheet, 'touchstart', { force: 0 });
    pointer(input.sheet, 'pointerdown', { pressure: .8 });
    touch(input.sheet, 'touchmove', { clientX: 20, force: .1 });
    pointer(input.sheet, 'pointermove', { clientX: 20, pressure: .8 });
    touch(input.sheet, 'touchmove', { clientX: 30, force: .5 });
    pointer(input.sheet, 'pointerup', { clientX: 30, pressure: 0 });
    expect(input.onEnd).not.toHaveBeenCalled();
    touch(input.sheet, 'touchend', { clientX: 35, force: 0 });

    expect(input.onStart).toHaveBeenCalledOnce();
    expect(input.onStart.mock.calls[0][0].pressure).toBe(0);
    expect(input.onMove.mock.calls.map(([point]) => point.pressure)).toEqual([.1, .5]);
    expect(input.onEnd).toHaveBeenCalledExactlyOnceWith(
      expect.objectContaining({ clientX: 35, pressure: 0 }),
      { source: 'touch', device: 'pen', inputId: 7 }, false,
    );
  });

  it.each(['pen', 'mouse'])('preserves the final zero-pressure sample for a %s tap without dropping the contact', device => {
    const input = attach('pointer');
    pointer(input.sheet, 'pointerdown', { pointerType: device, pressure: .5 });
    pointer(input.sheet, 'pointerup', { pointerType: device, pressure: 0 });
    expect(input.onStart).toHaveBeenCalledExactlyOnceWith(
      expect.objectContaining({ pressure: .5 }),
      { source: 'pointer', device, inputId: 7 },
    );
    expect(input.onMove).not.toHaveBeenCalled();
    expect(input.onEnd).toHaveBeenCalledExactlyOnceWith(
      expect.objectContaining({ clientX: 10, clientY: 20, pressure: 0 }),
      { source: 'pointer', device, inputId: 7 }, false,
    );
  });
});
