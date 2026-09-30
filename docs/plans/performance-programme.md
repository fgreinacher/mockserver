# Performance Programme

**The central question is settled.** The healthy serving ceiling is **60,000 rps offered
(57,149 served)** at 0.179 ms median; peak **59,905** (build 464, 2026-09-27, commit
`efdc5227b`, shipped ZGC default). Per repo convention, when the remaining items below are
closed this file is deleted in the same commit — it is not an archive.

The latency tail is closed: a steady 24k run (build 473, docker bridge, no per-rung ramp)
measured client p99 0.343 ms and p99.9 1.43 ms, with no server request over 5 ms in 7.25M, so
the 10–16 ms ladder tail was a rung-onset transient in the rig, not MockServer or the bridge.
The ladder now excludes each rung's first 3 s from its published percentiles.
Every §2 tuning candidate is closed; what remains is §4, including confirming on the rig that the
harness throughput step (item 26) is fixed.

Published figures and rig measurement gates are in
[docs/code/performance-measurement.md](../code/performance-measurement.md). The GC-default
decision (ZGC shipped as `ENV JAVA_TOOL_OPTIONS="-XX:+UseZGC"`) is in
[docs/infrastructure/docker.md](../infrastructure/docker.md) and
[docs/code/startup-performance.md](../code/startup-performance.md).

## What remains

| # | Item | Blocked on |
|---|---|---|
| 26 | Throughput step from `30917ac40` | §4 |
| 27 | Throughput at a range of hardware sizes | a manual matrix run (§4), after the #26 confirmation run |
| 28 | JFR CPU, lock and GC analysis at the ceiling | §4 |
| 31 | Remove the load-generator ceiling: N k6 processes merged in Prometheus | §4 |
| 32 | Deep run perturbed and diluted what it measured | the first daily deep run after the harness change (§4) |

## Decided against

- **Splitting the netty integration tests across parallel JVMs** — fixed ports and shared static config make this high-risk.
- **Reusing `:maven: build` output for the deploy** — pipeline steps do not share a filesystem.
- **A different Disruptor wait strategy for the event-log ring** — with 6 producers on a ring
  shaped like `MockServerEventLog`'s, `BlockingWaitStrategy` (current) sustained 10.5–11.7
  ops/µs against 4.4–5.8 for Lite, Sleeping and Yielding (`EventLogPublishWaitStrategyBenchmark`).
  The profiled publish cost is the multi-producer slot claim and field copy, not the lock;
  Sleeping/Yielding also burn idle CPU and Lite is marked experimental.
- **Flushing stdout only at the end of an event-log batch** — the console handler serves every
  logger, so startup and error output from other threads could be delayed or lost.
- **A striped counter for the graceful-shutdown in-flight count** — `LongAdder.sum()` cannot keep
  the clamp-at-zero and drain invariants; the per-request allocations around it were removed instead.
- **Virtual threads for the local callback executor** — it exists to avoid a self-deadlock on
  recursive loopback callbacks; virtual threads pin on `synchronized` on the JDK 21 clustered image,
  which would reintroduce it.
- **Changing the worker event-loop count from 5** — on the 6-core perf server, 4, 8 and 12 loops peaked at
  59,578, 59,594 and 59,484 rps against 59,805 for the default 5 (builds 485, 483, 484, 482), within
  run-to-run noise, with the same median latency at every rate.
- **Compact object headers (`-XX:+UseCompactObjectHeaders`) in the JDK 25/26 images** — build 486 peaked at
  59,603 rps with the same median latency as the default-header runs (59,484–59,805, builds 482–485), and
  its end-of-growth heap low (438 MB) sat inside their 442–598 MB spread. A local workload also showed no
  live-set change, so the saving does not justify changing six images and the AppCDS archive training.
- **Disabling Netty leak detection in the images** — build 491 vs control 495 (same host, different image
  commit: 4b18a66 vs fa667fb): peak 57,324 vs 57,029 rps and every latency/memory metric within run-to-run
  noise (heap delta confounded by a different `maxLogEntries` default in 491's image); SIMPLE sampling costs
  nothing measurable and still reports leaks in production.
- **`-XX:InitialRAMPercentage=60` as an image default** — commits 60% of the container up front,
  raising idle memory for the many short-lived test containers; users with sustained load can set it.
- **`-XX:+UseStringDeduplication` as an image default** — per-connection header sharing saves 2–3 times as much retained heap per request over HTTP/1.1, and deduplication adds almost nothing on top of it or over HTTP/2 (see [memory-management.md](../code/memory-management.md#header-sharing-across-a-connections-requests)).
- **Array-indexed Prometheus metric handles (#28 candidate C)** — resolving each `Metrics.Name`'s gauge and
  `_total` counter from an array instead of a lock plus two map lookups saved ~4 ns per increment on one thread,
  but removed the lock that was spacing out Prometheus's contended CAS on the gauge value: with six threads
  incrementing the same counter, `MetricsIncrementBenchmark` went from 0.39 to 0.72 us per increment, and
  fixed-rate CPU per request did not improve. Disabling the exemplar sampler was also measured as no gain
  (5.33 vs 5.37 ns per gauge increment).

---

## §2 — Tuning candidates (found 2026-09-28)

Source: four read-only reviews of master `a81b72ddf` plus the build 464 allocation profile.
At up to ~59.5k rps with `MOCKSERVER_LOG_LEVEL=ERROR`: 11.8 KB allocated per request, 90% on
worker loops, 10% on the event-log thread.

### Outcome

Candidates 2–5, 7–10, 13, 15 and 16 from the original list landed (see `changelog.md` and git
history); candidates 1, 6, 11 and 12 were declined (see [Decided against](#decided-against)).
No §2 candidate remains.

### Checked and not worth pursuing

io_uring (blocked by Docker's default seccomp, would silently fall back); `FlushConsolidationHandler` (HTTP/1.1 flushes once per response — already consolidated); explicit `TCP_NODELAY`/`SO_RCVBUF`/`SO_SNDBUF`; `AlwaysPreTouch` (slower start); THP by default (needs host `shmem_enabled`); `SoftMaxHeapSize`; two non-secure UUIDs per request (externally visible ids).

---

## §4 — Hardware scale and JVM profiling (added 2026-09-29)

| # | Item | What is known | Next step |
|---|---|---|---|
| 26 | Throughput step from `30917ac40`: the rig-valid peak fell after it landed | Its per-request settle tagging cost ~10% more k6 user CPU per request (local A/B) and coincided with a drop in the rig-valid peak — on the default 13-rung ladder, runs 464–486 peaked at 59.6–59.9k and runs 491–497 at 57.0–57.3k. Runs 502–504 point at the harness, not MockServer: 504 put the build-482 image on the current harness and measured 59,805 → 58,107, which isolates the image but not the ladder (502–504 ran the 22-rung fine ladder, 482 the default 13-rung one). The fix is the VU-tag window in `sweep.js`, plus a guard that fails a run whose rungs' `measured_sample_count + settle_excluded` differs from `sample_count` | One post-merge perf run on the DEFAULT 13-rung ladder (no `K6_SWEEP_RATES` override), so it is comparable with 464–486; confirm the 64k rung's `achieved_rps` returns to ~59.5k (not `rig_valid_peak_achieved_rps`, which now excludes the client-limited top rungs), and only then refresh the website figures. The website counts only rig-valid rungs, so a refresh now yields a client-limited lower bound (40–48k when re-derived for runs 451–511); `perf-website-publish.sh` holds rather than lower the committed 60k with it, so the published figure changes only once #31's multi-process k6 measures past the client knee |
| 27 | Publish throughput for a range of hardware sizes, from 1 core / 512 MB up to 6 cores / 2 GB and beyond where the rig allows, so users can size a deployment | Harness and page built: `PERF_SERVING_HW_MATRIX=true` runs `lib/perf-percore.sh` in `hw_matrix` mode over 1c/512 MB, 2c/1 GB, 2c/2 GB (control), 4c/2 GB, 6c/2 GB, 8c/2 GB, each with a container memory limit and image-default heap and every other rig container paused; records resolved heap and log bounds, OOM state and lower-bound reasons; publishes to the "Throughput by hardware size" table and chart, which shows "Not yet measured" until a run exists (see [performance-measurement.md](../code/performance-measurement.md#hardware-matrix--throughput-by-cores-and-memory-item-27)). `client_cpu_limited` still uses `lib/perf-percore.sh`'s own 85%-of-pin rule, so it misses a k6 client saturating below 85%; the server-headroom test that `derive_saturation` now applies is not ported there | After the #26 confirmation run, trigger one manual `[perf-run]` build with `PERF_SERVING_HW_MATRIX=true` and apply the publish patch |
| 28 | Use the JFR profile the deep run already records to find where CPU goes at the ceiling, not just where memory is allocated | Build 502's deep-run `load.jfr` over the 48k–64k window: the server used 275% of 600% CPU and no thread exceeded 45% of a core, so the SUT was not the limit (k6 used ~228 us CPU per request against the server's ~55 us). Candidates found: A, event-log publish wake cost (~22% of worker-loop samples in `RingBuffer.tryPublishEvent`); B, per-request configuration reads (~3.3%); C, Prometheus name lookup per increment (~2–4%); D, repeated channel attribute lookups (~2.9%); E, native epoll against NIO. B (five per-request settings memoised against the configuration generation) and D (one attribute per request in `PreserveHeadersNettyRemoves`, single lookups elsewhere) landed: in JMH B saves ~7–9 ns across the five settings, and fixed-rate 20k rps CPU per request did not move measurably. C was declined (see [Decided against](#decided-against)) | A and E: prove A's mechanism (native frames or `jdk.CPUTimeSample`) and benchmark a wait strategy with consumer think-time; A/B E on the rig; add the useful JFR views to the step's annotation |
| 31 | Remove the load-generator ceiling from the published throughput measurement | At the ~57k plateau on 6 pinned cores (build 502 JFR) the SUT used ~275% of 600% CPU and ~55 µs CPU per request, while the single k6 used ~228 µs per request and 62–88% of its 17-CPU pin, so the ceiling is the client. `mockserver-performance-test/scripts/rw-multi-k6-sweep.sh` (opt-in, `PERF_SERVING_RW_MULTIK6=true`) now offers each rung from N independent k6 processes started at one instant, merges their native histograms in a pinned Prometheus (true merged percentiles, summed counts), and fails closed on per-process count accounting, start skew, window and a same-request cross-check against the published summary mode; see [performance-measurement.md](../code/performance-measurement.md#rw-multi-k6-sweepsh--the-knee-curve-from-n-merged-k6-processes-opt-in-item-31). Locally the same-request cross-check agreed within 3.5% at p50–p99 and all three degrade tests (a killed process, a paused Prometheus at the end, a Prometheus stall across a settle boundary) marked the run invalid | One rig trial with `PERF_SERVING_RW_MULTIK6=true PERF_INFO_ARM=false PERF_COVERAGE=false`. The pass criterion is `valid` with `cross_check.same_requests.equivalent` true; `cross_check.cross_run` is report-only (two separate runs), read against the N=1 run-to-run spread as its noise floor. Then check per-process CPU headroom (`per_process[].per_rung`) and whether the healthy ceiling rises above ~60k. Only then propose switching the published sweep, as a separate approved change |
| 32 | The deep run's own instrumentation perturbed and diluted what it measured (#28's harness follow-up) | Build 502: the live-heap histogram's 0.4–0.5 s stop-the-world `GC_HeapInspection` landed in 10 of 22 rungs (9 after the settle window), and those 9 were exactly the rungs with p99.9 of 90–480 ms; the recording spanned the whole run, so ceiling CPU was averaged with idle rungs; `jfr` was JDK 21 against a JDK 25 SUT. Built: histogram only during growth.js with a daily placement check, `sut/ceiling.jfr` cut to the knee-to-top rungs, JDK 25 `jfr`, ceiling views and per-thread "% of one core", `JavaMonitorEnter` at 1 ms and `jdk.CPUTimeSample`, and a daily per-rung k6-vs-server over-5 ms table (see [performance-measurement.md](../code/performance-measurement.md#allocation-profile-step)) | Confirm on the first daily deep run that the placement line reads "none inside a sweep rung", `ceiling.jfr` is present and valid, and `jdk.CPUTimeSample` events are recorded on the rig; then close. Follow-up: the server column times only the request handler, so an accept-to-flush (or decode-to-flush) server timer would let the table tell event-loop queueing from the rig |

