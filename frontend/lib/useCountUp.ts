'use client';

import { useEffect, useRef, useState } from 'react';

const COUNT_UP_DURATION_MS = 600;

// Animates from the previous value to a new one whenever target changes — including counting up
// from 0 the very first time a real value arrives, so the dashboard's hero number doesn't just
// snap into place. Cheap (requestAnimationFrame, no dependency), and a no-op while target is
// still undefined (loading).
export function useCountUp(target: number | undefined): number | undefined {
  const [displayValue, setDisplayValue] = useState<number | undefined>(undefined);
  const previousTarget = useRef<number | undefined>(undefined);

  useEffect(() => {
    if (target === undefined) return;

    const startValue = previousTarget.current ?? 0;
    previousTarget.current = target;

    if (startValue === target) {
      setDisplayValue(target);
      return;
    }

    let frame: number;
    const startTime = performance.now();

    const tick = (now: number) => {
      const progress = Math.min((now - startTime) / COUNT_UP_DURATION_MS, 1);
      const eased = 1 - Math.pow(1 - progress, 3);
      setDisplayValue(startValue + (target - startValue) * eased);
      if (progress < 1) {
        frame = requestAnimationFrame(tick);
      }
    };

    frame = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frame);
  }, [target]);

  return displayValue;
}
