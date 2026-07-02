import { useEffect, useState } from 'react';
import type { ConnectionParams } from './useConnectionParams';
import { buildBaseUrl } from '../lib/mcpClient';
import { parseDroppedLogEvents } from '../lib/droppedLogEvents';

/**
 * Polls the dropped-log-events counter from the server's Prometheus metrics
 * endpoint so the traffic/log views can warn when the ring buffer has evicted
 * events (see {@link parseDroppedLogEvents}).
 *
 * Design notes:
 * - Reuses `GET /mockserver/metrics` (already polled by the Metrics view) rather
 *   than a bespoke endpoint. Polls slowly (default 15s): the counter only ever
 *   grows and the banner is advisory, so a low cadence keeps overhead trivial.
 * - Stops polling permanently on a 404 — that means the server was started
 *   without metrics enabled, so the counter is unavailable and the banner can
 *   never fire; there is no point re-scraping.
 * - Pauses while the tab is hidden (mirrors {@link useMetricsPolling}).
 * - Returns `null` until the first successful scrape (and after a server change);
 *   callers treat `null` as "unknown — show nothing".
 */
const DEFAULT_POLL_INTERVAL_MS = 15000;

export function useDroppedLogEvents(
  params: ConnectionParams,
  intervalMs: number = DEFAULT_POLL_INTERVAL_MS,
): number | null {
  const baseUrl = buildBaseUrl(params);
  const [dropped, setDropped] = useState<number | null>(null);

  // Reset the count when the target server changes so a previous instance's
  // eviction count never leaks into a freshly-connected server. (React's
  // "adjust state while rendering" pattern — see useMetricsPolling.)
  const [prevBaseUrl, setPrevBaseUrl] = useState(baseUrl);
  if (prevBaseUrl !== baseUrl) {
    setPrevBaseUrl(baseUrl);
    setDropped(null);
  }

  useEffect(() => {
    let cancelled = false;
    let metricsDisabled = false;
    // Guards against overlapping poll chains: a hidden→visible transition (or a
    // re-scrape) can fire while a fetch is still in flight, and without this a
    // second chain would start — leaking a timer and doubling the cadence.
    let inFlight = false;
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;

    const isHidden = () => typeof document !== 'undefined' && document.hidden;

    function scheduleNext(): void {
      if (cancelled || metricsDisabled || isHidden()) return;
      timer = setTimeout(() => void poll(), intervalMs);
    }

    async function poll(): Promise<void> {
      if (inFlight) return; // a poll is already running — don't start a second chain
      inFlight = true;
      try {
        const res = await fetch(`${baseUrl}/mockserver/metrics`, { signal: controller.signal });
        if (cancelled) return;
        if (res.status === 404) {
          // Metrics disabled — the counter is unavailable; stop polling for good.
          metricsDisabled = true;
          return;
        }
        if (!res.ok) return; // transient error — retry on the next tick
        const text = await res.text();
        if (cancelled) return;
        setDropped(parseDroppedLogEvents(text));
      } catch {
        // Network failure or abort — swallow and retry on the next tick.
      } finally {
        inFlight = false;
        if (!cancelled) scheduleNext();
      }
    }

    function onVisibilityChange(): void {
      if (cancelled || metricsDisabled) return;
      if (isHidden()) {
        if (timer) { clearTimeout(timer); timer = undefined; }
        return;
      }
      if (timer) clearTimeout(timer);
      void poll();
    }

    void poll();
    if (typeof document !== 'undefined') {
      document.addEventListener('visibilitychange', onVisibilityChange);
    }
    return () => {
      cancelled = true;
      controller.abort();
      if (timer) clearTimeout(timer);
      if (typeof document !== 'undefined') {
        document.removeEventListener('visibilitychange', onVisibilityChange);
      }
    };
  }, [baseUrl, intervalMs]);

  return dropped;
}
