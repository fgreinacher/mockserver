# Startup Performance Harness

Measures MockServer start-up latency — from process/container launch to the
first successful `PUT /mockserver/status` — so start-time changes are
validated with numbers, not guesses. These scripts produced the evidence in
[docs/code/startup-performance.md](../../docs/code/startup-performance.md).

## Scripts

| Script | What it measures | When to use |
|--------|------------------|-------------|
| `bench_startup.py` | Launch→port-bind and launch→ready medians across a matrix of launch variants (JVM flags, jars, Docker images) | Comparing image/flag/JDK variants; regression-checking a startup change |
| `gap_probe.py` | Splits port-bind from readiness: times the first N sequential requests after the TCP port opens | Diagnosing first-request latency (lazy classloading vs bind cost) |
| `warmup_probe.py` | First-request latency 600 ms after port-open, `startupWarmup` on vs off | Validating the `startupWarmup` feature end-to-end |
| `bench_laptop.py` | Laptop-profile startup + footprint (programme item 8): docker launch→ready median-of-9, idle RSS + thread count at `--memory` 256m/512m/1g, the in-JVM start cost, `initializationJsonPath` scaling, and compressed image size. Emits a `laptop` result block. | The laptop / central-deploy profile: measuring first-run footprint and the per-test in-JVM start cost |
| `InJvmStartupBench.java` | The **in-JVM** start cost — `ClientAndServer.startClientAndServer(...)` inside one JVM, cold first launch vs warm steady-state median. Delegated to by `bench_laptop.py`. | The number `MockServerExtension` users pay per test class (item 8b) |

## Usage

```bash
# Variant matrix (java- and docker-kind variants; JAR/PORT placeholders substituted):
python3 scripts/perf/bench_startup.py scripts/perf/startup-variants.json \
  --jar mockserver/mockserver-netty/target/mockserver-netty-<version>-jar-with-dependencies.jar \
  --port 22080 --runs 5

# First-request decomposition:
python3 scripts/perf/gap_probe.py <path-to-jar-with-dependencies>

# startupWarmup on/off validation:
python3 scripts/perf/warmup_probe.py <path-to-jar-with-dependencies>

# Laptop profile (item 8) — all four sub-items, emitting a `laptop` result block:
python3 scripts/perf/bench_laptop.py all \
  --jar mockserver/mockserver-netty/target/mockserver-netty-<version>-jar-with-dependencies.jar \
  --image mockserver/mockserver:<tag> --out laptop-result.json
# Individual sub-items: ready | footprint | initscale | imagesize | readiness-demo
# The in-JVM cost on its own (item 8b):
java -cp <jar-with-dependencies> scripts/perf/InJvmStartupBench.java --warmups 1 --runs 9
```

## The `laptop` result block and its budgets (item 8)

`bench_laptop.py all` emits `{ "laptop": { <variant>: { <metric>: value } } }` — the same
`{variant: {metric}}` shape as `behaviours` in a perf run result, so `perf-test-compare.sh`
consumes it non-gating once its `metrics` function extracts a `laptop.*` clause **and**
`mockserver-performance-test/perf-budgets.json` carries the five wildcard budget keys:
`laptop.*.ready_ms`, `laptop.*.cold_ready_ms`, `laptop.*.rss_mb`, `laptop.*.threads`,
`laptop.*.compressed_bytes` (all `dir:"up"`, `min_pct:0.25`, `floor:null`, no `gating` →
notify-only until ≥10 runs of MAD accrue). The compare step **fails closed** on any emitted
metric with no budget key, so the budget entries must land (a reviewed `perf-budgets.json`
diff) **before** the producer emits the block, or the daily run goes red.

Readiness is `PUT /mockserver/status` == 200, never an open TCP port — MockServer accepts a
connection and then resets it during initialisation. `bench_laptop.py readiness-demo` prints
the gap between the two probes so the difference is visible, not asserted.

`bench_startup.py` writes a raw per-run CSV next to the variants file and
prints a median/min/max table. Docker-kind variants measure from `docker run`
(pre-pulled image) to ready — the same latency a Testcontainers user sees.

## Methodology notes

- Startup is a single-shot wall-clock event: compare **medians of ≥5 fresh
  launches**, never a warmed JMH loop. (JMH `SingleShotTime` in
  `mockserver-benchmark` is for costing individual subsystem inits, not
  whole-process startup.)
- The ready poll runs every 2 ms, which races the built-in `startupWarmup`
  self-request — tight-poll `ready` figures therefore UNDERSTATE the benefit
  warmup gives realistic pollers (e.g. Testcontainers strategies polling at
  hundreds-of-ms intervals). Use `warmup_probe.py` for that comparison.
- Absolute numbers are machine-specific; only compare runs from the same
  machine and session. Watch the max column for cold-cache outliers (first
  run after building an artifact is often slow).
- Ports: the scripts use the 22080+ range to avoid colliding with a developer
  MockServer on 1080. `bench_startup.py` takes `--port` (default 22080);
  `gap_probe.py` hardcodes **22082** and `warmup_probe.py` hardcodes
  **22083** — both scripts abort with a clear error if their port stays
  occupied or the JVM never binds (30 s deadline).
- The committed `startup-variants.json` pins explicit image version tags so
  CSV results stay comparable across sessions — bump the pins deliberately;
  don't switch them to `latest`.
