# Kubernetes Examples

Runnable MockServer deployments on Kubernetes. These use [k3d](https://k3d.io)
(k3s in Docker) so they run on any developer machine with Docker — no cloud
cluster required.

| Example | What it covers |
|---------|----------------|
| [`load-injection-observability/`](load-injection-observability/) | Drive a **load scenario** from MockServer and visualise its metrics in **Grafana** — over both **Prometheus** and **OpenTelemetry (OTLP)** — alongside JVM and pod CPU/memory. A one-command k3s stack: MockServer + Prometheus + OTEL Collector + Grafana with a provisioned dashboard. |

Each example is self-contained and follows the standard shape: one runnable
entrypoint (`run.sh`) and a `README.md` with **What it demonstrates ·
Prerequisites · Run · Expected output**.
