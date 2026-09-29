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
| 27 | Throughput at a range of hardware sizes | §4 |
| 28 | JFR CPU, lock and GC analysis at the ceiling | §4 |
| 29 | `vus_pool_grew` cannot detect VU pool growth | §4 |
| 30 | `rig_valid` misses a k6 client saturating below 85% of its pin | §4 |

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
| 26 | Throughput step from `30917ac40`: the rig-valid peak fell after it landed | Its per-request settle tagging cost ~10% more k6 user CPU per request (local A/B) and coincided with a drop in the rig-valid peak — on the default 13-rung ladder, runs 464–486 peaked at 59.6–59.9k and runs 491–497 at 57.0–57.3k. Runs 502–504 point at the harness, not MockServer: 504 put the build-482 image on the current harness and measured 59,805 → 58,107, which isolates the image but not the ladder (502–504 ran the 22-rung fine ladder, 482 the default 13-rung one). The fix is the VU-tag window in `sweep.js`, plus a guard that fails a run whose rungs' `measured_sample_count + settle_excluded` differs from `sample_count` | One post-merge perf run on the DEFAULT 13-rung ladder (no `K6_SWEEP_RATES` override), so it is comparable with 464–486; confirm the peak returns to ~59.5k, and only then refresh the website figures |
| 27 | Publish throughput for a range of hardware sizes, from 1 core / 512 MB up to 6 cores / 2 GB and beyond where the rig allows, so users can size a deployment | `lib/perf-percore.sh` already pins one SUT per core count (1, 2, 4, 8, 16) with a disjoint k6, but it is opt-in (`PERF_SERVING_PERCORE=true`), never published, and sets no memory limit, so the JVM sizes its heap and the event-log budget from the whole host | Add a memory limit per point (container `-m`, so heap and event-log defaults follow it), run the matrix after #26 lands (the upper points are client-limited until then), then add a table and chart to `performance.html` |
| 28 | Use the JFR profile the deep run already records to find where CPU goes at the ceiling, not just where memory is allocated | The daily deep run records `settings=profile` JFR (CPU samples, lock contention, GC and safepoint pauses, socket I/O), but `perf-test-allocprofile.sh` only reports `allocation-by-site` and `allocation-by-class`; at 57k rps the server used 250–410% of its 600% CPU, so the ceiling may be contention or a single-threaded stage rather than raw CPU | Analyse an existing deep-run `load.jfr` (hot methods, contention by site, GC and safepoint pauses, per-thread CPU); add any useful views to the step's annotation; turn findings into §2 candidates |
| 29 | `sweep.js` `vus_diagnostics.vus_pool_grew` cannot detect pool growth | Its baseline (`vus_initialized_baseline`) is the sum of every rung's pool, on the assumption that k6 initialises every staggered scenario's pool up front. k6 instead reuses VUs across rungs whose schedules do not overlap, so the initialised count is far below that sum: the published result reads 6,144 initialised against a 22,272 baseline, and a local 13-rung ladder 723 against 3,132. Growth smaller than the gap reads `false` | Compute the baseline the way k6 plans VUs (the maximum, over time, of the summed pools of rungs whose schedule plus `gracefulStop` overlap), or compare each rung's own `vus_active_max` against its pool; also correct the sweep.js header comment that says every pool is pre-initialised |
| 30 | `rig_valid` does not flag a k6 client that is saturating below 85% of its CPU pin | `derive_saturation` in `perf-test-run.sh` marks a rung client-limited only when mean k6 CPU reaches `$pin * 0.85`; in runs 502–504 k6 was already the bottleneck at 75–87% of its pin, so a harness cost change moved `rig_valid_peak` with no flag | Derive the threshold from where achieved rps stops tracking offered while the server has CPU headroom, or add a server-headroom condition; degrade-test against 502–504 (control-class change, needs approval) |

