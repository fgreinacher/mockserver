# Performance Programme

**The central question is settled.** The healthy serving ceiling is **60,000 rps offered (57,149
served)** at 0.179 ms median; peak **59,905** (build 464, 2026-09-27, commit `efdc5227b`, shipped ZGC
default; build 420's 41,000 / 43,671 was the starting point). Per repo convention, when
the remaining items below are closed this file is deleted in the same commit — it is not an
archive. The parts that deserve to outlive it have been moved to permanent documentation: see
[What was moved](#what-was-moved).

## Closed

All programme work has landed; the commits are in git history and in `changelog.md`, so the
table that used to list them has been removed rather than maintained in two places.

## What remains

One item is open, and it is **not blocked on code** — it is waiting on a run that is held until
after the next release.

| # | Item | Blocked on |
|---|---|---|
| 1 | The unattributed latency tail | a run, held until after the next release |
| 2 | Low-rate handler latency spike | **closed — measurement artefact** (see §2) |
| 4 | `jvm_memory_allocated_bytes` missing from Docker images | **resolved** — confirmed by the snapshot-image smoke check (java build 2584 onward) |
| 5 | Large-heap event-log profile | **done** — run 458; default cap raised to 250,000 (`15825ae55`) |

### Pipeline wall-clock

| Change | State |
|---|---|
| Snapshot deploy and artifact-only steps pass `-DskipITs` (deploy re-ran 2,278 netty ITs, ~23 min) | landed `ef283860e`; confirmed — java build 2587's deploy took 10 min |
| Container integration tests fed by a test-free jar step, alongside the build | landed `ef283860e`; confirmed — build 2587 ran them inside the build window (build 26 min, container tests 13 min alongside) |
| `docker-build-verify.sh` artifact install passes `-DskipITs` | landed `b516cfe89`; the step is not in any pipeline today |
| Allocation gate moved into the build's parallel group (~3 min off the java critical path) | landed `b516cfe89`; confirmed — build 2587 ran it alongside the build (2 min) |
| UI build + Playwright e2e alongside lint/unit tests (~5 min off the UI pipeline; e2e no longer skipped on red unit tests) | landed `b516cfe89`; confirmed — UI builds 1320–1323 ran the e2e job alongside lint/unit tests |

Considered and not pursued, by decision: splitting the netty integration tests across parallel JVMs (fixed ports and shared static config make it high-risk), and reusing `:maven: build` output for the deploy (steps do not share a filesystem).

**Cross-reference:** the allocation and occupancy work that superseded this plan is complete. The GC-default decision (ZGC shipped as `ENV JAVA_TOOL_OPTIONS="-XX:+UseZGC"`, `-aot` kept on G1) is documented in [docs/infrastructure/docker.md](../infrastructure/docker.md) and [docs/code/startup-performance.md](../code/startup-performance.md). Published figures and rig measurement gates are in [docs/code/performance-measurement.md](../code/performance-measurement.md). Items 1 and 2 here are the two latency questions that programme did not answer.

### 1. The unattributed latency tail — OPEN (held until after the next release)

At the 41,000 rps healthy ceiling, client-observed p95 is **74 ms** while MockServer's own p99
across **6,026,984 requests** in the run stayed under 5 ms. The gap is outside the server
handler — confirmed by the server-side request-duration histogram captured in `aeaf0c74a`.

What is unresolved: whether the tail belongs to MockServer's Netty event-loop queue during GC
pauses on the SUT, or to the load generator, which shares the same physical machine as the SUT.

Two changes in flight to settle it:
- Capture k6's `http_req_*` phase breakdown (connecting, waiting, receiving, sending) to locate
  where the client-observed time is spent.
- Assess moving k6 to a separate box, so the load generator no longer shares cores with the SUT
  even at rest.

The maintainer has asked for this to remain open until after the next release.

### 2. Low-rate handler latency spike — CLOSED (measurement artefact)

The 1,720–1,990 s window is the rig's **forward.js** (100 → 1,500 rps, every request forwarded to the
single-core upstream) and **proxy.js** (absolute-URI and CONNECT arms, ~200 rps) arms, not direct
serving at low rate. The server-side `request_duration_millis` histogram includes the upstream hop, and
the forward path legitimately costs ~5 ms (build 420: `forward_http` p95 4.8 ms, `forward_connect_proxy`
p50 3.7 ms), which fills the >5 ms bucket at 200–395 per interval. Direct serving at a similar low
rate is clean: growth.js, 280,764 requests at ~820 rps, 13 over 5 ms (0.005%). The pattern reproduces
in builds 445, 446, 448, 449 and 450 under both G1 and ZGC, `forward_guard` shows zero errors, and GC
deltas do not track the spikes. When reading the histogram, separate forward-arm intervals from
direct-serving ones; the `behaviours.forward_*` percentiles already do. Adjacent, not item 2: the
regression arms' large-body and template behaviours fill the >5 ms bucket much more on the JDK 25
builds (300–785 per interval vs 20–100 on 420).

### 3. Canceled-child reporting — REMOVED (decision 2026-09-27)

Dropped rather than left open: it could only be settled by a child build that is created and then reaches
`canceled` or `not_run`, which has never been observed, and provoking it on purpose would mean cancelling
live CI builds on shared pipelines. The Dependabot trigger-refusal case (a child never created) was fixed
in `ea2336131`. Re-open only if a real canceled child is seen.

### 4. Shading defect: `jvm_memory_allocated_bytes` — RESOLVED (confirmed on the snapshot image)

**Real root cause: the Docker images' jlink runtime lacked `jdk.management`.** The shade fix was
necessary but not sufficient. `JvmMetricsCollector.totalAllocatedBytes()` needs
`com.sun.management.ThreadMXBean`, which lives in the `jdk.management` module. The trimmed runtime
baked into every image (`docker/Dockerfile`, `docker/local/Dockerfile`, `docker/aot/Dockerfile`) was
`jlink`ed with `--add-modules java.se,…` — and `java.se` aggregates only `java.*` modules, never
`jdk.management`. So `com.sun.management.ThreadMXBean` was absent from the runtime, the `instanceof`
check failed, the collector returned `-1`, and the metric line was suppressed from the Prometheus
scrape with no other symptom (every other `jvm_*` metric present, the server otherwise healthy).

Two independent causes had to be fixed:
- `63a7a9833` stopped `mockserver/pom.xml` relocating `com.sun.management` in the shaded jar (guarded
  by `assert-jdk-com-sun-not-relocated.sh`) — required so the reference resolves to the real JDK class.
- This commit adds `jdk.management` to the `jlink --add-modules` in all three Dockerfiles **and** in
  `scripts/build-binary-bundle.sh` (which the Dockerfile comments cite as the canonical module set, and
  which produces the JVM-less release bundles that had the same dead metric). A build-time guard in
  `.buildkite/scripts/steps/docker-build-verify.sh` now starts the image with metrics enabled and fails
  the build if `jvm_memory_allocated_bytes` is missing from the scrape.

Verified locally: a `docker/Dockerfile` image built from this commit emits the metric; removing
`jdk.management` from the module list makes both the metric and the new guard go red. Confirmed through the
pipeline: the snapshot-image smoke check (java build 2584 onward) asserts the metric is present.

### 5. Large-heap event-log profile — DONE (cap raised to 250,000)

**First run, build 456 (2026-09-27), 8 GiB heap:** invalid by its own gate — the rig always passes a fixed 256 MiB `maxEventLogSizeInBytes` unless `PERF_MAX_EVENT_LOG_BYTES` is set, so the byte budget did not scale with the heap (the check `event_log_byte_budget_scaled_with_heap` failed, correctly). It still shows the key fact: 100,000 entries retained at a mean of 2,684 bytes = ~268 MB, so the **count cap binds** (100,000 is reached at 0.76 GiB of heap and never grows); with a heap-scaled 1 GiB budget only ~25% of it would be used. Corrected run 458 sets `PERF_MAX_EVENT_LOG_BYTES=1073741824` (heap/8).

**Run 458 (8 GiB heap, 1 GiB byte budget):** the count cap bound first — 100,000 entries at 100% count utilisation, holding ~527 MB (mean 5,268 B/entry), i.e. 49% of the byte budget and ~6% of the heap. **Decision (maintainer, 2026-09-27):** raise the fixed cap from 100,000 to 250,000 and keep `min(heapKB/8, cap)`; the byte budget stays the memory guard. The cap is now reached at ~1.9 GiB of heap, and the 2 GB perf SUT resolves ~155,000 entries instead of 100,000 (the rig's per-core arithmetic now derives this from `PERF_PERCORE_MEMORY`).

The instrument is in `perf-test-run.sh`: a large-heap profile plus a fail-closed proportionality
assertion. It exists to answer one question — when the heap grows, does the event log actually
retain more, or only gain headroom?

**How to run it.** A large-heap run is expressed through levers that already existed —
`PERF_SERVER_MEMORY` (container memory and hence the MaxRAMPercentage heap), `PERF_SERVER_JAVA_OPTS`
(carry the GC here, e.g. `-XX:+UseZGC`, and an explicit `-Xmx`/`-XX:MaxRAMPercentage` if wanted),
and `PERF_MAX_EVENT_LOG_BYTES` (the log byte budget) — plus the new `PERF_LARGE_HEAP_PROFILE=true`,
which turns the assertion into a build gate. Any of these now flips `config_profile` to `tuned`
(previously only `PERF_SO_BACKLOG` did), so such a run is recorded but never persisted to the
default-configuration baseline.

**What it records.** Every run now emits an `event_log_scaling` block in `perf-result.json` carrying
the EFFECTIVE, gauge-resolved `maxLogEntries` and `maxEventLogSizeInBytes`, the heap ceiling the JVM
actually saw, the peak observed retained bytes/entries, the mean entry size, and which bound bound
(`count` / `bytes` / `neither`).

**How it fails closed** (only on a `PERF_LARGE_HEAP_PROFILE` run — a normal run records the block
and asserts nothing): the run REDS if the SUT exposed no event-log gauges (cannot verify), if the
resolved byte budget is below the heap-derived floor `(heapKB/divisor)*1024` (the budget did not
scale with the heap), or if neither bound was approached (utilisation below `PERF_EVENT_LOG_APPROACH_RATIO`,
default 0.90, on both bounds with no evictions — the workload never filled the log, so the run is
not evidence of proportionality).

**The two bounds scale differently — this is the point.** Both derive from the heap ceiling, but:

| heap | `maxLogEntries` | byte budget @ERROR (`heap/8`) | count cap binds when mean entry < |
|---|---|---|---|
| 1.2 GiB | ~155,000 | 154 MiB | 1.0 KiB |
| 8 GiB | 250,000 (capped) | 1024 MiB | 4.2 KiB |

`maxLogEntries = min(heapKB/8, 250000)` reaches the 250,000 cap at **~1.9 GiB** of heap (the cap was
100,000, reached at 0.76 GiB, until run 458) and never grows again; `maxEventLogSizeInBytes = (heapKB/divisor)*1024` (divisor 8 at WARN/ERROR/OFF, 12 at
INFO/DEBUG/TRACE) scales linearly with the heap without limit. So for the perf workload's small
bodies the COUNT cap binds on large heaps, so beyond ~1.9 GiB a bigger heap yields no additional
retention: a large-heap run tests "same live set, much more headroom", not "bigger log". The assertion makes the
harness measure that rather than assume it.

**Cap decision — resolved.** Run 458 showed the 100,000 cap leaving ~half the 8 GiB byte budget
unused, so the maintainer chose a higher fixed cap (250,000) over an unbounded heap-derived one:
query scans (`retrieve`/`verify`) grow with entry count, not heap, so an unbounded count on a very
large heap would slow them without limit. The disruptor ring is unaffected (`min(maxLogEntries,
16384)`).

## Durable findings

These are lessons that cost effort to learn. The ones that belong in permanent documentation have
been moved there (see [What was moved](#what-was-moved)); what remains here is specific to this
programme or has no single permanent home.

**Four changes were each necessary and none sufficient for the knee certification.** The MCP
per-connection registry leak fix (`2a5999395`) let the SUT survive the full ladder instead of
dying at 38,000 rps; the raised VU ceiling let pools scale past the previous 2,048 hard cap; the
p95 headroom criterion (`bac8a96b7`) stopped idle pools from reading as client-limited; the finer
rungs at 39,000 and 41,000 located the ceiling accurately. Remove any one and the run certifies
nothing. This is what the programme spent to go from "cannot certify a knee" to
`validity.valid: true` on build 420.

**The load generator was inside the server's cores for every run before 2026-09-22.** The old
`c5.4xlarge` perf queue had eight physical cores; the run's cpusets requested thirteen. The
overlap was unavoidable regardless of how vCPUs were enumerated. Every throughput figure produced
before build 397 was measured with k6 contending for the same physical cores as the SUT. The
figures are reproducible and the regression control still detects change — but they are not
measurements of the server on six isolated cores. The queue is now `c5.12xlarge` (24 physical
cores); disjoint core assignment has been verified in build 397 and is guarded at run time by a
physical-topology check that fails the run if cpusets share a physical core.

**A 2,000-rps ladder gap cannot distinguish a noisy rung from a knee.** Documented in
[docs/code/performance-measurement.md](../code/performance-measurement.md) under `sweep.js`.

**`vus_active_max` is right-censored by the pool.** A pileup that briefly touches the ceiling
makes a rung read as client-limited even when the pool sat at 1% utilisation for 95% of the
measurement. First addressed by using `vus_active_p95` instead of the max; later refined into the
occupancy-knee discriminator below (a bare `vus_active_p95 < pool` still discarded the saturating
knee). Documented in [docs/code/performance-measurement.md](../code/performance-measurement.md)
under `sweep.js`.

**The rig-validity criteria measured the wrong thing, capping every reported figure at ~24,000
rps.** Two exclusion criteria in `derive_saturation` (`.buildkite/scripts/steps/perf-test-run.sh`)
were each answering the wrong question:

- *k6 CPU aggregated with MAX over a window that included container startup.* `docker stats
  --no-stream`'s cold read is inflated, and the per-rung CPU was the MAX sample, so a single spike
  read as sustained client saturation — build 420's 8,000 rung recorded 1,173 % CPU against a VU
  pool 1 % occupied, and the CPU series was pure noise (270 → 663 → 457 → 682 %). Fixed by gating
  on the MEAN over the steady window and dropping the sampler's first (startup) reading; the max is
  still recorded alongside (`k6_cpu_pct_max`) so the spread is visible. The mean, not a high
  percentile, because a rung's window holds only ~4 samples where a p95 ≈ the max.
- *the drop criterion could not tell a slow server from a slow client.* A dropped iteration means
  the arrival executor found no free VU. The old rule excluded any rung whose drops exceeded a 1 %
  fraction, judged against a bare `vus_active_p95 < pool` — true even at 95 % occupancy — so it
  discarded the exact saturating knee it existed to find (build 420's 48,000 rung: p95 3,646 / pool
  3,840, 9 % drops, excluded). Fixed by making the discriminator an occupancy RATIO
  (`vus_active_p95 / pool`): at or above an 0.80 knee the pool is the binding constraint (VUs
  blocked on server responses → server-limited → KEEP and label the knee); below it, drops over
  tolerance are a client-side scheduling stall → EXCLUDE. 0.80 sits in the widest gap of the
  observed occupancy ladder (a pinned rung reads ~0.86–0.95; a client-limited idle pool ~0.01–0.02).
  When `vus_active_p95`/`pool_per_rung` are absent (older artifacts) the rule stays STRICT
  zero-drop — never lenient on drops it cannot corroborate.

Replaying builds 420 and 426 through the new logic lifts rig-valid rungs from 2 to 8–12 and the
rig-valid peak from ~23,900 to between 40,886 (worst case — the fixtures carry only the recorded
CPU max, replayed as mean=max) and 43,672 (with the CPU spikes averaged out, which is what the mean
fix does). Only the 8,000 rung (idle pool, drops → client stall) and the 28,000 rung (73–79 %
occupancy, a blip just over tolerance) stay excluded; everything pool-pinned from 32,000 up is
kept. This reconciles the gate with the already-published all-rungs headline (43,671): the headline
was always the true server peak; it was this gate that was capping at ~24,000.

**k6's cpuset was 12 of 48 vCPUs.** Widened to `11-23` (thirteen physical cores) — since superseded
by `7-23` (seventeen), because thirteen proved too few and the ten-vCPU arm the reservation protected
cannot run on this box at all (see [docs/code/performance-measurement.md](../code/performance-measurement.md)). The reasoning at the
time, now superseded, was: it starts at 11, not 7, so a planned second arm with the server on ten
vCPUs (0-9) plus upstream (10) needs no k6 move — one k6 cpuset shares no physical core with the server or upstream in either the six- or the
ten-vCPU arm. The sibling pairing is NOT assumed: `lib/perf-cpu-topology.sh` resolves it from sysfs
at run time, the disjointness guard fails the run closed on overlap, and the resolved
physical-core counts (siblings counted once) are now recorded in `perf-result.json`
(`config.cpusets.physical_cores`, `agent.server_physical_cores`/`k6_physical_cores`) so a published
"on N cores" figure can cite real cores, not vCPUs.

## What was moved

| Content | Destination |
|---|---|
| What each harness measures, which CI executes vs lints, that `ForwardPathBenchmark` measures the load generator not the server, `throughput_rps` delivery-ratio semantics, `vus_active_max` right-censoring and the `vus_active_p95` criterion, the ladder-granularity requirement, certified knee figures | [docs/code/performance-measurement.md](../code/performance-measurement.md) |
| Dating and provenance rule; a populated field is not a correct one | [docs/code/startup-performance.md](../code/startup-performance.md) |
| k6 README corrections (`forward.js` was described as running when it never did) | `mockserver-performance-test/k6/README.md` |
| Hazard-class table, evidence standard, and the `rig_valid_peak_achieved_rps` wrong-subject lesson | [docs/code/optimisation-safety.md](../code/optimisation-safety.md) |

## Known consequence: the log-guard sweep cost diff coverage

The diff-coverage gate reported **47.7% against a 70% threshold** on `b0430b627`
(431 uncovered changed lines). The gate is soft-fail, so it notified rather than
blocked, and the number is real rather than spurious.

**Mechanism.** Before the sweep, `logEvent(new LogEntry()...)` was *executed* on
every path and discarded inside `logEvent` when the level was disabled — so the
lines counted as covered. After it, the guard is false at the suite's default
`ERROR` level and the body is never reached. The uncovered lines are precisely the
guarded log bodies (`MTLSAuthenticationHandler:78-102,152-157`,
`StreamingAwareHttpObjectAggregator:234-237`, and so on across the sweep).

**The real cost, stated honestly.** This is mostly a measurement artefact — the
same behaviour is tested, the lines just are not reached. But not entirely: a
latent fault inside one of those statements (a bad `setArguments` index, an NPE in
a message expression) used to surface as a test failure because the statement ran.
At `ERROR` it no longer does. The protection was weak — no test asserted those
messages, so it only ever caught an exception, not a wrong message — but it was
not nothing.

**Why the ledger is the wrong instrument here.** `.buildkite/diff-coverage-ledger.json`
has a staleness ratchet: an entry whose line later becomes covered fails the build.
These lines become covered the moment any test runs below `INFO`, so bulk-recording
431 of them would both hide the trade and create a latent build break. Recording a
number instead of raising coverage is the antipattern the ledger exists to prevent.

**Options, none taken yet — this is a judgement call:**
1. Accept it. The gate is notify-only by design, and this is a deliberate trade:
   allocation on the hot path against execution coverage of log statements.
2. Run a subset of the suite at `DEBUG`/`TRACE` so the guarded bodies execute.
   Restores the incidental protection; costs suite time and log noise.
3. Add targeted tests per site. ~180 sites of low-value assertions — rejected.

## The ladder needs finer rungs around the knee

Build 434 (six vCPUs, 4 GB, generational ZGC, JDK 25) measured this ladder:

| offered | achieved | p50 ms | p95 ms |
|---|---|---|---|
| 32,000 | 31,745 | 0.119 | 24.6 |
| 48,000 | 43,686 | 0.369 | 82.2 |
| 64,000 | 48,698 | **36.9** | 78.4 |
| 128,000 | 50,311 | 38.2 | 74.4 |

The median jumps from **0.369 ms to 36.9 ms** between the 48,000 and 64,000 rungs. The
knee is somewhere in that gap and the ladder has no resolution there, so the run cannot
say where the healthy ceiling actually is — only that it is above 43,686 achieved and
below 48,698.

`rig_valid_peak_achieved_rps` reports **50,311**, the top rung. That is not a publishable
ceiling: throughput is flat near 50,000 from 64,000 offered upward while the median sits
near 40 ms. The server is queuing, not serving faster. `saturation_rps` reports 32,000,
which is conservative — 48,000 offered still returns a sub-millisecond median.

**Two separate limits are in play above 64,000** and they must not be conflated:
`vus_concurrent_overall_max` hit **4,096** against the 4,096 ceiling from that rung up, so
those rungs are ALSO client-constrained. The occupancy-ratio criterion keeps them because
the pool is pinned, and it cannot distinguish "pinned because the server is slow" from
"pinned because there are not enough VUs". Here p50 shows it is genuinely the server —
but that has to be read off the latency, not inferred from the criterion.

**Next run:** a fine ladder across the knee for each core count (for six vCPUs,
44,000 → 64,000 in 4,000 steps), with the VU ceiling raised so the pool is not a
co-constraint. Publish the highest rung holding a sub-millisecond median, not the highest
achieved throughput.

## What the GC logs actually showed

Two six-core runs differing only in heap size and event-log budget, both on JDK 25 with
generational ZGC:

| | 2.4 GiB heap, 256 MiB budget | 7.2 GiB heap, 1 GiB budget |
|---|---|---|
| collections | 646 | 163 |
| total cycle time | 175.2 s | 54.7 s |
| cycle p50 / p95 / max | 66 / 1047 / 2058 ms | 181 / 952 / 1272 ms |
| peak live set after GC | 1,658 M | 2,204 M |
| Major "Proactive" | 20 | 28 |

**The live set tracks the event-log budget, not the workload.** A 1 GiB budget retained
2,204 M; 256 MiB retained 1,658 M. The heap occupancy that appeared to justify a large
heap was mostly the budget the harness had been told to use — the event log is the
dominant retained structure, as the consumer docs now say.

**The smaller heap collects four times as often with shorter cycles, and is no slower.**
646 collections at a 66 ms median against 163 at 181 ms, yet the 2.4 GiB run matched or
beat the 7.2 GiB run on throughput and latency at every rung. On generational ZGC the
extra heap bought fewer, longer cycles — not more speed. "Proactive" collections, which
are ZGC collecting because it has room rather than because it must, fell from 28 to 20.

**These are CYCLE times, not pause times.** `-Xlog:gc` does not emit stop-the-world
pauses, so a 1,047 ms p95 cycle is concurrent work, consistent with a request p95 near
74 ms rather than near 1,000 ms. Turning "the tail improved" into a pause number needs
`-Xlog:gc+phases`, or the allocation-profile step (JFR `settings=profile` plus periodic
`GC.class_histogram`), which is the only thing here that can also name the allocation
sources.

## The rig is out of physical cores, and that caps what can be measured

The perf box is a `c5.12xlarge`: 48 logical CPUs but **24 physical cores**, with
hyperthread siblings at `N` and `N+24` (read off the cpuset guard's own topology dump,
not assumed). Every core is already allocated:

| arm | server | upstream | k6 max | total |
|---|---|---|---|---|
| six vCPU | 6 | 1 | **17** | 24 |
| ten vCPU | 10 | 1 | **13** | 24 |

k6 had 13 (`11-23`) throughout. At the six-core knee it drew a **mean 1294% of its 1300%
pin**, so the 56,000 rung — the most interesting one — was excluded as client-limited.
Widening it to `11-23,30-47` does NOT help and the guard correctly refused the run:
`30-47` are the hyperthread twins of `6-23`, so k6 would have been overlapping itself and
colliding with upstream on physical core 6. More logical CPUs, no more compute.

**Consequence, and it is structural rather than a tuning problem:**

- **Six cores can be measured.** k6 can have `7-23` = 17 physical cores, its maximum here.
- **Ten cores cannot.** Server 0-9 plus upstream 10 leaves exactly `11-23` = 13, which is
  what build 436 already used and which demonstrably saturates at the knee. No cpuset
  arrangement changes the arithmetic.

A diagnostic worth remembering: k6's CPU is **non-monotonic in offered load** — 1294% at
56,000 but ~851% at 64,000 and 72,000. That is not noise. Past the knee the server is slow,
so k6's VUs block on I/O rather than working; the client works hardest at the LAST healthy
rung. Peak client CPU therefore locates the knee rather than indicating a bad measurement.

This makes "run k6 on a separate box from the SUT" the blocking prerequisite for any
ten-core figure, not an improvement to schedule later.

## JFR cannot attribute a retained heap under ZGC

**Both of JFR's retention instruments emit nothing under ZGC.** That is why the
deep-diagnostics runs up to build 439 could say what *allocated* but never what the
heap *held* — and why the ~1.8 GB of build 439's 2,336 MiB peak that the event-log
gauges do not account for stayed unattributed.

Verified directly on JDK 25 (`eclipse-temurin:25-jdk`), same program, same JFR
settings, only the collector changed:

| Instrument | `jfr view` | `-XX:+UseG1GC` | `-XX:+UseZGC` |
|---|---|---|---|
| `jdk.ObjectCount` | `object-statistics` | populated | **empty** |
| `jdk.OldObjectSample` | `memory-leaks-by-class` / `-by-site` | populated | **empty** |

Explicitly enabling them (`jdk.ObjectCount#enabled=true`,
`jdk.OldObjectSample#cutoff=0 ns`) changes nothing under ZGC — the JVM accepts the
options silently and emits no events. **Do not add these views to the allocation
annotation**: they would run, pass, and report nothing, while looking like coverage.

The SUT runs generational ZGC, so the only live-set instrument left is
`jcmd GC.class_histogram`, which does work under ZGC. The harness now samples it
during every deep run (`live_heap_histo_sampler` in `perf-test-run.sh`), writing
`sut/live-heap-histogram.txt` into the uploaded diagnostics bundle, and the
allocation-profile step reports the last sample in its annotation.

Two constraints that the implementation encodes and a future change must preserve:

- **The attach handshake requires an exact uid match.** Running the sidecar as root
  fails with `Unable to open socket file /tmp/.java_pid1`; root is not privileged for
  HotSpot attach. The uid is therefore read from the target's own `/proc/1/status`
  inside the shared PID namespace, never assumed to be 65532.
- **`GC.class_histogram` forces a full GC.** It is deep-only and must never run on a
  baselined measurement.

The tier-2 comment block in `perf-test-run.sh` previously described this periodic jcmd
sampling as though it existed. It did not; the comment was the specification, not the
implementation.
