# Performance Programme

**The central question is settled.** The healthy serving ceiling is **41,000 rps** at 0.196 ms
median; peak **43,671** (build 420, 2026-09-24, committed `3dbed98ae`). Per repo convention, when
the remaining items below are closed this file is deleted in the same commit — it is not an
archive. The parts that deserve to outlive it have been moved to permanent documentation: see
[What was moved](#what-was-moved).

## Closed

All programme work has landed. The commits below are the ones the programme directly produced.

| What | Commit |
|---|---|
| Per-connection MCP registry leak (~156 KB/connection) | `2a5999395` |
| Opt-in `maxExpectationsSizeInBytes` byte budget | `951b84abe` |
| SUT liveness gate (dead server cannot be reported as throughput) | `ec59fbe23` |
| p95 VU-headroom criterion replacing the right-censored `vus_active_max` | `bac8a96b7` |
| Server-side latency histogram capture | `aeaf0c74a` |
| Expectation-store and accept-queue metrics, charted | `d568695a2` |
| `com.sun` JDK shade-relocation fix | `63a7a9833` |
| S3 fixture moved off the gated MinIO image | `0b0ac1d9b` |
| ECR pull-through enabled | `ce2df52b9` |

## What remains

Four items are open. Three need a run or an observed event; one needs a build to verify a fix
that already landed.

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

### 2. Low-rate handler latency spike — OPEN (unexplained)

In build 420, the sampler interval covering approximately elapsed **1,720–1,990 seconds**, at
**300–1,800 rps**, showed the server-side `request_duration_millis > 5 ms` bucket spiking to
**200–395 counts per interval** — elevated handler latency at low rate. This is the one point in
the run where the server itself was genuinely slow. It is distinct from item 1 above (which is
outside the server handler). It has not been examined.

### 3. Canceled-child reporting — OPEN (needs an observed event)

How a child build reaches `canceled` or `not_run` independently of its parent has never been
observed. The Dependabot-bot trigger-refusal case (a child that is never created, `triggered_build:
null`) was fixed in `ea2336131`. The original question — a child that IS created but reaches one of
those states — cannot be answered by editing the repo; it requires a build that actually reaches
that state.

### 4. Shading defect: `jvm_memory_allocated_bytes` — OPEN (needs a build)

`mockserver/pom.xml` relocates `com.sun` wholesale, including `com.sun.management`.
`JvmMetricsCollector.totalAllocatedBytes()` checks `instanceof com.sun.management.ThreadMXBean`;
the shade plugin rewrites that class reference to the shaded package, so it never matches the real
JDK class. The metric has been dead in every shipped jar since it was added, and
`alloc_kb_per_handshake` has been `null` on every TLS-arm run.

`63a7a9833` is the shade-relocation fix. What remains is adding `com.sun.management` explicitly to
the shade exclusions and verifying the metric fires on a snapshot image.

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
measurement. Fixed by using `vus_active_p95 < pool` instead. Documented in
[docs/code/performance-measurement.md](../code/performance-measurement.md) under `sweep.js`.

## What was moved

| Content | Destination |
|---|---|
| What each harness measures, which CI executes vs lints, that `ForwardPathBenchmark` measures the load generator not the server, `throughput_rps` delivery-ratio semantics, `vus_active_max` right-censoring and the `vus_active_p95` criterion, the ladder-granularity requirement, certified knee figures | [docs/code/performance-measurement.md](../code/performance-measurement.md) |
| Dating and provenance rule; a populated field is not a correct one | [docs/code/startup-performance.md](../code/startup-performance.md) |
| k6 README corrections (`forward.js` was described as running when it never did) | `mockserver-performance-test/k6/README.md` |
| Hazard-class table, evidence standard, and the `rig_valid_peak_achieved_rps` wrong-subject lesson | [docs/code/optimisation-safety.md](../code/optimisation-safety.md) |
