import { describe, it, expect, vi, afterEach } from 'vitest';
import {
  runRepeatReplay,
  MAX_REPEAT_CONCURRENCY,
  MAX_REPEAT_ITERATIONS,
  type RepeatProgress,
} from '../lib/repeatReplay';

/** A manually-resolvable promise, for driving concurrency precisely. */
function deferred<T>() {
  let resolve!: (v: T) => void;
  let reject!: (e?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

describe('runRepeatReplay — driver', () => {
  it('issues exactly N calls, one per iteration index', async () => {
    const seen: number[] = [];
    const summary = await runRepeatReplay({
      iterations: 5,
      concurrency: 2,
      delayMs: 0,
      send: (i) => {
        seen.push(i);
        return Promise.resolve();
      },
    });

    expect(seen).toHaveLength(5);
    expect([...seen].sort((a, b) => a - b)).toEqual([0, 1, 2, 3, 4]);
    expect(summary).toEqual({ total: 5, succeeded: 5, failed: 0, aborted: false });
  });

  it('never runs more than `concurrency` calls in flight at once', async () => {
    const concurrency = 3;
    const iterations = 9;
    type Pending = ReturnType<typeof deferred<void>> & { done?: boolean };
    const pending: Pending[] = [];
    let inFlight = 0;
    let maxInFlight = 0;

    const send = () => {
      inFlight += 1;
      maxInFlight = Math.max(maxInFlight, inFlight);
      const d = deferred<void>() as Pending;
      pending.push(d);
      return d.promise.finally(() => {
        inFlight -= 1;
      });
    };

    const runPromise = runRepeatReplay({ iterations, concurrency, delayMs: 0, send });

    // Flush enough microtask turns for the pool to fully refill each wave.
    const flush = async () => {
      for (let i = 0; i < 8; i++) await Promise.resolve();
    };

    let settled = 0;
    while (settled < iterations) {
      await flush();
      // The invariant under test: never more than `concurrency` pending at once.
      expect(inFlight).toBeLessThanOrEqual(concurrency);
      // Resolve every currently in-flight call, then let the pool refill.
      const wave = pending.filter((d) => !d.done);
      expect(wave.length).toBeGreaterThan(0);
      for (const d of wave) {
        d.done = true;
        d.resolve();
        settled += 1;
      }
    }

    const summary = await runPromise;
    expect(summary.total).toBe(iterations);
    expect(summary.succeeded).toBe(iterations);
    expect(maxInFlight).toBe(concurrency);
  });

  it('honours the inter-request delay (fake timers)', async () => {
    vi.useFakeTimers();
    const seen: number[] = [];
    const send = (i: number) => {
      seen.push(i);
      return Promise.resolve();
    };

    const runPromise = runRepeatReplay({ iterations: 3, concurrency: 1, delayMs: 100, send });

    // First request fires immediately (no leading delay).
    await Promise.resolve();
    await Promise.resolve();
    expect(seen).toEqual([0]);

    // Nothing more until the 100ms delay elapses.
    await vi.advanceTimersByTimeAsync(99);
    expect(seen).toEqual([0]);

    await vi.advanceTimersByTimeAsync(1);
    expect(seen).toEqual([0, 1]);

    await vi.advanceTimersByTimeAsync(100);
    expect(seen).toEqual([0, 1, 2]);

    const summary = await runPromise;
    expect(summary.succeeded).toBe(3);
  });

  it('stops issuing calls once the signal aborts', async () => {
    const controller = new AbortController();
    let calls = 0;

    const summary = await runRepeatReplay({
      iterations: 10,
      concurrency: 1,
      delayMs: 0,
      signal: controller.signal,
      send: () => {
        calls += 1;
        return Promise.resolve();
      },
      onProgress: (p: RepeatProgress) => {
        // Abort after the first call completes; the worker must not issue more.
        if (p.done === 1) controller.abort();
      },
    });

    expect(calls).toBe(1);
    expect(summary.aborted).toBe(true);
    expect(summary.total).toBe(10);
    expect(summary.succeeded).toBe(1);
  });

  it('counts failures without aborting the run', async () => {
    const summary = await runRepeatReplay({
      iterations: 4,
      concurrency: 2,
      delayMs: 0,
      send: (i) => (i % 2 === 0 ? Promise.reject(new Error('boom')) : Promise.resolve()),
    });

    expect(summary).toEqual({ total: 4, succeeded: 2, failed: 2, aborted: false });
  });

  it('reports progress after every completed call', async () => {
    const snapshots: RepeatProgress[] = [];
    await runRepeatReplay({
      iterations: 3,
      concurrency: 1,
      delayMs: 0,
      send: () => Promise.resolve(),
      onProgress: (p) => snapshots.push({ ...p }),
    });

    // One initial 0/total emit, then one per completed call.
    expect(snapshots[0]).toEqual({ done: 0, succeeded: 0, failed: 0, total: 3 });
    expect(snapshots[snapshots.length - 1]).toEqual({ done: 3, succeeded: 3, failed: 0, total: 3 });
  });

  it('clamps iterations and concurrency to their maxima', async () => {
    let maxInFlight = 0;
    let inFlight = 0;
    const summary = await runRepeatReplay({
      iterations: MAX_REPEAT_ITERATIONS + 500,
      concurrency: MAX_REPEAT_CONCURRENCY + 50,
      delayMs: 0,
      send: () => {
        inFlight += 1;
        maxInFlight = Math.max(maxInFlight, inFlight);
        return Promise.resolve().then(() => {
          inFlight -= 1;
        });
      },
    });

    expect(summary.total).toBe(MAX_REPEAT_ITERATIONS);
    expect(maxInFlight).toBeLessThanOrEqual(MAX_REPEAT_CONCURRENCY);
  });

  it('does nothing for a zero iteration count', async () => {
    const send = vi.fn().mockResolvedValue(undefined);
    const summary = await runRepeatReplay({ iterations: 0, concurrency: 4, delayMs: 0, send });
    expect(send).not.toHaveBeenCalled();
    expect(summary).toEqual({ total: 0, succeeded: 0, failed: 0, aborted: false });
  });
});
