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
| 33, 35, 36 | Server-side memory bounds found by the 512 MiB OOM investigation | design decisions (§5) |
| 39 | Promote the 512 MiB memory-floor container test to blocking | five green master runs on amd64 agents (§5) |
| 40 | Perf baselines shift with the lower image heap | the first daily run after it merges (§5) |
| 34a | Queues left unbounded by #34: WebSocket bidi replies (one delayed task per frame; to bound them, admit or refuse a matcher's whole reply set atomically and close with 1013 on refusal, never shed single frames); `TcpChaosHandler` holds one delayed read (with its `ByteBuf`) per inbound read under a TCP latency profile; control-plane timed scenario transitions (`PUT /mockserver/scenario` with `transitionAfterMs`); undelayed scheduler tasks (forward continuations, undelayed side actions) | a design for each (§5, row 34) |
| 41 | Make the `snapshot-http3` publish blocking | 5 consecutive green master runs of its non-blocking smoke and push (§6) |
| 42 | JNA-based original-destination lookup does not work from the shaded jar | a design choice (§4) |

## Decided against

- **Splitting the netty integration tests across parallel JVMs** — fixed ports and shared static config make this high-risk.
- **Reusing `:maven: build` output for the deploy** — pipeline steps do not share a filesystem.
- **Lite, phased-backoff or batched publishes for the event-log ring** — the earlier rejection
  benchmarked 6 saturating producers, where the consumer never sleeps; below the ceiling it sleeps
  between entries and each request's first publish paid a futex wake. In that regime (local A/B,
  4-core SUT, `logLevel ERROR`, fixed-rate k6, 2–4 runs each at 20k rps) `LiteBlockingWaitStrategy` measured +1.0% CPU
  per request against `BlockingWaitStrategy`, `PhasedBackoffWaitStrategy.withLock` +20% to +64%
  (spinning, which also counts against a container CPU quota), and publishing a request's two
  entries together +6.7%, and batching would hide a delayed response's request from `verify`
  during the delay. Conditional signalling cannot help because the consumer really is asleep at
  almost every first publish; only waking it less often does, which is what landed (§4 #28).
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
| 28 | Use the JFR profile the deep run already records to find where CPU goes at the ceiling, not just where memory is allocated | Build 502's deep-run `load.jfr` over the 48k–64k window: the server used 275% of 600% CPU and no thread exceeded 45% of a core, so the SUT was not the limit (k6 used ~228 us CPU per request against the server's ~55 us). Candidates found: A, event-log publish wake cost (~22% of worker-loop samples in `RingBuffer.tryPublishEvent`); B, per-request configuration reads (~3.3%); C, Prometheus name lookup per increment (~2–4%); D, repeated channel attribute lookups (~2.9%); E, native epoll against NIO. B (five per-request settings memoised against the configuration generation) and D (one attribute per request in `PreserveHeadersNettyRemoves`, single lookups elsewhere) landed: in JMH B saves ~7–9 ns across the five settings, and fixed-rate 20k rps CPU per request did not move measurably. C was declined (see [Decided against](#decided-against)). A landed as `CoalescingWakeWaitStrategy`: the consumer is woken only after 50 ms idle, at a backlog of `min(256, ringSize / 4)`, when in-flight bytes pass a quarter of the budget, or for a control-plane read. Local A/B against master `3d5f8096f` (4 pinned cores, Docker Desktop arm64, JDK 25, fixed-rate k6, 3 interleaved runs each): at `logLevel ERROR` container CPU per request fell 40.6 → 31.6 us (−22%) at 20k rps and 33.2 → 26.4 us (−21%) at 40k rps, ranges disjoint, consumer wakes per request 0.72 → 0.04; at `INFO` the consumer is ~50% busy rendering and almost never sleeps (0.001 wakes per request on master), and CPU per request did not change measurably (61.5 → 62.1 us at 20k rps, ranges overlapping). E: the ceiling run was on NIO because the shaded jar every image is built from carried the epoll `.so` under Netty's unrelocated name, so relocated Netty never loaded it; the shade config now renames it (guarded by `assert-shaded-epoll-natives.sh`). With epoll actually loading, a fixed-rate A/B on four pinned CPUs (linux/arm64 Docker Desktop, 7 interleaved runs per arm, `logLevel=ERROR`, before A landed) found no measurable CPU per request difference: 52.4 vs 52.9 us at 20k and 41.0 vs 42.0 us at 40k (epoll vs NIO medians, ranges overlapping), system time ~50% on both, since JDK NIO also uses epoll(7) on Linux. Landed as a correctness fix (images now run the transport CI tests and the docs describe), not as a CPU win. JNA-based original-destination lookup still does not work from the shaded jar (item 42) | Add the useful JFR views to the step's annotation; confirm A's gain on the rig's daily run; confirm on the next deep run that the SUT JFR shows `EpollIoHandler`, not `NioIoHandler` |
| 31 | Remove the load-generator ceiling from the published throughput measurement | At the ~57k plateau on 6 pinned cores (build 502 JFR) the SUT used ~275% of 600% CPU and ~55 µs CPU per request, while the single k6 used ~228 µs per request and 62–88% of its 17-CPU pin, so the ceiling is the client. `mockserver-performance-test/scripts/rw-multi-k6-sweep.sh` (opt-in, `PERF_SERVING_RW_MULTIK6=true`) now offers each rung from N independent k6 processes started at one instant, merges their native histograms in a pinned Prometheus (true merged percentiles, summed counts), and fails closed on per-process count accounting, start skew, window and a same-request cross-check against the published summary mode; see [performance-measurement.md](../code/performance-measurement.md#rw-multi-k6-sweepsh--the-knee-curve-from-n-merged-k6-processes-opt-in-item-31). Locally the same-request cross-check agreed within 3.5% at p50–p99 and all three degrade tests (a killed process, a paused Prometheus at the end, a Prometheus stall across a settle boundary) marked the run invalid | One rig trial with `PERF_SERVING_RW_MULTIK6=true PERF_INFO_ARM=false PERF_COVERAGE=false`. The pass criterion is `valid` with `cross_check.same_requests.equivalent` true; `cross_check.cross_run` is report-only (two separate runs), read against the N=1 run-to-run spread as its noise floor. Then check per-process CPU headroom (`per_process[].per_rung`) and whether the healthy ceiling rises above ~60k. Only then propose switching the published sweep, as a separate approved change |
| 32 | The deep run's own instrumentation perturbed and diluted what it measured (#28's harness follow-up) | Build 502: the live-heap histogram's 0.4–0.5 s stop-the-world `GC_HeapInspection` landed in 10 of 22 rungs (9 after the settle window), and those 9 were exactly the rungs with p99.9 of 90–480 ms; the recording spanned the whole run, so ceiling CPU was averaged with idle rungs; `jfr` was JDK 21 against a JDK 25 SUT. Built: histogram only during growth.js with a daily placement check, `sut/ceiling.jfr` cut to the knee-to-top rungs, JDK 25 `jfr`, ceiling views and per-thread "% of one core", `JavaMonitorEnter` at 1 ms and `jdk.CPUTimeSample`, and a daily per-rung k6-vs-server over-5 ms table (see [performance-measurement.md](../code/performance-measurement.md#allocation-profile-step)) | Confirm on the first daily deep run that the placement line reads "none inside a sweep rung", `ceiling.jfr` is present and valid, and `jdk.CPUTimeSample` events are recorded on the rig; then close. Follow-up: the server column times only the request handler, so an accept-to-flush (or decode-to-flush) server timer would let the table tell event-loop queueing from the rig |
| 42 | JNA-based original-destination lookup (`SoOriginalDstResolver`, `EbpfOriginalDestinationResolver`) does not work from the shaded jar (#28 E follow-up) | The shaded jar relocates `com.sun.jna` to `shaded_package.com.sun.jna`, but `libjnidispatch`'s JNI entry points are bound to the unrelocated class names, so JNA cannot initialise. Every published image is built from that jar, so even with epoll now loading, transparent proxying in the images falls back to the conntrack resolver. The unshaded jar and its end-to-end test are unaffected | Either exclude `com.sun.jna` from the shade relocation (as the JDK `com.sun.*` subpackages already are) or boot JNA in a relocation-aware way; then prove it with the SO_ORIGINAL_DST end-to-end test run against the shaded jar or image, not the unshaded fat jar |

## §5 — Server-side memory bounds (added 2026-09-30)

The images were OOM-killed at `--memory=512m` under load. That is fixed at the image layer (a
static HEALTHCHECK probe instead of a second JVM, and a lower default heap percentage; see
[docker.md](../infrastructure/docker.md#heap-cap)). The investigation also found four server-side
gaps that let memory outside the heap grow with load. None caused the kill on its own; each is a
design change, not a one-line fix. Items 39–40 are follow-ups of the image fix itself.

| # | Item | What is known | Next step |
|---|---|---|---|
| 33 | Per-connection write-buffer water marks never apply | `MockServer.java` sets `WRITE_BUFFER_WATER_MARK` with `.option(...)` (the server channel) rather than `.childOption(...)`, so accepted connections keep Netty's defaults (32/64 KiB) and a slow reader can buffer more than the 8/32 KiB intended | Move it to `.childOption`, then check every `isWritable()` / `channelWritabilityChanged` path still makes progress under the lower marks (backpressure behaviour change) |
| 34 | Template-rendering and delayed-response queues are unbounded | **Closed.** `Scheduler` admission-bounds delayed responses and template renders with `maxPendingDelayedResponses` / `maxQueuedTemplateActions` (default heap ceiling / 64 KB, an estimate; capped at 100,000; resizable at runtime): over a bound a request gets an immediate `503` with `Retry-After: 1`. Chaos latency on an already-forwarded response is skipped rather than refused, and delayed side actions are shed on their own same-sized budget, so they never 503 a response. Refusals are counted by `mock_server_overload_rejections{reason}` and a rate-limited WARN. An event-loop or `HashedWheelTimer` timer was rejected: both are unbounded heaps too and would move fire-time work onto the I/O thread. Every request-path executor is tabulated in [request-processing.md](../code/request-processing.md#overload-bounds-on-delayed-and-templated-actions) | Remaining unbounded request-path queues are tracked as 34a under What remains |
| 35 | No connection cap or idle-connection timeout | Each open connection costs about 3.9 KiB of kernel socket memory (charged to the container) plus Netty per-channel state; the load test held 4,800 keep-alive connections and nothing limits how many, or for how long idle ones, are kept | Add an opt-in maximum connection count and an idle timeout for keep-alive connections, with metrics for both |
| 36 | `MaxDirectMemorySize` defaults to the maximum heap | Netty's pooled direct arenas may grow to the heap size on top of the heap itself; at 512 MiB that is a second 256 MiB the container does not have | Measure direct-buffer high-water marks under load and set an explicit image default (for example a fraction of the limit), or bound Netty's pooled arena count |
| 39 | `docker_memory_floor_512m` runs non-blocking | It was calibrated on Apple-silicon Docker Desktop only: the `docker/Dockerfile` reference image it builds peaked at 80.5–85.7% of the 512 MiB limit against a 90% threshold. GraalJS, which the test does not build, ships at 45% because it peaked at 87–88% at 50%; at 45% it peaks at 79.2–83.1%, but its file-backed pages still dipped below the 70% line once on a contended host, and two of those runs (which predate the test's absolute 17 MiB file-page floor) would fail that floor, so the absolute floor may need per-image calibration before the GraalJS run can count toward promotion. It records a warning, not a failure, until it has run on real amd64 `default`-queue agents (the same precedent as the `root-snapshot`/`aot` smoke tests and the `docker-build-verify` step) | Promote when both hold: five consecutive green `:docker: container integration tests` runs on master with the peak recorded below 88%, and at least one green run of the same test against the GraalJS image on an amd64 `default`-queue agent (`MEMORY_FLOOR_IMAGE=<graaljs image>`). Then default `MEMORY_FLOOR_BLOCKING` to `true` in `integration_tests.sh`. If the amd64 peak sits above ~88%, lower the standard images' heap to 45% (measured 78.3–82.5%) instead. |
| 40 | The perf baselines move when the lower image heap lands | The main perf SUT runs the GraalJS image, whose 2 GB heap falls from 1.2 GiB to 0.9 GiB (45%), with default `maxLogEntries` from ~155k to ~115.5k, so the growth ring fills sooner; the soak (standard image) and the clustered A/B nodes fall to 1.0 GiB and ~128.5k (50%). No SUT starts a health-check JVM on its CPUs every 10 s any more. Watch the first daily run for a heap `OutOfMemoryError` on the 0.9 GiB GraalJS SUT: the MB-scale arms run against a 256 MiB event-log budget, which measured at up to ~1.7× (~430 MiB) at `ERROR` | Mark the first daily run after the merge as a baseline change rather than a regression, and re-baseline the notify-only comparisons from it. Separately, #34 bounds template and delayed-response queues, so a stress arm that drives templates past the render rate (or delayed responses past `maxPendingDelayedResponses`) may now see `503`s counted by `mock_server_overload_rejections` instead of heap growth |

## §6 — HTTP/3 packaging follow-ups (added 2026-09-30)

Follow-ups to moving the QUIC natives out of the default jar. #37 and #38 closed in the change that
added them and are listed so the item numbers stay unique; #41 remains.

| # | Item | Outcome |
|---|---|---|
| 37 | An HTTP/3 image tag, so container users need not mount a jar | Closed: `mockserver/mockserver:<version>-http3` (+ `latest-http3`, `snapshot-http3`), multi-arch, layered on the standard image digest (`docker/http3/Dockerfile`), smoke-tested with a real HTTP/3 request; Helm `image.variant=http3`. Building it found that no published image had ever served HTTP/3 and that the documented `/libs` mount could not work there: the shaded jar's relocated Netty asks for a differently named native (see [docker.md](../infrastructure/docker.md#http3-image-variant)) |
| 38 | Make the missing-native start-up error name every fix | Closed: `Http3NativeUnavailableException` names the image tag, Helm value, `-http3` jar and the `netty-codec-native-quic` coordinate with this runtime's Netty version and platform classifier, and the underlying error on one line, says so when the native is present but unloadable, and the CLI prints it without a stack trace (see [http3.md](../code/http3.md#lifecycle-integration)) |
| 41 | Make the `snapshot-http3` publish blocking | Open. It runs last and non-blocking in `java-docker-push-snapshot.sh` (warning annotation on failure, `timeout 20m`) because it has not yet run on a CI agent. After 5 consecutive green master runs (no `snapshot-http3` warning annotation, image pushed), drop the `if !` wrapper so a failure reds the step, as the release publish already does |
