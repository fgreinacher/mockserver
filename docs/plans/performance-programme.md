# Performance Programme

**The central question is settled.** The healthy serving ceiling is **60,000 rps offered
(57,149 served)** at 0.179 ms median; peak **59,905** (build 464, 2026-09-27, commit
`efdc5227b`, shipped ZGC default). Per repo convention, when the remaining items below are
closed this file is deleted in the same commit — it is not an archive.

Two open items remain: the unattributed latency tail (§1) and a ranked list of tuning
candidates identified from the build 464 allocation profile (§2). See [What remains](#what-remains).

Published figures and rig measurement gates are in
[docs/code/performance-measurement.md](../code/performance-measurement.md). The GC-default
decision (ZGC shipped as `ENV JAVA_TOOL_OPTIONS="-XX:+UseZGC"`) is in
[docs/infrastructure/docker.md](../infrastructure/docker.md) and
[docs/code/startup-performance.md](../code/startup-performance.md).

## What remains

| # | Item | Blocked on |
|---|---|---|
| 1 | The unattributed latency tail | a steady-state run (control-class rig change, gated approval) |
| 2 | Tuning candidates | individual investigations and prototypes per candidate |

## Decided against

- **Splitting the netty integration tests across parallel JVMs** — fixed ports and shared static config make this high-risk.
- **Reusing `:maven: build` output for the deploy** — pipeline steps do not share a filesystem.

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

### Next step (gated approval — control-class rig change)

A steady-state 24k run with no per-rung ramp, comparing bridge versus `--network host`, and
comparing the client `waiting` p99 with the server histogram. If the ~10 ms p99 disappears
on host networking or with no ramp, close the item as measurement, exclude rung-onset samples
from the published tail statistic, and consider moving k6 off-box.

---

## §2 — Tuning candidates (found 2026-09-28)

Source: four read-only reviews of master `a81b72ddf` plus the build 464 allocation profile.
At up to ~59.5k rps with `MOCKSERVER_LOG_LEVEL=ERROR`: 11.8 KB allocated per request, 90% on
worker loops, 10% on the event-log thread.

### Ranked candidates

| # | Candidate | Evidence | Expected gain | Risk / hazard class | Next step |
|---|---|---|---|---|---|
| 1 | Event-log ring publish contention (`MockServerEventLog` uses MULTI producers + `BlockingWaitStrategy`, which takes a shared lock and `notifyAll` on every publish) | `RingBuffer.tryPublishEvent` top frame in 15% of CPU samples; `MockServerEventLog.add` 22.8% CPU inclusive; found independently by two reviews | Potentially the largest CPU win, unproven | Hazard 5 (lost wakeup; verify relies on consumer progress and FIFO) | Confirm with async-profiler `-e lock` or a 6-producer JMH, then A/B `LiteBlockingWaitStrategy` or `SleepingWaitStrategy` |
| 2 | `LogEntry.setArguments` builds a Java stream per call, 3× per entry, 2 entries per request | 14.5% of allocation (~1.7 KB/req); 5.5% of CPU | ~1.2–1.7 KB/req and ~3–5% CPU | None for a plain loop; hazard 1 only if clone/translateTo share the array | Implement plus the alloc gate |
| 3 | Detailed `MatchDifference` recorded for every non-matching candidate by default (`detailedMatchFailures=true`), even below INFO where nothing in the scan loop reads it | ~12.5% of allocation (~1.5 KB/req) with 3 non-matching candidates; grows with expectation count | Shrinks with expectation count | Needs an audit that no reader exists below INFO | Gate on INFO |
| 4 | `Expectation.getAllActions` allocates a list on every `getAction` (2 per request, 3–4 more with metrics on), and every call writes the expectation id into the shared Action | 6.2% of allocation (~0.7 KB/req) | Moderate | Low; hazard 2 for the id write | Single-action fast path; set id at assignment |
| 5 | Per-request configuration resolution: `logLevel()` re-resolved on every `isEnabledForInstance` (ConcurrentHashMap get, `toUpperCase`, `Level.valueOf`; 5–10 calls/req); `readIntegerProperty` boxes; `CORSHeaders` does 5 config lookups per request although CORS is off for mock traffic by default | ~3–5% CPU combined | Moderate | Hazard 2 (runtime `logLevel` changes must invalidate) | Memoise against the configuration modification count; lazy CORS |
| 6 | Worker event-loop count fixed at 5 (`nioEventLoopThreadCount` default) regardless of core count; the main rig arm never varied it | Expected large gain on hosts with more than 6 cores, unmeasured | Likely large | Hazard 5 | Rig sweep 4/5/6/8/12 via `PERF_SERVER_JAVA_OPTS` (keep `-XX:+UseZGC` in the string), then consider default `max(5, cores)` |
| 7 | Event-log consumer at INFO: releases a body's derived forms, then `writeToSystemOut` → `getMessage` decodes the body again and re-caches the String, possibly undoing the heap saving | Code trace; needs verification | Moderate | Hazards 2 and 4 (keep the #2374 guard) | Verify with `BodyDerivedFormReleaseTest` at INFO; render the message before releasing |
| 8 | INFO stdout: `StandardOutConsoleHandler` flushes after every record; each line `String.format`s its timestamp | No effect on ERROR baseline; raises INFO-arm ceiling and reduces ring drops | Low-moderate | Lines may appear up to one batch late | Flush at `endOfBatch`; measure with the INFO arm |
| 9 | Shared atomic writes on every match: `matchCount` CAS; `rotationCount` CAS even for single-response expectations; `ThreadLocal` `Integer` boxing past 127; chaos first-match CAS plus `currentTimeMillis`; graceful-shutdown in-flight counter plus per-request token/listener | Estimate 50–150 ns per contended read-modify-write | Low-moderate | Hazard 5 (except a safe `get()==0` pre-check) | Contention JMH (`MetricsIncrementBenchmark` as template) |
| 10 | Small hot-path items (each ≤2%): `KeysToMultiValues.getFirstValue` O(n²) miss and list allocation on hit; constant `NottableString`s in loop-prevention `containsEntry`; `String.split` in `HttpRequest.splitHostPort`; regex in `URLParser.isFullUrl` (preserve `.` semantics) | Code review | ≤2% each | Low | Individual micro-benchmarks before and after |
| 11 | Compact object headers (JDK 25+, JEP 519) for the JDK 25/26 images | — | ~10–20% smaller live set (estimate) | AppCDS dump and training must use the same flag; confirm ZGC compatibility; not the JDK 21 clustered image | Enable flag; re-run allocation profile and a sweep |
| 12 | Netty leak detection left at its default in production images | ~1–2% CPU (estimate) | Low | Loses the production leak signal; the CI paranoid gate remains | A/B with `ResourceLeakDetector.Level.DISABLED` on a production image |
| 13 | Two `ByteBuf` allocators possibly live: server and client pin `PooledByteBufAllocator.DEFAULT` while Netty 4.2's default is adaptive | — | Unknown | Low | A/B on RSS and throughput |
| 14 | Low-value items: `-XX:InitialRAMPercentage=60` to avoid heap-growth stalls during ramp; virtual threads for the local callback executor via a runtime check (Java 21+ runtime, keeping Java 17 bytecode) | — | Minimal | Low | Opt-in flag only |

### Doc and gate fixes found

These are control-class items requiring gated approval — do not fix in this change, just track:

- `docs/code/netty-pipeline.md` documents a write-buffer water mark for connections, but `MockServer.java` sets it with `.option` (listening socket only), so accepted channels get Netty's default.
- `docs/operations/performance-tuning.md` GC/heap guidance predates the ZGC default and the 60% cap.
- The daily microbench (`perf-test-microbench.sh`) pins `detailedMatchFailures=false`, the opt-out, so its tracked `time_per_op` baseline describes a non-default arm. Moving the pin to `true` resets that baseline, so it needs a deliberate gate decision. (The alloc gate already measures both arms; its labels were corrected.)
- The comment at `MockServerEventLog` "0 for a body-less entry" is stale.

### Checked and not worth pursuing

io_uring (blocked by Docker's default seccomp, would silently fall back); `FlushConsolidationHandler` (HTTP/1.1 flushes once per response — already consolidated); explicit `TCP_NODELAY`/`SO_RCVBUF`/`SO_SNDBUF`; `AlwaysPreTouch` (slower start); THP by default (needs host `shmem_enabled`); `SoftMaxHeapSize`; two non-secure UUIDs per request (externally visible ids).
