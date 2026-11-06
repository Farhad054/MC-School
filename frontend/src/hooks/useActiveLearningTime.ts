import { useEffect, useRef } from 'react';
import { api } from '../api/client';

/** Count only visible, recently interactive time. Never record answers or mouse position.
 * Checkpoints are idempotent and stored for retry when connection is interrupted.
 */
export function useActiveLearningTime(kind: 'CARDS' | 'HOMEWORK', referenceId: string, enabled = true) {
  const ref = useRef({seconds: 0, lastTick: Date.now(), lastInteraction: Date.now()});
  useEffect(() => {
    if (!enabled || !referenceId) return;
    const state = ref.current;
    state.seconds = 0;
    state.lastTick = Date.now();
    state.lastInteraction = Date.now();
    let sending = false;
    const key = 'mindcrafti:learning-time-queue:v1';
    type Queued = {eventId: string; kind: 'CARDS' | 'HOMEWORK'; referenceId: string; seconds: number};
    const read = (): Queued[] => {
      try { const parsed = JSON.parse(localStorage.getItem(key) || '[]'); return Array.isArray(parsed) ? parsed.slice(0, 200) : []; }
      catch { return []; }
    };
    const flush = async () => {
      if (sending || !navigator.onLine) return;
      sending = true;
      try {
        for (const event of read().slice(0, 20)) {
          try {
            await api.learningTime.record(event);
            const rest = read().filter(item => item.eventId !== event.eventId);
            localStorage.setItem(key, JSON.stringify(rest));
          } catch { break; }
        }
      } finally { sending = false; }
    };
    const tick = () => {
      const now = Date.now();
      const delta = Math.max(0, Math.min(5, (now - state.lastTick) / 1000));
      state.lastTick = now;
      if (document.visibilityState === 'visible' && document.hasFocus() && now - state.lastInteraction < 60000) {
        state.seconds += delta;
      }
    };
    const checkpoint = () => {
      tick();
      const whole = Math.min(120, Math.floor(state.seconds));
      if (whole < 1) return;
      state.seconds -= whole;
      const queue = read();
      queue.push({eventId: crypto.randomUUID(), kind, referenceId, seconds: whole});
      localStorage.setItem(key, JSON.stringify(queue.slice(-200)));
      void flush();
    };
    const interaction = () => { state.lastInteraction = Date.now(); };
    const reset = () => { checkpoint(); state.lastTick = Date.now(); };
    const timer = window.setInterval(() => { tick(); if (state.seconds >= 15) checkpoint(); else void flush(); }, 5000);
    window.addEventListener('pointerdown', interaction, {passive:true});
    window.addEventListener('pointermove', interaction, {passive:true});
    window.addEventListener('keydown', interaction);
    window.addEventListener('touchstart', interaction, {passive:true});
    window.addEventListener('online', flush);
    window.addEventListener('pagehide', checkpoint);
    document.addEventListener('visibilitychange', reset);
    void flush();
    return () => {
      checkpoint(); window.clearInterval(timer);
      window.removeEventListener('pointerdown', interaction);
      window.removeEventListener('pointermove', interaction);
      window.removeEventListener('keydown', interaction);
      window.removeEventListener('touchstart', interaction);
      window.removeEventListener('online', flush);
      window.removeEventListener('pagehide', checkpoint);
      document.removeEventListener('visibilitychange', reset);
    };
  }, [kind, referenceId, enabled]);
}
