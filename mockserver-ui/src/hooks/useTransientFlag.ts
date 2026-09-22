import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * A boolean-ish flag that flips on and resets itself after a delay — the "Copied!"
 * affordance every copy button in the dashboard uses.
 *
 * It exists because the obvious spelling leaks a timer:
 *
 *   setCopied(true);
 *   setTimeout(() => setCopied(false), 2000);   // nothing cancels this
 *
 * If the component unmounts inside the delay, the timer still fires and calls
 * setState on a component that is gone. In the browser that is a harmless warning.
 * Under vitest it is a **build failure**: the test file's jsdom environment is torn
 * down when the file finishes, so a timer outliving it dereferences a `window` that
 * no longer exists and React throws `ReferenceError: window is not defined`. Vitest
 * reports that as an unhandled error and exits non-zero **even though every test
 * passed** — which is exactly how it presented in CI (`196 passed / 3084 passed`,
 * then `exit status 1`). It is timing-dependent, so it flakes rather than failing
 * consistently.
 *
 * The hook owns the timer, cancels a pending reset when re-triggered, and clears it
 * on unmount, so no timer can outlive the component.
 */
export function useTransientFlag<T>(
  initial: T,
): [T, (value: T, resetTo: T, delayMs: number) => void] {
  const [value, setValue] = useState<T>(initial);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(
    () => () => {
      if (timerRef.current !== null) clearTimeout(timerRef.current);
    },
    [],
  );

  const flash = useCallback((next: T, resetTo: T, delayMs: number) => {
    if (timerRef.current !== null) clearTimeout(timerRef.current);
    setValue(next);
    timerRef.current = setTimeout(() => {
      timerRef.current = null;
      setValue(resetTo);
    }, delayMs);
  }, []);

  return [value, flash];
}
