# Performance Programme

**The central question is settled.** The healthy serving ceiling is **60,000 rps offered
(57,149 served)** at 0.179 ms median; peak **59,905** (build 464, 2026-09-27, commit
`efdc5227b`, shipped ZGC default). Per repo convention, when the remaining items below are
closed this file is deleted in the same commit — it is not an archive.

Two open items remain: the unattributed latency tail (§1, steady-state A/B runs in progress)
and the tuning candidates that still need a rig measurement or a decision (§2). See
[What remains](#what-remains).

Published figures and rig measurement gates are in
[docs/code/performance-measurement.md](../code/performance-measurement.md). The GC-default
decision (ZGC shipped as `ENV JAVA_TOOL_OPTIONS="-XX:+UseZGC"`) is in
[docs/infrastructure/docker.md](../infrastructure/docker.md) and
[docs/code/startup-performance.md](../code/startup-performance.md).

## What remains

| # | Item | Blocked on |
|---|---|---|
| 1 | The unattributed latency tail | the steady-state bridge vs host A/B runs (rig support landed in `2e02c7318`) |
| 2 | Tuning candidates | rig A/B runs for the event-loop count and three JVM/Netty flags; two small code items; one gate decision |

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

## §1 — The unattributed latency tail (OPEN)

**Summary:** the k6 p99 tail (10–32 ms at 24k–52k offered) is predominantly a rig and
measurement artefact, not MockServer server time. One run is needed to confirm and close.

### Findings from build 464

k6 phase breakdown at each rate:

| Offered rps | k6 p99 | k6 p95 | Phase carrying the tail |
|---|---|---|---|
| 24k–36k | 10–16 ms | < 1 ms | `waiting` (TTFB) |
| 44k+ | 28–32 ms | rising | `waiting`; `sending` p99 3–6 ms; blocked/connecting ≈ 0 |

MockServer's own `request_duration_millis` histogram (diag-samples.csv) shows at most 0.017%
of requests over 5 ms at any rung and 0.006% over 10 ms across the whole run.

ZGC pause max: 0.032 ms. SUT CPU peaks at 480% of 600% pin. k6 stays at or below 74% of its
pin. TCP_NODELAY is not the cause — Netty enables it by default on accepted and client channels;
it is not set explicitly, but the default is on.

At 24k, all stalls fall in the first of six wall-time buckets of the rung with the VU pool 0.4%
occupied: this is a rung-onset transient over the docker bridge. At 52k and above, stalls are
uniform across buckets as the VU pool fills near saturation.

**Conclusion:** the tail is predominantly a rig/measurement artefact — the k6 per-rung onset
over the docker bridge, and VU pool pressure near the knee — not MockServer server time.

### Next step

Rig support landed in `2e02c7318` (`PERF_STEADY_RATE`, `PERF_NETWORK_MODE=host`, both
non-baseline-eligible). Run a steady-state 24k pass on the bridge and on host networking and
compare the client `waiting` p99 with the server histogram for the same window. If the ~10 ms
p99 disappears with no ramp or on host networking, close the item as measurement, exclude
rung-onset samples from the published tail statistic, and consider moving k6 off-box.

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
| 17 | The level-aware event-log byte-budget divisor predates the body-release fixes, so it is now conservative | See `docs/code/memory-management.md` | Re-derive from a fresh `jmap -histo:live` before retightening |

### Gate decision pending

- The daily microbench (`perf-test-microbench.sh`) pins `detailedMatchFailures=false`, the opt-out,
  so its tracked `time_per_op` baseline describes a non-default arm. Moving the pin to `true` resets
  that baseline, so it needs a deliberate decision.

### Checked and not worth pursuing

io_uring (blocked by Docker's default seccomp, would silently fall back); `FlushConsolidationHandler` (HTTP/1.1 flushes once per response — already consolidated); explicit `TCP_NODELAY`/`SO_RCVBUF`/`SO_SNDBUF`; `AlwaysPreTouch` (slower start); THP by default (needs host `shmem_enabled`); `SoftMaxHeapSize`; two non-secure UUIDs per request (externally visible ids).
