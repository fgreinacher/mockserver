# Performance Programme

**The central question is settled.** The healthy serving ceiling is **60,000 rps offered
(57,149 served)** at 0.179 ms median; peak **59,905** (build 464, 2026-09-27, commit
`efdc5227b`, shipped ZGC default). Per repo convention, when the remaining items below are
closed this file is deleted in the same commit — it is not an archive.

The latency tail is closed: a steady 24k run (build 473, docker bridge, no per-rung ramp)
measured client p99 0.343 ms and p99.9 1.43 ms, with no server request over 5 ms in 7.25M, so
the 10–16 ms ladder tail was a rung-onset transient in the rig, not MockServer or the bridge.
The ladder now excludes each rung's first 3 s from its published percentiles.
What remains is the tuning candidates that need a rig measurement or a small change (§2).

Published figures and rig measurement gates are in
[docs/code/performance-measurement.md](../code/performance-measurement.md). The GC-default
decision (ZGC shipped as `ENV JAVA_TOOL_OPTIONS="-XX:+UseZGC"`) is in
[docs/infrastructure/docker.md](../infrastructure/docker.md) and
[docs/code/startup-performance.md](../code/startup-performance.md).

## What remains

| # | Item | Blocked on |
|---|---|---|
| 2 | Tuning candidates | item 16 below |

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

---

## §2 — Tuning candidates (found 2026-09-28)

Source: four read-only reviews of master `a81b72ddf` plus the build 464 allocation profile.
At up to ~59.5k rps with `MOCKSERVER_LOG_LEVEL=ERROR`: 11.8 KB allocated per request, 90% on
worker loops, 10% on the event-log thread.

### Remaining candidates

Candidates 2–5, 7–10, 13 and 15 from the original list landed (see `changelog.md` and git history);
candidates 1, 6, 11 and 12 were declined (see [Decided against](#decided-against)).

| # | Candidate | What is known | Next step |
|---|---|---|---|
| 16 | Graduate the per-merge alloc gate's `HEADERS_MISS` scan arm (four rows: INFO/WARN × `detailedMatchFailures` false/true) from notify-only to gating | Scan arm shipped: it runs on every Java build, notify-only, against provisional floors (`premerge_alloc.MatchingBenchmark_HEADERS_MISS_*`); see [performance-measurement.md](../code/performance-measurement.md#perf-alloc-gatesh--per-merge-allocation-floors) | Once ~10 gate runs exist, set each floor from their `jmh-alloc-gate.json` artifacts (median + 3 × 1.4826 × MAD) and add `gating: true` (control-class budget change, needs approval) |

### Checked and not worth pursuing

io_uring (blocked by Docker's default seccomp, would silently fall back); `FlushConsolidationHandler` (HTTP/1.1 flushes once per response — already consolidated); explicit `TCP_NODELAY`/`SO_RCVBUF`/`SO_SNDBUF`; `AlwaysPreTouch` (slower start); THP by default (needs host `shmem_enabled`); `SoftMaxHeapSize`; two non-secure UUIDs per request (externally visible ids).

---

## §3 — Coverage-gap candidates (found 2026-09-28)

Source: a read-only review for paths the rig never exercises, and a leak audit (12 identical
mixed-workload cycles under ZGC and G1 at `-Xmx512m` held a flat live heap; the 2 h soak in
build 340 held a flat floor). Each item is in progress in its own unit unless noted.

| # | Candidate | What is known | Next step |
|---|---|---|---|
| 18 | Disk capture (`persistRecordedRequestsToDisk`) serialises pretty, collapses whitespace with a per-entry regex (quadratic on long space runs) and flushes per line on the event-log consumer | With capture on, an 8-client proxy load at `WARN` drops thousands of log entries; none without it | Compact writer, newline-safe fallback, flush per Disruptor batch; JMH first |
| 19 | Client certificates re-parsed on every request; TLS context lookup hops to a pool and rebuilds a sorted SAN signature per handshake; SAN bookkeeping per request | mTLS is optional by default, so any client presenting a certificate pays the re-parse | Cast and memoise per connection; cached-context fast path; JMH/handshake benchmark first |
| 24 | Header-value sharing across requests on a connection (retained heap) and a `-XX:+UseStringDeduplication` rig A/B | Estimated ~0.5 KB less retained per bodiless request | Queued: A/B after build 496 |
