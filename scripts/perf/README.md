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
| `InJvmParallelBench.java` | **N in-JVM instances in ONE JVM** (programme item 17, in-JVM shape): per-instance startup, the LIVE thread count (counted via `ThreadMXBean`, with a name-prefix histogram), aggregate RSS / TCP sockets, heap used, the per-instance store capacity across launch order, and per-instance light-load p95 as a distribution. `--devMode true` is the control arm. | The `MockServerExtension` "lots of parallel tests in one JVM" profile: thread/heap footprint, the store-sizing freeze, and the `devMode` saving |
| `parallel_instances.py` | **N containers on one host** (item 17, container shape): per-container startup (cold vs warm), aggregate + per-container RSS, per-container live thread count, at `{1,4,8,16,32}`. `--cpuset`/`--cpus` show the cgroup-aware pool sizing. | Comparing the container shape (N JVMs, N baselines, cgroup-sized) against the in-JVM shape (one shared JVM) |
| `InJvmSuiteBench.java` | **Suite-level decomposition** (programme item 22): the per-test-method / per-test-class profile in ONE JVM. Times three regions per instance — `call` (`startClientAndServer` returns; port bind is inside it), `ready` (`PUT /mockserver/status` == 200), and `stop` (`server.stop()` returns) — for the cold first instance (reported alone; it pays class load), a SEQUENTIAL warm run (per-method, serial), and a CONCURRENT warm batch (per-method, parallel — the stated profile, with peak live thread count). Stops every instance and asserts **0 MockServer-owned threads survive** (the real leak signal, isolated from the harness's own JDK HttpClient pool). Emits a suite projection for a stated `classes x methods` shape. `--devMode true` is the control arm. | The `MockServerExtension` / `MockServerRule` "instance per method/class, often in parallel" profile: per-instance start AND stop cost, first-vs-subsequent, sequential-vs-concurrent |

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

# Item 17 — N parallel instances, IN-JVM shape (one JVM; run the devMode control arm too):
java -Xmx2g -cp <jar-with-dependencies> scripts/perf/InJvmParallelBench.java \
  --counts 1,4,8,16,32 --devMode false --loadWarmup 50 --loadMeasured 300
java -Xmx2g -cp <jar-with-dependencies> scripts/perf/InJvmParallelBench.java \
  --counts 1,4,8,16,32 --devMode true                      # control arm
# Reproduce the store-sizing freeze SWING (compare maxLogFirst across separate runs):
java -Xmx1g -cp <jar-with-dependencies> scripts/perf/InJvmParallelBench.java \
  --counts 1 --preconsumeHeapMb 0                           # then rerun with --preconsumeHeapMb 300

# Item 17 — N parallel instances, CONTAINER shape (N JVMs); --cpuset shows cgroup sizing:
python3 scripts/perf/parallel_instances.py --image mockserver/mockserver:<tag> \
  --counts 1,4,8,16,32 --settle 6 [--cpuset 2] --out container-result.json

# Item 22 — suite-level decomposition (per-method / per-class, in ONE JVM); start AND stop timed:
java -Xmx2g -cp <jar-with-dependencies> scripts/perf/InJvmSuiteBench.java \
  --seq 16 --conc 16 --projectClasses 100 --projectMethods 10          # default profile
java -Xmx2g -cp <jar-with-dependencies> scripts/perf/InJvmSuiteBench.java \
  --seq 16 --conc 16 --devMode true --label suite-dev                  # control arm
# NOTE: run on a QUIET machine — start/stop are single-digit ms, so background CPU contention
# dominates the signal. The LEAK CHECK line must read "MockServer-owned live threads ... : 0".
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

## Item 17 — N parallel instances, and the store-sizing freeze

`InJvmParallelBench.java` and `parallel_instances.py` measure the two shapes of the "lots of
parallel tests on a laptop" profile. The measured findings (14-core laptop, 2026-09-17) are
recorded in full under item 17 of
[docs/plans/performance-programme.md](../../docs/plans/performance-programme.md). Three that
matter to anyone reading a `flaky only on CI` report:

- **Thread counts are far below the naive arithmetic.** Pools start lazily — the action-handler
  (`Scheduler`) pool holds **zero** threads until a delayed/callback response schedules onto it
  (positive control: 0 → 14 after 60 concurrent delayed responses). 32 in-JVM instances hold ~222
  live threads under light load, not the ~480 ceiling.
- **`maxLogEntries`/`maxExpectations` are FROZEN at first read for the whole JVM**
  (`ConfigurationProperties.readPropertyHierarchically` caches the first computed default). Every
  instance in a JVM shares one capacity, set by the heap at the first store's construction — so a
  heavy fixture before the first MockServer start silently shrinks the store for *every* instance.
  Reproduce the swing with `--preconsumeHeapMb` (e.g. 100000 → 45957 at `-Xmx1g` for 0 vs 300 MB
  pre-consumed); the harness's per-instance `maxLogPre`/`maxLogPost` prove the freeze itself.
  `--devMode true` replaces the derivation with a fixed 1000/1000 and removes the lottery; measure
  both arms to see it.
- **The container shape pays N JVM baselines** (process RSS ~175 MiB each, flat with N) where the
  in-JVM shape shares one, so container aggregate RSS grows ~N× faster (2806 MiB at N=16, 5330 MiB
  at N=32). No clean cross-shape memory *multiplier* is claimed: container process RSS and the
  trustworthy in-JVM figure (heap-used, 52→117 MB) are different quantities, and in-JVM `ps` RSS is
  a noisy post-GC point read (non-monotonic 460→394 MiB). Thread counts are like-for-like: 448 vs
  222 at N=32 (~2×). Container `availableProcessors()` is cgroup-aware (`--cpuset-cpus=0,1` → the
  JVM reports 2), so each sizes its pools off its own limit — but `--cpuset N` pins every container
  to the same cores, so it is a sizing demo, not an isolation model.

Both harnesses write standalone `--out` JSON and do NOT emit into the daily perf result. See the
item-17 note in the plan for the notify-only `laptop.*` budget leaves a future daily wiring needs.
