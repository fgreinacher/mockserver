# MockServer Grafana Dashboard

`mockserver-server.json` is a standalone, importable Grafana dashboard for the
**MockServer server metric family** — request throughput and match outcomes,
request latency percentiles, registered expectations/actions, per-upstream
forward/proxy health, reliability/saturation signals (dropped log events, chaos
faults), JVM runtime (heap, GC, threads), and WebSocket callback levels.

It charts only the server's own Prometheus metrics. For the k6 load-injection
view (VUs, iterations, k6 throughput), see
[`../kubernetes/load-injection-observability`](../kubernetes/load-injection-observability).

## Prerequisites

1. **Enable metrics** — start MockServer with `mockserver.metricsEnabled=true`.
   When disabled, `/mockserver/metrics` returns `404` and no series exist.
2. **Scrape it with Prometheus** — point a Prometheus scrape at
   `GET /mockserver/metrics` (default port `1080`), or push via OTLP /
   Prometheus Remote-Write. In Kubernetes, the Helm chart can create a
   Prometheus Operator `ServiceMonitor` — set `serviceMonitor.enabled=true`
   (see `helm/mockserver/values.yaml`).

## Import

1. In Grafana: **Dashboards → New → Import**.
2. Upload `mockserver-server.json` (or paste its contents).
3. Select your Prometheus data source when prompted (the dashboard exposes a
   `datasource` variable, so it works with any Prometheus data source).

## Metrics used

Every panel references a metric documented in
[`docs/code/metrics.md`](../../docs/code/metrics.md), including the five
monotonic `_total` counters (`mock_server_requests_received_total`,
`mock_server_response_expectations_matched_total`,
`mock_server_expectations_not_matched_total`,
`mock_server_forward_expectations_matched_total`,
`mock_server_llm_chaos_injected_total`) — prefer these `_total` counters for
`rate()`/`increase()` over the legacy `_count` gauges. The dashboard also uses
the request-duration and per-upstream forward histograms, the dropped-log-events
and HTTP-chaos counters, the upstream circuit-breaker gauge, the `_actions_count`
level gauges, and the JVM runtime gauges.
