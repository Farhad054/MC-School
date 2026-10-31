import { useEffect, useMemo, useState } from 'react';

/** Keep midnight controls current even without a network refresh or a correct device clock. */
export function useLessonEntryClock(serverNow?: string): number {
  const anchor = useMemo(() => ({ server: serverNow ? Date.parse(serverNow) : NaN, elapsed: performance.now() }), [serverNow]);
  const [, tick] = useState(0);
  useEffect(() => {
    const timer = window.setInterval(() => tick(value => value + 1), 1000);
    return () => window.clearInterval(timer);
  }, []);
  return Number.isFinite(anchor.server) ? anchor.server + Math.max(0, performance.now() - anchor.elapsed) : Date.now();
}
