# Performance Programme

**The central question is settled.** The healthy serving ceiling is **60,000 rps offered
(57,149 served)** at 0.179 ms median; peak **59,905** (build 464, 2026-09-27, commit
`efdc5227b`, shipped ZGC default). Per repo convention, when the remaining items below are
closed this file is deleted in the same commit — it is not an archive.

The latency tail is closed: a steady 24k run (build 473, docker bridge, no per-rung ramp)
measured client p99 0.343 ms and p99.9 1.43 ms, with no server request over 5 ms in 7.25M, so
the 10–16 ms ladder tail was a rung-onset transient in the rig, not MockServer or the bridge.
What remains is the tuning candidates that need a rig measurement or a small change (§2).

Published figures and rig measurement gates are in
[docs/code/performance-measurement.md](../code/performance-measurement.md). The GC-default
decision (ZGC shipped as `ENV JAVA_TOOL_OPTIONS="-XX:+UseZGC"`) is in
[docs/infrastructure/docker.md](../infrastructure/docker.md) and
[docs/code/startup-performance.md](../code/startup-performance.md).

## What remains

| # | Item | Blocked on |
|---|---|---|
| 2 | Tuning candidates | rig A/B runs for the event-loop count and three JVM/Netty flags; small code and rig items |

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
- **`-XX:InitialRAMPercentage=60` as an image default** — commits 60% of the container up front,
  raising idle memory for the many short-lived test containers; users with sustained load can set it.

---

## §2 — Tuning candidates (found 2026-09-28)

Source: four read-only reviews of master `a81b72ddf` plus the build 464 allocation profile.
At up to ~59.5k rps with `MOCKSERVER_LOG_LEVEL=ERROR`: 11.8 KB allocated per request, 90% on
worker loops, 10% on the event-log thread.

### Remaining candidates

Candidates 2–5 and 7–10 from the original list landed (see `changelog.md` and git history);
candidate 1 was declined (see [Decided against](#decided-against)).

| # | Candidate | What is known | Next step |
|---|---|---|---|
| 6 | Worker event-loop count fixed at 5 (`nioEventLoopThreadCount`) regardless of cores | The main rig arm has never varied it | Rig sweep 4/5/6/8/12 via `PERF_SERVER_JAVA_OPTS` (keep `-XX:+UseZGC`), control run on the same image; then consider a `max(5, cores)` default |
| 11 | Compact object headers (`-XX:+UseCompactObjectHeaders`, JDK 25+) for the ZGC JDK 25/26 images | Implemented and smoke-tested in a held branch; archive maps when trained with the flag; header 16 → 8 bytes confirmed, but a local MockServer workload showed no measurable live-set change | Rig A/B; if it ships, `container_integration_tests/docker_compose_appcds_archive_mapped` must add the flag (control-class, needs approval) |
| 12 | Netty leak detection left at its default (SIMPLE) in the images | CI runs paranoid leak detection and fails on any leak, so the production signal is marginal | Rig A/B with `-Dio.netty.leakDetection.level=disabled` |
| 13 | Two `ByteBuf` allocator families live | Confirmed: HTTP/2 child stream channels use Netty 4.2's adaptive default while everything else is pinned to `PooledByteBufAllocator` | Rig A/B with `-Dio.netty.allocator.type=pooled`, judged on RSS and throughput |
| 15 | The force-response-index header is parsed twice per served request (`RequestMatchers` and `HttpActionHandler`) | Found during unit U7 | Thread the parsed index through; small |
| 16 | The per-merge alloc gate pins `matcherType=EXACT`, whose candidate index empties the bucket, so it never exercises the per-candidate scan | Found during unit U3 (the scan saving showed only on `HEADERS_MISS`) | Add a scan-exercising arm once it has run history to derive a budget |
| 18 | The ladder's published tail percentiles include each rung's onset transient (at 24k all stalls fall in the rung's first bucket) | A steady 24k run shows p99 0.343 ms against 10.6 ms on the ladder rung | Exclude rung-onset samples from the published tail statistic, or publish the steady-state figure alongside (control-class rig change) |
| 17 | The level-aware event-log byte-budget divisor predates the body-release fixes, so it is now conservative | See `docs/code/memory-management.md` | Re-derive from a fresh `jmap -histo:live` before retightening |

### Checked and not worth pursuing

io_uring (blocked by Docker's default seccomp, would silently fall back); `FlushConsolidationHandler` (HTTP/1.1 flushes once per response — already consolidated); explicit `TCP_NODELAY`/`SO_RCVBUF`/`SO_SNDBUF`; `AlwaysPreTouch` (slower start); THP by default (needs host `shmem_enabled`); `SoftMaxHeapSize`; two non-secure UUIDs per request (externally visible ids).
