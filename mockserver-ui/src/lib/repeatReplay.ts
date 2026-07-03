/**
 * Client-side driver for Charles-style "Repeat Advanced": re-send a captured
 * request N times with a bounded concurrency and an inter-request delay.
 *
 * MockServer's `PUT /mockserver/replay` endpoint is strictly single-shot — it
 * replays one request and returns one upstream response (see
 * `HttpState.handleReplay`). There is no server-side batching, so the fan-out is
 * orchestrated here from the dashboard: this driver issues the control-plane
 * replay calls with the chosen concurrency/delay, and the requests themselves
 * are re-issued BY THE SERVER through its matching/forwarding engine.
 *
 * Because every iteration only fans out a *control-plane* call (the real
 * outbound HTTP request happens server-side), concurrency is capped low: 20 is
 * far more than enough to keep the server busy while staying well under the
 * browser's per-origin socket limit and never flooding the single dashboard
 * connection with unbounded parallel requests.
 *
 * The driver is framework-agnostic (no React) so it can be unit-tested in
 * isolation with fake timers and a mock `send`.
 */

/** Hard cap on concurrent in-flight replay calls (see module doc). */
export const MAX_REPEAT_CONCURRENCY = 20;

/** Hard cap on the number of iterations a single Repeat run may issue. */
export const MAX_REPEAT_ITERATIONS = 1000;

/** Live progress snapshot pushed to `onProgress` after each completed call. */
export interface RepeatProgress {
  /** Completed calls (succeeded + failed). */
  done: number;
  /** Calls that resolved successfully. */
  succeeded: number;
  /** Calls that rejected (network error or server-rejected replay). */
  failed: number;
  /** Total calls this run will attempt (post-clamp). */
  total: number;
}

export interface RepeatOptions {
  /** Requested iteration count; clamped to `[0, MAX_REPEAT_ITERATIONS]`. */
  iterations: number;
  /** Requested concurrency; clamped to `[1, MAX_REPEAT_CONCURRENCY]`. */
  concurrency: number;
  /** Delay in ms inserted between successive requests on the same worker. */
  delayMs: number;
  /**
   * Issue a single replay for iteration `index`. Must resolve on success and
   * reject on failure — the driver counts resolutions vs rejections.
   */
  send: (index: number) => Promise<unknown>;
  /** Optional cancellation signal; once aborted no further calls are issued. */
  signal?: AbortSignal;
  /** Optional progress callback, invoked once up-front and after each call. */
  onProgress?: (progress: RepeatProgress) => void;
}

/** Terminal summary returned when the run settles (or is aborted). */
export interface RepeatSummary {
  total: number;
  succeeded: number;
  failed: number;
  /** True when the run was cancelled before every iteration completed. */
  aborted: boolean;
}

/** Resolve after `ms`, or immediately when the signal aborts. */
function abortableDelay(ms: number, signal?: AbortSignal): Promise<void> {
  if (ms <= 0) return Promise.resolve();
  return new Promise<void>((resolve) => {
    if (signal?.aborted) {
      resolve();
      return;
    }
    const timer = setTimeout(() => {
      cleanup();
      resolve();
    }, ms);
    const onAbort = () => {
      clearTimeout(timer);
      cleanup();
      resolve();
    };
    const cleanup = () => signal?.removeEventListener('abort', onAbort);
    signal?.addEventListener('abort', onAbort);
  });
}

/**
 * Run the Repeat Advanced fan-out.
 *
 * Spawns `min(concurrency, iterations)` cooperative workers that each pull the
 * next iteration index from a shared counter until the work is exhausted or the
 * signal aborts. A per-worker delay is inserted before every request except the
 * worker's first, giving a true "delay between requests" without a tight loop.
 *
 * Errors from `send` are caught and counted (never thrown) so one failing
 * upstream does not abort the whole run.
 */
export async function runRepeatReplay(options: RepeatOptions): Promise<RepeatSummary> {
  const { send, signal, onProgress } = options;
  const total = Math.max(0, Math.min(Math.floor(options.iterations) || 0, MAX_REPEAT_ITERATIONS));
  const concurrency = Math.max(1, Math.min(Math.floor(options.concurrency) || 1, MAX_REPEAT_CONCURRENCY));
  const delayMs = Math.max(0, Math.floor(options.delayMs) || 0);

  let next = 0;
  let succeeded = 0;
  let failed = 0;
  let done = 0;

  const emit = () => onProgress?.({ done, succeeded, failed, total });
  emit();

  const worker = async () => {
    let first = true;
    for (;;) {
      if (signal?.aborted) return;
      const index = next;
      if (index >= total) return;
      next += 1;

      if (!first && delayMs > 0) {
        await abortableDelay(delayMs, signal);
        if (signal?.aborted) return;
      }
      first = false;

      try {
        await send(index);
        succeeded += 1;
      } catch {
        failed += 1;
      }
      done += 1;
      emit();
    }
  };

  const workerCount = Math.min(concurrency, total);
  await Promise.all(Array.from({ length: workerCount }, () => worker()));

  return { total, succeeded, failed, aborted: signal?.aborted ?? false };
}
