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
```

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
