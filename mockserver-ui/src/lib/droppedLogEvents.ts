/**
 * Reads the cumulative "dropped log events" counter MockServer exposes on its
 * Prometheus metrics endpoint (`GET /mockserver/metrics`).
 *
 * When the server's log ring buffer is full, the oldest events are silently
 * evicted — the single most common cause of "verification intermittently fails"
 * and "the dashboard is missing requests". The server increments the
 * `mock_server_dropped_log_events_total` counter each time this happens, so a
 * non-zero value is a reliable signal that events have been lost and
 * `maxLogEntries` / `ringBufferSize` should be raised.
 *
 * We reuse the metrics endpoint the Metrics view already polls rather than
 * inventing a new control-plane endpoint. The counter is only present when the
 * server was started with metrics enabled; when it is absent the parse returns
 * 0 (nothing to warn about).
 */
import { parsePrometheusText, metricValue } from './prometheusParser';

// The server's Prometheus client (simpleclient 0.x / 1.x) appends a mandatory
// `_total` suffix to every counter, so the wire name is `..._total` even though
// the counter is registered as `mock_server_dropped_log_events` — matching the
// convention used for the other counters in MetricsView.tsx
// (e.g. `mock_server_http_chaos_injected_total`).
export const DROPPED_LOG_EVENTS_METRIC = 'mock_server_dropped_log_events_total';

/**
 * Extract the cumulative dropped-log-events count from a Prometheus exposition
 * document, or 0 when the counter is absent (metrics disabled, or no drops).
 */
export function parseDroppedLogEvents(prometheusText: string): number {
  return metricValue(parsePrometheusText(prometheusText), DROPPED_LOG_EVENTS_METRIC);
}
