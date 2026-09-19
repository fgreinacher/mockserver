# Performance Programme

**Status: active plan, partially in flight.** Originally written 2026-09-16 against `master`
at `b98d18f0c`. **Revised 2026-09-16 against `master` at `a984a8c3a`** after a second
read-only audit that checked the first audit's load-bearing claims against the code and
against the repo's own stored results. Two of those claims were wrong; see
[Corrections to the first audit](#corrections-to-the-first-audit).

Tier 1 items **2, 3, 4, 5, 6 and 7 are being implemented now**. **Item 1 has landed** — a
detected regression on a gating metric now fails the build, which is the notification (no
webhook or channel; see item 1). Everything else is unstarted.

Per repo convention, the change that finishes this work **deletes this file in the same
commit** — it is written to be consumed and removed, not to persist. The parts that deserve
to outlive it are named in [What survives this plan](#what-survives-this-plan) at the end;
move those before deleting.

## Bottom line

MockServer's performance harness is better than its reputation, narrower than its claims,
and **less trustworthy than the first audit concluded**. It measures one dimension in one
deployment profile continuously — response latency on the local-match hot path, on a
six-core pinned central server. Almost everything else is either measured once and
published, or never measured at all. The one signal the first audit called good had a
four-orders-of-magnitude internal contradiction in the repo's own published data; that has
now been **diagnosed and fixed** — it was a client-side rig artefact (see Finding 3).

Five things a reader needs before anything else:

1. **The daily latency signal was measuring the rig, not the server — now fixed
   (2026-09-16).** The committed `perf-result.json` recorded, from a single run, a sweep p95
   of **0.279 ms at 16,000 req/s** and a regression-scenario p95 of **1,014 ms at 200 req/s**;
   both could not describe the same server. Finding 3 shows the regression tail was a
   client-side VU-allocation connection storm and fixes `regression.js` (stagger + a fixed
   equal VU pool + warm-every-path + a settle exclusion), verified to still catch a real
   slowdown. See
   [Finding 3](#finding-3-the-daily-latency-percentiles-and-the-sweep-disagree-by-four-orders-of-magnitude).
2. **Proxying is effectively unmeasured**, despite being half of what MockServer is. The
   daily run measures a forward *action* at 200 req/s. `HttpConnectHandler`, the SOCKS
   handlers, the relay handlers, transparent proxying, binary proxying and the HTTP/2
   relay have never been benchmarked at any rate by any harness.
3. **The laptop / per-test-method profile has no measurement at all** — and the profile's
   dominant code path is not the one the proposed startup measurement covers. Users run
   MockServer **in-JVM** via `MockServerExtension` and `ClientAndServer.startClientAndServer`,
   not via `docker run`.
4. **Several checks that look like gates cannot fail for the reason they claim.** The one
   real pass/fail load gate runs at 300 req/s against a server measured at ~32,000 req/s,
   on the Spot `default` queue.
5. **The run metadata cannot support the provenance the programme depends on.** The
   published run records `"instance_type": ""`, and the result schema has no field for heap,
   GC, JVM options or log level. Several proposed mechanisms — the hardware-invalidation
   rule, the ratchet, the website provenance line — cannot be built until that is fixed.

The infrastructure needed to fix most of this already exists and is well built. This
programme is mostly about **pointing existing harnesses at unmeasured things**, **making the
existing measurements say what they are**, and **closing the loop back to the website** —
not new infrastructure.

## The mandate this serves

From the repo owner, verbatim:

> For the work on performance I want to ensure we're considering: scale; performance of
> request/response handling **and proxying**; CPU and memory growth and efficiency; and
> start up time; as well as how small the CPU and memory can be for low volume scenarios.
> I want MockServer to behave well when run **per test method on a user's laptop across
> lots of parallel tests**, as well as behave well when run as part of a **heavily loaded
> central deployment used by many pipelines or consumers**, and when run as part of a
> **performance test**.
>
> The plan should include consideration of how we can **maintain the goals, re-assess, and
> constantly improve** in the face of other changes flowing into the project.
>
> I want to make sure any performance improvements are **fully tested to confirm they work
> correctly**.

That is **six dimensions** across **three deployment profiles**, plus a correctness
obligation that has its own section
([Proving a performance change is still correct](#proving-a-performance-change-is-still-correct)).

| | Dimension |
|---|---|
| D1 | Scale — throughput ceiling, connection ceiling, how capacity grows with cores and instances |
| D2 | Request/response handling — latency and cost of matching and responding |
| D3 | **Proxying** — forwarding, CONNECT tunnelling, SOCKS, relaying. A different cost profile from matching a local expectation |
| D4 | CPU and memory **growth** over time and **efficiency** under load |
| D5 | Startup time |
| D6 | The CPU and memory **floor** — how small MockServer can be for low-volume use |

| | Profile |
|---|---|
| **L** | Per test method on a laptop, many instances in parallel. Short-lived, near-idle, dozens at once |
| **C** | Heavily loaded central deployment serving many pipelines and consumers. Long-lived, saturated |
| **P** | Inside someone else's performance test — either serving load, or generating it via Load Scenarios |

**On the scope of "request/response handling".** The first audit read D2 narrowly, as the
local-match hot path, and explicitly deferred HTTP/3, LLM mocking, async messaging, WASM
rules and the dashboard. That was defensible as a first cut and is **no longer defensible as
a final one**. The owner asked for MockServer to behave well in a heavily loaded central
deployment; a central deployment is precisely where a customer streams SSE from a mocked
LLM, where the dashboard is left open on someone's second monitor, where state is clustered
across instances, and where a hundred pipelines each hold a TLS connection. Those are
request/response handling. See [Feature surfaces the first audit
excluded](#feature-surfaces-the-first-audit-excluded), which adds five of them to the
programme and names the five it deliberately leaves out.

## How to read the numbers in this document

**Every figure below is dated and version-stamped.** That is deliberate: the whole failure
mode this programme addresses is figures going stale silently. If you are reading this
months later, treat any undated number as untrustworthy and any dated number as history,
not as current state. Re-measure before you rely on it.

Analysis date: **2026-09-16**, `master` at `a984a8c3a`. Where a claim below could not be
verified by reading code or stored results, it is marked **unverified** rather than dropped,
because an unverified claim someone can go and check is more useful than a silent gap.

## Corrections to the first audit

The first audit was written from a read-only pass over harnesses, scripts, stored results
and the website. It measured nothing, so its claims about what a harness *does* were
inferences. This revision re-checked the load-bearing ones.

**Verified correct, no change:**

| Claim | How verified |
|---|---|
| The CI sweep ladder stops at 16,000 | `perf-test-run.sh:162` sets `K6_SWEEP_RATES` default `500,1000,2000,4000,8000,16000` |
| `forward.js` is in no step and no lint list | `perf-test-lint.sh` inspects `smoke, load, stress, soak, regression, growth, sweep`. No pipeline step references `forward.js` |
| `-Xshare:auto` means a broken archive degrades silently | `docker/Dockerfile:164` and `docker/local/Dockerfile:102` pass `-XX:SharedArchiveFile` with no `-Xshare:on`. The Dockerfile comment at line 161 *states this outcome explicitly* — the repo already knows, and ships it anyway. The build check is `ls -l /mockserver.jsa` (line 120). **Now also measured — see Finding 4** |
| `nioEventLoopThreadCount` is a fixed 5; `actionHandlerThreadCount` is `max(5, availableProcessors)` | `ConfigurationProperties.java:2223-2237`. Note `availableProcessors()` **is** cgroup-aware on modern JVMs, so the "sizes off the whole machine" gloss is right for a bare JVM and wrong for a CPU-limited container. The laptop profile is the bare-JVM case, so the concern stands where it matters |
| Published figures were taken at `logLevel=ERROR` while the shipped default is `INFO` | `perf-test-run.sh:85` and `perf-test-load.sh:32` set `MOCKSERVER_LOG_LEVEL=ERROR`; `ConfigurationProperties.java:52` `DEFAULT_LOG_LEVEL = "INFO"`. The run **also** sets `MOCKSERVER_DISABLE_SYSTEM_OUT=true`, a second non-default the first audit missed |
| `PERF_NOTIFY_WEBHOOK` is a silent no-op (at audit time) | `perf-test-compare.sh` guarded the `curl` on `[ -n "${PERF_NOTIFY_WEBHOOK:-}" ]` with no else branch. Not set in any terraform file. **Since removed by item 1** — the webhook is gone; a gating regression now fails the build instead |
| The compare step never reads `.sweep` or `.h2_multiplex` | Confirmed in the `metrics` jq: it reads `.behaviours`, `.growth`, `.microbench` only |
| A new behaviour key is picked up by compare with zero script changes | Confirmed: `metrics` does `(.behaviours // {}) \| to_entries[]`. Item 9a's design is sound |
| The `perf` queue is a single on-demand `c5.4xlarge`, max 1, scale to zero | `terraform/buildkite-agents/variables.tf:79-94` |

**Wrong, and the correction matters:**

1. **`throughput_rps` is not arithmetically pinned.** The first audit said the value is
   "~200 by construction" and the metric "structurally cannot fail". The arithmetic is as
   described — `count / durationSec` from a `constant-arrival-rate` executor — but `count`
   is *completed* requests, and k6 drops iterations when its VU pool cannot keep up. The
   repo's own published run records `throughput_rps` of **191.8, 183.8, 191.8, 191.3,
   186.1, 177.7, 185.9, 185.3** across the eight behaviours. `forward_https_h2` at 177.7 is
   **below** the 180 that a 10-percent `dir:"down"` rule would trip against a nominal 200.
   So the metric moves, has moved, and is already losing 4 to 11 percent of offered load at
   200 req/s.

   The correct diagnosis is worse than the original one, not better: `throughput_rps` is an
   **unlabelled dropped-iteration counter** presented as a throughput measurement. It cannot
   distinguish "the server got slower" from "k6 ran out of VUs", and the run records
   `dropped_iterations` nowhere. **This changed item 5**: the answer was not to delete the
   metric, it was to record what it is actually detecting — done in `19686f9f1`, and the
   shortfall it was detecting is now **explained and fixed** (Finding 3, 2026-09-16): the same
   tied-up VU pool that produced the latency tail. After the harness fix the light-path
   `delivery_ratio` reads ~1.00 with zero `dropped_iterations`.

2. **`regression.js` is now a trustworthy continuous signal (fixed 2026-09-16).** In the
   published run its p95/p99 were between 1,012 ms and 2,154 ms at 200 req/s per behaviour —
   a client-side rig artefact, now diagnosed and fixed (light-path p99 collapses from
   ~1,900-3,000 ms to single-digit/low-double-digit ms, drops to zero). See
   [Finding 3](#finding-3-the-daily-latency-percentiles-and-the-sweep-disagree-by-four-orders-of-magnitude).
   The JMH `alloc_bytes_per_op` backstop remains the strongest *absolute* signal; the k6
   latency percentiles are now sound as a *relative* change detector.

3. **The acceptance table now carries a REFUTED premise (item 9a), on measurement.** The
   9a control "disable forward pooling; the CONNECT behaviour moves" describes something that
   cannot happen: the CONNECT tunnel is architecturally unreachable from the forward pool
   (`RelayConnectHandler` connects a fresh per-tunnel `Bootstrap`, never `NettyHttpClient`/the
   pool), so `forwardConnectionPoolEnabled` moves only the absolute-URI arm (measured 1→150
   connections), not CONNECT (~30 vs ~49, unmoved). The pooling lever is still guarded — by
   item 3 on the absolute-URI path (gating `forward.error_rate` 0.997, exit 99) — 9a simply
   named the wrong arm. Recorded so nobody re-attempts it; full evidence in the 9a row below.

**Could not verify:**

- **That the published run used ZGC with an 8 GB heap.** The result schema records
  `agent.instance_type`, `agent.queue`, the cpusets and the image. It does **not** record
  heap, GC, `JAVA_TOOL_OPTIONS`, `PERF_SERVER_JAVA_OPTS` or log level. The claim may be true
  from build logs; it is not recoverable from the artefact. That gap is itself a finding —
  it is [item 0](#0-make-a-result-self-describing-before-anything-compares-them).
- ~~**What causes the 1-second regression p95.**~~ **Settled (2026-09-16):** a client-side
  VU-allocation connection storm — see Finding 3 for the diagnosis, fix and evidence.
- **Whether `alloc_bytes_per_op` really is agent-independent.** Still open, still one cheap
  experiment.

### What actually runs pre-merge

The first audit asserted merge-blocking for a check without checking where its step is wired.
That produced a recommendation that reads as a PR gate and is not one. The topology, verified
2026-09-16, so nobody has to infer it again:

- **Exactly one pipeline has `trigger = "code"`**: the orchestrator, `.buildkite/pipeline.yml`.
  Every other entry in `terraform/buildkite-pipelines/pipelines.tf` is `trigger = "none"`, and
  the `provider_settings` block derives `build_branches`, `build_pull_requests` and
  `publish_commit_status` from that flag — so no other pipeline builds a PR directly or
  reports a commit status of its own.
- The orchestrator's `generate-pipeline.sh` dispatches sub-pipelines by changed path, passing
  the **PR branch**, and `trigger-pipeline.sh` polls each triggered build to completion — so a
  sub-pipeline failure **does** fail the PR.

| Where a step lives | Runs pre-merge? | Blocks the PR? | Covers which changes |
|---|---|---|---|
| `pipeline-java.yml`, **no `if:`** | Yes | Yes | Path-filtered: `mockserver/`, `mockserver-ui/`, `test-fixtures/` |
| `pipeline-java.yml`, `if: build.branch == 'master'` | No | Blocks the **master build** post-merge | **Source-agnostic** — every master build |
| `pipeline-container-tests.yml` | Yes | Yes, via the orchestrator | Path-filtered: `container_integration_tests/`, `docker/` |
| `pipeline-perf-test.yml` | No | No — not a PR gate; schedule/UI triggered. Its own build fails on a *gating* regression (item 1), but that is the daily build, not the PR | Commit-guarded daily |

Two consequences the rest of this document depends on:

1. **"Merge-blocking" is a property of wiring, not of a check.** Any gating claim in this plan
   must name the pipeline file, the step label and the branch condition. That is now an
   acceptance criterion.
2. **Pre-merge does not imply broader.** Every pre-merge path in this repo is path-filtered by
   the orchestrator; the master-gated steps are the only source-agnostic ones. See the gating
   rule in [Feedback latency](#feedback-latency-what-should-block-a-merge).

## The model: what the measurement system is today

```mermaid
flowchart TD
  subgraph daily["Daily, commit-gated, notify-only"]
    guard["perf-test-guard.sh
    dispatches only if master moved"]
    run["perf-test-run.sh
    regression.js http + https_h2
    sweep.js ladder capped at 16000
    growth.js + resource sampler"]
    micro["perf-test-microbench.sh
    MatchingBenchmark JMH, one fork
    CandidateIndexBenchmark scaling"]
    h2["perf-test-h2multiplex.sh
    Http2StreamChannelBenchmark"]
    cmp["perf-test-compare.sh
    median plus MAD vs last 10 runs
    reads behaviours, growth, microbench only
    per-metric gating: fails build on a gating metric"]
  end
  subgraph gate["Real pass or fail gate"]
    load["perf-test-load.sh
    load.js at 300 rps
    runs on the noisy default queue"]
  end
  subgraph optin["Opt-in only"]
    inject["stack/inject/run-inject.sh
    injection ceiling, per-core, scaling"]
  end
  subgraph dark["Exists but never executes"]
    fwd["k6/forward.js
    not even linted"]
    soak["k6/soak.js"]
    stress["k6/stress.js"]
    startup["scripts/perf/bench_startup.py"]
    jmhdark["InboundDecode, MetricsIncrement,
    OpenApiValidation, LocalCallbackDispatch"]
  end
  subgraph never["Features with a request-path cost, never measured"]
    feat["HTTP/3 QUIC transport
    LLM SSE streaming physics
    WASM rule interpreter
    Infinispan clustered state
    dashboard WebSocket fan-out
    TLS and mTLS handshake
    OpenAPI request validation"]
  end
  guard --> run --> cmp
  guard --> micro --> cmp
  guard --> h2 --> cmp
  cmp --> s3["S3 bucket mockserver-ci-perf-results"]
  cmp --> ann["Buildkite annotation"]
  cmp -->|"a gating metric regresses"| redbuild["build fails = the notification
  (item 1, landed)"]
  s3 -.->|"no path exists"| site["website performance.html
  static snapshot from 2026-06-24"]
```

A regression on a **gating** metric now fails the build (the solid `redbuild` edge —
item 1), and that red build is the notification. A regression on a notify-only metric still
reaches only the annotation, as does every measured number that reaches S3 and stops (the
remaining dotted edge). The `never` box is the part the first audit left out.

## Coverage map

Status vocabulary: **continuous** = runs on a schedule and is compared; **once** = measured
by hand at a point in time and never since; **dark** = the code exists but nothing runs it;
**none** = never measured.

| Dim | Profile | Status | What exists | Cadence | Threshold |
|---|---|---|---|---|---|
| D1 Scale | L | none | — | — | — |
| D1 | C | continuous | Knee ladder. **Extended to 64,000 in `19686f9f1`** — it previously stopped at 16,000, below saturation, so the ceiling could not move the number | daily | `peak_achieved_rps`, `dir:"down"`, validity-gated |
| D1 | C | once | Published knee: p50 0.19 ms at 32,000 offered / 31,751 achieved; peak achieved 36,324 at 48,000 offered, where p50 had already risen to 23.1 ms. **2026-06-24, build #64, commit `15f4dcf50`, pre-8.0.0, instance type not recorded** | one-off | — |
| D1 | C | none | Connection-count ceiling; keep-alive pool limits; max concurrent connections | — | — |
| D1 | C | continuous | HTTP/2 streams per connection, N = 1, 10, 100 over one h2c connection | daily | **none by design** — variance unknown; a threshold would be guessing |
| D1 | P | opt-in | Injection ceiling, per-core sweep, aggregate scaling N = 1 to 6 | opt-in | none |
| D1 | all | none | req/s **per core for the serving path** — the per-core curve that exists measures the *injector* | — | — |
| D2 Req/resp | L | none | — | — | — |
| D2 | C | continuous, **sound (Finding 3 fixed 2026-09-16)** | p50/p95/p99 per behaviour over HTTP and HTTPS+H2 at 200 rps | daily | median + MAD, 10% floor. The old ~1 s p95 was a client rig artefact, now fixed (stagger + equal VU pool + warm-every-path + settle exclusion); attach the budget notify-only for 10 runs first per open question 9 |
| D2 | C | continuous | p95 < 25 ms, p99 < 100 ms at **300 rps** against a server measured near 32,000 | daily | real gate, ~100x headroom, on the Spot `default` queue |
| D2 | C | continuous | Matcher time/op and `gc.alloc.rate.norm`, **one JMH fork** | daily | median + MAD, 5% floor — the strongest absolute backstop |
| D2 | C | dark | Inbound decode allocation; metrics contention; OpenAPI validation cache; callback dispatch hop | never run | — |
| D2 | C | none | **Template engine cost by engine** — the `template` op exercises Velocity only | — | — |
| D2 | C | none | **TLS and mTLS handshake cost** — connections are reused per VU, so handshake is amortised out of every number | — | — |
| D2 | C | **continuous** | **ByteBuf leak detection at `paranoid`, gated at `verify`** — landed `a1158a104`. Found a production leak on its first run | every `mockserver-netty` build | **fails the module**; `-Dmockserver.failOnNettyLeak=false` downgrades |
| D2 | P | dark | Stress past the knee; sustained soak | **lint-only — nothing runs them** | — |
| **D3 Proxy** | all | continuous (partial) | Forward **action** latency at 200 rps | daily | median + MAD |
| **D3** | C | **continuous** | Forward connection-pool exhaustion at 1,500 rps. **Wired in `19686f9f1`** — it had never executed and was not even linted | daily | `forward_guard.error_rate`; infra failure now announces itself |
| **D3** | all | **none** | CONNECT tunnel; SOCKS4/5; transparent proxy; binary proxying; HTTP/2 relay; upstream-proxy chaining; proxy MITM TLS | — | — |
| D4 CPU | L | none | Idle-instance CPU floor | — | — |
| D4 | C | continuous | CPU start/end/peak/ratio over a 6-minute growth run | daily | ratio vs median + MAD, floor 1.30 |
| D4 Memory | L | none | Per-instance RSS; idle heap floor; whether the documented `-Xmx512m` sidecar recipe works | — | the 512 MB recipe is **prose only, never measured** |
| D4 | C | continuous | **Live-set floor ratio AND absolute `live_set_bytes`**, SUT bounded to 2g so GC cycles. Landed `19686f9f1` | daily | ratio floor 1.30; absolute budgeted |
| D4 | C | unit-only | Ring-buffer bound | every build | **asserted in unit tests; never demonstrated in a live process under load** |
| D4 | C | none | Long-running steady state over hours | — | — |
| D4 | C | **none** | **Per-connection memory after the 8.0.0 HTTP/2 multiplex change** | — | see Finding 2 |
| D4 | C | **none** | **Event-log verification query cost at high log occupancy** — the central-deployment pattern, unmeasured at any occupancy | — | — |
| D5 Startup | L | **once** | Docker 855 ms to **566 ms** with AppCDS; fat jar 919 to 804 ms; first-request warmup 230 ms to 5-11 ms; `-aot` about 580 ms. **2026-07-02, 7.3.1-SNAPSHOT, arm64 Mac, median of 5** | **by hand, once** | — |
| D5 | L | **none** | **In-JVM start via `ClientAndServer.startClientAndServer`** — what `MockServerExtension` actually does, and what a laptop user pays per test class | — | — |
| D5 | L | **continuous** | Whether the AppCDS archive actually maps. Landed `bb3c41246`. **Silent degradation confirmed by experiment 2026-09-16** — corrupted archive, container healthy, `bad magic number` and nothing else | every master build, post-merge | **boolean, blocking** — not a PR gate, deliberately |
| D5 | C | **none** | Startup with a large `initializationJsonPath` — a central deployment boots from one | — | — |
| D6 Floor | L | none | Minimum viable heap; idle thread count. `nioEventLoopThreadCount` is a **fixed 5**; `actionHandlerThreadCount` is `max(5, cores)` | — | **designed and documented, never measured** |
| D6 | L | none | N parallel instances: port pressure, aggregate threads, aggregate RSS, GC interference | — | — |
| D6 | L | **none** | **Dev mode.** `mockserver.devMode` exists specifically for this profile and nobody knows what it saves | — | — |
| **NEW D2, D4** | C, P | **none** | **LLM / SSE streaming.** Per-token delays are scheduled onto a `max(5, cores)` pool; under saturation scheduler-thread starvation delays per-token emission (fidelity drift) and the `writeAndFlush` each task hands off loads the shared event loops a concurrent `match` uses (see item 12 — the original `CallerRunsPolicy`-on-the-event-loop claim was measured wrong) | — | — |
| **NEW D1, D2** | C | **none** | **HTTP/3 / QUIC** — a full second transport with different physics | — | — |
| **NEW D1, D2, D4** | C | **none** | **Clustered state** (`StateBackend`, Infinispan) — the feature built for the exact profile the owner named | — | — |
| **NEW D2** | C | **none** | **WASM rule bodies** — a per-request interpreter on the matching path when used | — | — |
| **NEW D2, D4** | C | **none** | **Dashboard WebSocket fan-out** while serving traffic | — | — |

## Trustworthiness: which numbers would catch a regression tomorrow

Three grades: **measured continuously** (runs and is compared), **measured once and
published** (a historical fact, not a current one), and **asserted in prose**. Then the
sharper question: *could this check pass while the thing it measures had regressed?*

| Check | Would a regression be caught? |
|---|---|
| `regression.js` latency percentiles | **Now trustworthy (Finding 3 fixed 2026-09-16).** Median + MAD over 10 runs with a 10% floor is a sound *method*; the published p95 of 1,014 ms was a client-side VU-allocation connection storm, not the server. Fixed (stagger + equal `preAllocatedVUs==maxVUs` + warm-every-path + settle exclusion), and verified to still move with a real +25 ms server delay so the exclusion does not hide regressions. Safe to budget — notify-only for 10 runs first (open question 9) |
| `regression.js` `throughput_rps` | **It fired for the wrong reason — now understood.** A dropped-iteration counter in disguise: it fell when k6's VU pool was tied up, conflating server slowdown with client starvation. Published values 177.7 to 191.8 against a nominal 200. Labelled with `offered_rps` and a delivery ratio (`19686f9f1`); the shortfall it detected is the **same** tied-up pool as the latency tail, now fixed (Finding 3) — post-fix light-path `delivery_ratio` reads ~1.00. Keep it un-budgeted (it is a client-health gate, not a server throughput measure — use the sweep for peak throughput) |
| `MatchingBenchmark` | **Yes for allocation, weakly for time.** `gc.alloc.rate.norm` is a real absolute backstop. `time_per_op` runs `-f 1` — a **single JMH fork** — so inter-fork JIT variance is never sampled and the measured dispersion understates the real one. It was also **silently dark 2026-09-12 to 2026-09-16** |
| `load.js` CI gate | **Barely.** 300 rps against a server measured near 32,000; a p95 gate of 25 ms against a sweep p50 of 0.19 ms. A 50x throughput regression passes. It runs on the Spot `default` queue, so its noise floor is worse than its sensitivity |
| `sweep.js` knee | **Now yes, previously no.** The ladder reached only 16,000 where the server is comfortable. Extended to 64,000 with `peak_achieved_rps` budgeted (`19686f9f1`) |
| `sweep.js` — was the **client** the bottleneck? | **Now asserted, previously unknown.** k6's own CPU and dropped iterations are captured per rung and a compromised rung is excluded and named. Before this, the published "~36,000 req/s on six cores" was not proven to be MockServer's ceiling rather than a six-core k6's |
| `growth.js` heap | **Now yes, previously weakly.** Was last-instantaneous over first-instantaneous — a point on the GC saw-tooth — with the SUT unbounded on a 32 GB box where GC barely cycled. Now the live-set floor plus a budgeted absolute, bounded to 2g (`19686f9f1`) |
| Ring-buffer bound | The **bound** is enforced in unit tests. Its behaviour **under sustained load in a live process** is asserted in prose, never demonstrated |
| ByteBuf leaks | **Yes, newly.** Paranoid detection gated at `verify` (`a1158a104`). Configured nowhere before, so Netty ran at ~1% sampling and gated nothing |
| `Http2StreamChannelBenchmark` | **No, by explicit and correct design** — recorded, no threshold, because run-to-run variance is unknown |
| Run provenance | **Insufficient to compare runs at all.** Every stored run carries `"instance_type": ""` — `curl -s` exits zero on an empty body so the fallback never fired. Fixed in `19686f9f1`; the rest of the `config` block is item 0. No heap, GC, JVM-options or log-level field exists |
| Published website figures | **No.** `perf-test-compare.sh` writes to S3 and stops. Nothing regenerates the committed chart data |
| Published *methodology* claims | The page states soak and stress are part of how MockServer is tested. **Neither has ever been executed by CI** |
| Startup figures | **No.** No CI step runs `scripts/perf/` |

### Finding 1: the published figures are a stale customer-facing claim

`performance.html` asserts that a single six-core instance holds sub-millisecond median
latency to 32,000 req/s and saturates near 36,000. The backing data records
`timestamp_utc: 2026-06-24T23:12:13Z`, `build_number: 64`, `commit: 15f4dcf50`,
`agent.instance_type: ""` — **the hardware is not recorded** — and predates 8.0.0.

Three caveats the page does not state:

- The run is believed to have used **ZGC with an 8 GB heap**, not the default configuration.
  **Unverified** — the schema has no field for it, so the claim cannot be checked from the
  artefact. That is item 0.
- Every CI perf run sets `MOCKSERVER_LOG_LEVEL=ERROR` **and** `MOCKSERVER_DISABLE_SYSTEM_OUT=true`.
  The shipped default log level is `INFO`, and the site's own tuning guidance says INFO-level
  per-matcher diagnostics are "the single largest matching-path allocation". The headline
  figures are **not default-configuration figures**, and the page does not say so.
- **Never publish a throughput ceiling without the latency measured at it.** The headline
  "saturates near 36,000" is the peak *achieved* rung — and the curve turns over above it:

  | Offered | Achieved | p50 | p95 |
  |---:|---:|---:|---:|
  | 16,000 | 16,000.1 | 0.159 ms | 0.279 ms |
  | 32,000 | 31,751.2 | 0.194 ms | 4.14 ms |
  | 48,000 | **36,323.8** | **23.119 ms** | **125.136 ms** |
  | 64,000 | 30,870.7 | 97.355 ms | 170.664 ms |
  | 80,000 | 33,040.9 | 112.646 ms | 181.170 ms |

  At the published peak, p50 has risen 119x and p95 30x against the rung below, and 64,000
  offered returns **less** than 48,000 did. 36,324 req/s is the top of an overload curve on
  the way down, not a healthy operating ceiling. A reader sizing a deployment from it will
  provision for 36,000 and get 23 ms medians. Publish `healthy_ceiling_rps` — highest rung
  where achieved is within 5% of offered **and** latency stays within a stated multiple of
  the flat part, which on this data is **32,000 at p50 0.194 ms** — with `peak_achieved_rps`
  beside it, explicitly labelled as degraded.

### Finding 2: the 8.0.0 HTTP/2 multiplex change is unverified

The 8.0.0 changelog records, about issue #2669:

> Users driving very large numbers of concurrent streams over a single connection may notice
> different memory and throughput characteristics, since each stream now has its own
> lightweight channel.

An explicit, self-declared change to per-connection memory and throughput, in the area that
matters most to the central-deployment profile. **Nothing has been re-measured since.** The
benchmark added alongside it sweeps streams-per-connection but has no memory axis and no
threshold, and the published figures predate the change entirely. This is the single most
concrete reason to re-baseline.

### Finding 3: the daily latency percentiles and the sweep disagree by four orders of magnitude

> **RESOLVED — 2026-09-16.** The `regression.js` tail was a **client-side rig
> artefact**, not server latency, and the harness has been fixed so the measured
> percentiles describe the server.
>
> **Mechanism.** The four `constant-arrival-rate` scenarios all started
> simultaneously at `startTime: 30s` with a mid-run allocation ramp
> (`preAllocatedVUs: 20` → `maxVUs: 200`). When the warmup-to-measured
> discontinuity made the first cohort run long, all four scenarios allocated VUs
> at once; **each new VU opens a fresh connection**, and the connection storm on
> the six-core SUT slowed requests further, piling up more iterations and
> allocating still more VUs — a feedback loop that overshot then settled,
> producing a multi-second p95/p99 while p50 stayed sub-ms and `error_rate` stayed
> 0. The 4-11% `throughput_rps`/`delivery_ratio` shortfall was the **same** tied-up
> VU pool, not a second problem. A secondary bug compounded it: the warmup
> scenario touched `/simple`, `/template` and `/forward` but **never `/large`**, so
> the heaviest path (4 KB JSON `ONLY_MATCHING_FIELDS` match) was JIT-cold at
> measurement start.
>
> **Fix** (`mockserver-performance-test/k6/regression.js` + `lib/config.js` +
> `lib/expectations.js`), four coordinated levers, none discarding steady-state
> data:
> 1. **Stagger** the four scenario starts (`K6_REG_STAGGER`, default 5 s) so their
>    allocation/connection transients do not superimpose.
> 2. **Equalise** `preAllocatedVUs == maxVUs` (default 50) so the executor can
>    **never** allocate mid-run — the ramp *was* the storm. Reproduction showed the
>    tail *grows* with pool size (200 was catastrophic; a large pool is a bigger
>    connection storm), so the fix is a modest **equal** pool, not a large one —
>    the opposite of the first guess, because a fast isolated CI core tolerates a
>    big pool that a contended core does not.
> 3. **Warm every path**, including the previously-omitted `/large`.
> 4. **Exclude a settle window** (`K6_REG_SETTLE`, default 10 s) from the measured
>    percentiles — load still runs during it (the transient is traversed, not
>    skipped); only the known start artefact is dropped. The excluded count is
>    reported per behaviour as `settle_excluded`, and `dropped_iterations` still
>    counts the whole scenario, so the exclusion is auditable and cannot silently
>    hide client starvation.
>
> **Evidence** (local, SUT pinned to 6 cores, upstream 1, k6 6; 200 rps/behaviour;
> `match` behaviour, the cleanest signal):
>
> | | HTTP p50/p95/p99 (ms) | drops | delivery | H2 p50/p95/p99 (ms) | drops | delivery |
> |---|---|---:|---:|---|---:|---:|
> | before (master) | 0.15 / 2.5 / **1922** | 426 | 0.956 | 0.25 / 6.6 / **3048** | 690 | 0.937 |
> | after (fixed) | 0.16 / 1.4 / **4.5** | **0** | **1.00** | 0.23 / 2.5 / **10.0** | **0** | **1.01** |
>
> **The fix does not hide a real slowdown.** With a genuine +25 ms server delay
> injected on the match response (`K6_REG_MATCH_DELAY_MS=25`, a self-test knob),
> the measured `match` percentiles moved to **25.4 / 28.4 / 34.0 ms** (HTTP) and
> **25.4 / 28.0 / 32.8 ms** (H2) — the delay shows up in full, so the settle
> exclusion was not tuned until nothing is measured.
>
> **What remains unproven.** Local heavy-path magnitudes (`template`, `large`, and
> under load `forward`) still show run-to-run drops and sub-second tails on this
> contended laptop — macOS does not isolate the pinned cpusets, the 1-core upstream
> competes, and a 60 s window makes the transient a larger fraction than CI's 2 m.
> The diagnosing agent predicted this ("local slow cohort 1-5%, absolute magnitudes
> differ"; CI's slow cohort exceeds 5% so the artefact lands on p95 there, on p99
> locally). The mechanism and the light-path clean-up are decisive; the heavy-path
> clean-up should be confirmed on the isolated CI box on the first run.

The original diagnosis, kept as the record of what was learned:

From **one artefact**, build #64:

| Source, same run | Offered | p50 | p95 | p99 |
|---|---:|---:|---:|---:|
| `sweep` rung | 16,000 rps | 0.159 ms | 0.279 ms | 0.501 ms |
| `growth` probe | 20 rps | — | 0.204 ms | — |
| `behaviours.match_http` | 200 rps | 0.475 ms | **1,014.2 ms** | **1,240.0 ms** |
| `behaviours.forward_https_h2` | 200 rps | 1.297 ms | **1,487.6 ms** | **2,154.1 ms** |

A server serving 16,000 req/s at p95 0.279 ms cannot also serve 200 req/s at p95 1,014 ms.
Every behaviour shows it, on both transports, with `error_rate: 0`. Medians are fine; only
the tail is pathological. The same run's `throughput_rps` is 4-11% short of the offered 200,
consistent with a VU pool tied up.

Candidates, unverified, in rough order of prior probability: k6 VU starvation at scenario
start (all four scenarios use `preAllocatedVUs: 20`, `maxVUs: 200`, and start simultaneously
at `startTime: 30s`; a ~1 s mode is suspiciously close to a one-second scheduling quantum);
contention between the four scenarios plus warmup inside one k6 process on six cores; a real
server-side tail visible only at low concurrency; or a percentile-computation artefact.

**What this blocked (now unblocked).** Three things rested on `regression.js` being sound: its
p95/p99 are the proposed D2 budget metrics, item 9a proposes cloning its scenario shape for
proxying, and the first audit called it the one good continuous signal. The tail *was* a rig
artefact — so with the fix above, the harness now measures the server, and those three no
longer inherit a rig artefact. The diagnostic that settled it was exactly the one predicted:
the tail moved with `K6_REG_PRE_VUS` (and *grew* with the pool), and a uniform +25 ms server
delay showed up in full — starvation/contention, not a server tail, on the light paths.

### Finding 4: the AppCDS degradation is measured, not inferred

On **2026-09-16**, with a deliberately corrupted `/mockserver.jsa` bind-mounted over the real
one, the container **served `/mockserver/status` 200 while logging only `bad magic number`**.
The 34% startup win is given back silently. Item 4 now detects this on every master build.
This moved from an inference about flag semantics to an experiment, and it is the pattern
worth copying: the plan's other flag-derived claims deserve the same treatment.

## The programme

Ordered by value divided by cost. Items marked **[landed]** are on `master`; the commit is
named so a reader can see what was actually done versus what was planned.

### Tier 0 — must precede or accompany everything else

#### 0. Make a result self-describing before anything compares them

*Serves: all. Cost: half a day. **Blocks items 2, 7, 11, 19 and the whole Sustaining section.***

The comparison machinery, the ratchet, the hardware-invalidation rule and the website
provenance line all assume a run records what it was. It does not.

- **The `instance_type` bug is fixed** (`19686f9f1`): `curl -s` exits zero on an empty body,
  so the `||` fallback never fired and `""` was written into permanent history for months.
  A field that exists, is populated, and is wrong survives review in a way an absent field
  does not.
- **Add a `config` block** (`schema_version: 2`): MockServer version and image digest, log
  level, `DISABLE_SYSTEM_OUT`, the **resolved** heap and GC, JVM options, JDK build, k6 image
  digest, cpusets, and the k6 container's CPU allocation. Resolve from the **running JVM**
  where possible rather than echoing the environment variables meant to set it — record what
  the run was, not what someone intended. Mark declared-versus-observed values distinctly.
- **Fail the step when a value cannot be recorded**, rather than writing a placeholder.
- **Do not silently compare across the boundary.** Annotate when a baseline window contains
  runs without a `config` block. Do not backfill history.

**Done when:** a run's JSON carries a populated `config` block and a non-empty
`instance_type`; the annotation names the version and log level; and a deliberately
unobtainable value makes the step fail rather than write an empty string.

#### 1. Make a detected regression fail the build — **LANDED**

*Serves: all. Cost: hours.*

**Decision (owner):** do **not** add a notification channel, a webhook, or a named owner
to read it. A failing pipeline is itself the notification, and the owner checks the pipeline
regularly. That reasoning is sound — but its premise was **false**, which is why this item
existed. `perf-test-compare.sh` annotated a detected regression as a *warning*, printed
"_Notify-only: this does not fail the build_", and `exit 0`d — so a 20% throughput
regression produced a **green** build. The pipeline only went red for *harness* failures.
Checking the pipeline therefore caught harness breakage and missed the thing the check
exists to detect. (The old `PERF_NOTIFY_WEBHOOK` was an *optional* hook configured nowhere —
not in `terraform/buildkite-agents/`, not in `terraform/buildkite-pipelines/`, nowhere — so
it notified nobody either. It has been removed: a configured-nowhere hook that looks like a
notification path is what made this confusing.)

**What landed:** the compare step now **exits non-zero when a *gating* metric regresses**,
which makes "the pipeline fails and that is my notification" actually true, and removes the
need for a webhook, a channel, or an owner reading it.

- **Per-metric gating, not a global flip.** Each metric in the compare `metrics()` jq carries
  an explicit `gating: true|false`. A flagged **gating** metric fails the build (non-zero
  exit); a flagged **notify-only** metric is reported *exactly as loudly* in the annotation
  but does not change the exit code. The annotation's Gate + Status columns distinguish the
  two at a glance (`:red_circle: REGRESSION (fails build)` vs `:warning: flagged
  (informational)`).
- **Only metrics with a derived, trustworthy budget gate today:** the JMH micro-benchmark
  metrics (`*.time_per_op`, `*.alloc_bytes_per_op`) and `forward.error_rate` (a discriminating
  pass/fail guard, not a tuned threshold). **Everything else starts notify-only** — every k6 latency
  percentile (only just fixed in `4ce6ae27b`, so zero clean runs of history), every growth
  ratio, `peak_achieved_rps`, and `live_set_bytes`. Gating those now would fire on noise, and
  a gate that cries wolf gets switched off — the failure mode this whole document warns
  about.
- **`time_per_op` is the weaker of the two JMH signals, and gates anyway — deliberately.**
  `alloc_bytes_per_op` counts allocations, so it is genuinely noise-free. `time_per_op` is
  wall-clock and the micro-benchmark runs `-f 1`, so inter-fork JIT variance is never sampled
  and the observed dispersion is understated (item 15c raises the fork count). The gate is
  self-calibrating rather than a fixed budget — a rolling `median + 3 x 1.4826 x MAD` with a
  5% floor over the last >= 5 runs — so it adapts to real cross-run spread rather than
  enforcing a number picked in advance. The residual risk is sparse history: the backstop was
  dark 2026-09-12 to 2026-09-16, so with only a few points the MAD degenerates toward zero and
  the 5% floor binds against single-fork timing noise. That is the most plausible false red
  here. It is bounded: this is the daily build, not a PR or release gate, and reversing it is
  a one-line `gating: false` flip. Re-assess once 15c has landed and ten clean runs exist.
- **Promotion path (explicit).** A notify-only metric becomes gating once it has **≥ 10 clean
  runs of history** and a budget **derived from that history** (per the acceptance criteria
  and open question 9). Flip its `gating` flag to `true` in the same change that records the
  derived budget. **Who decides:** the repo owner, from the stored S3 history.
- **Fail-closed paths unchanged.** The invalid-run refusal, the missing-artifact path, the
  warming-up (< `MIN_BASELINE`) path, and the `forward_guard` infra-error notice all still
  `exit 0` exactly as before — the non-zero exit is reserved for a flagged gating metric.
- **No `soft_fail` on the compare step**, so the non-zero exit actually reddens the build
  (`perf-test-guard.sh`).

**Done when:** DONE. Proven against local fixtures for all four cases (no regression → exit
0; a notify-only metric flagged → exit 0 with the informational annotation; a gating metric
flagged → non-zero; both together → non-zero, distinguished in the table), plus the
invalid-run and warming-up paths still exiting 0.

### Tier 1 — cheap, high value

#### 2. Extend the sweep past the knee, and prove the client was not the bottleneck — **[landed `19686f9f1`]**

The ladder now reaches 64,000. Per rung, k6's own CPU and `dropped_iterations` are captured,
and a rung where the client was pinned or starved is **excluded and named** rather than
reported. The budgeted metric is **`peak_achieved_rps`**, not `saturation_rps`.

**Two corrections made during implementation, worth preserving:**

- `saturation_rps` as originally specified is **ladder-quantised** — on the published data its
  only neighbours are 16,000 and 32,000. A metric whose smallest possible move is a factor of
  two cannot carry a 15% floor. `peak_achieved_rps` is continuous and moves with the ceiling.
- `perf-test-compare.sh` applied `$m.floor` **only in the `dir:"up"` branch**, so a
  `dir:"down"` floor was **silently ignored**. Fixed symmetrically, with the up branch left
  byte-identical. Without this the item would have shipped a threshold that could not fire —
  the exact defect the programme exists to remove, built into the programme.

Validity-exclusion is bidirectional: if the k6 container degrades, top rungs are excluded,
`peak_achieved_rps` falls, and a **client** problem reports as a **server** regression. The
annotation names which rungs were excluded and why, so an operator can tell them apart.

#### 3. Wire up `forward.js` — **[landed `19686f9f1`]**

The repo's only written proxy guard had **never executed** and was not in the lint list. Now
both. Its failure modes are distinguished: an unreachable upstream announces that the guard
did not run, rather than passing unnoticed because its metric row is absent — a guard that
has never run quietly not running again is the failure this closes.

#### 4. Gate AppCDS being *used*, not merely present — **[landed `bb3c41246`]**

*Where: the master-gated container-integration suite. **Post-merge-on-master blocking** — a considered decision, not an accepted limitation.*

Silent degradation confirmed by experiment (Finding 4). The check forces `-Xshare:on` via
`JAVA_TOOL_OPTIONS` over the real entrypoint so an unusable archive aborts JVM init, and
additionally asserts the `-Xlog:cds` line naming `/mockserver.jsa` — because dropping the
`SharedArchiveFile` flag still boots cleanly on the base archive, so readiness alone would
not catch it. Both halves are load-bearing.

**Implementation trap:** a naive `docker run --entrypoint java ... -version` probe
**false-fails on a healthy archive** — it maps the archive then rejects it with a CDS *shared
class paths mismatch*, because the probe lacks the classpath the archive was trained with.
Reuse the real entrypoint; override only the share mode.

**On placement — the counter-argument, because the next reader's instinct will be to move it
earlier.** The pre-merge `container-tests` pipeline is path-filtered on
`container_integration_tests/**` and `docker/**`; the master-gated suite is
**source-agnostic** and runs on every master build. The archive can stop mapping from
origins those paths do not cover — a JDK base-image digest bump, a jlink module-set change,
a core change that shifts the training run's class set. Moving it earlier buys **earlier
detection of a narrower set of causes** and loses the likeliest ones. Tenanting the
pre-merge slot also means re-importing the `docker/Dockerfile` build, which drags in the
AppCDS training stage (it boots a server and polls up to 240 x 0.5 s) — precisely the
heavyweight tenant that was pulled from that slot for turning it red.

#### 5. Say what `throughput_rps` actually measures — **[landed `19686f9f1`]**

The original specification said this metric was arithmetically unfalsifiable and should be
deleted. **That was wrong on both counts.** The published run records 177.7 to 191.8 against
a nominal 200, and one value already sits below its own trip line. It is a **dropped-iteration
counter in disguise**, conflating server slowdown with client starvation. It is now kept and
labelled — `offered_rps`, `dropped_iterations` and a delivery ratio in the annotation — and
deliberately **un-budgeted** until the 4-11% shortfall is explained. That investigation is
the same one as Finding 3.

#### 6. Measure growth against a realistic heap and against the live set — **[landed `19686f9f1`]**

The SUT ran unbounded on a 32 GB box, so `MaxRAMPercentage=75` gave roughly a 24 GB heap in
which GC barely cycled and a slow leak was invisible. Now bounded to 2g, with the heap metric
changed from a point on the GC saw-tooth to the **live-set floor** — and a **budgeted
absolute** alongside the ratio, because a ratio has a gameable denominator: a leak that
plateaus at the ring cap gives a ratio near 1.0 while the live set is permanently doubled.

#### 7. Add validity blocks to every measurement — **[landed `19686f9f1`]**

Every result carries a `validity` block and compare **refuses to baseline** a run whose block
is absent or false — absent is treated as invalid rather than defaulting to valid, and the
refusal happens before the S3 persist.

**The rule that makes this worth anything:** no assertion enters the validity block until it
has been **observed to evaluate false at least once**, with the provocation recorded as a
comment beside it. An assertion that has never been false is an assumption wearing a check's
clothing. Five checks, each carrying its provocation.

One of those checks was itself a false green on first draft: `resource_samples_present` keyed
on **row count**, so a growth phase where `docker stats` worked but the metrics endpoint was
unreachable produced CPU-only rows, collapsed the heap floor to 0, and would have baselined
`live_set_bytes = 0` — which, as a `dir:"up"` metric, never exceeds its threshold. It would
have silently dragged the rolling median down for every future run. Now gated on
`HEAP_MIN_LAST > 0`.

#### 7b. Netty ByteBuf leak detection — **[landed `a1158a104`]**

*Serves: D2, D4 / all profiles. Not in the original plan; added because the correctness section demanded it and no mechanism existed.*

Leak detection was configured **nowhere** in the tree, so Netty ran at its default ~1%
sampling and gated nothing. Now `paranoid`, with a gate failing `mockserver-netty` at
`verify`.

**The obvious implementation does not work.** A JUnit `RunListener` that throws on leak
detected four leaks, threw 88 times, and **surefire still reported SUCCESS** — it catches
listener exceptions and downgrades them to warnings. The gate is therefore a file that
outlives the fork, checked by Maven. Anyone tempted to simplify it back to a listener will
reintroduce a gate that does not gate.

It found a **real production bug on its first run**: `ProxyAuthenticationValidator` allocated
two unreleased buffers per proxy-authenticated request. Unpooled heap, so GC reclaimed them
rather than exhausting an arena — which is why nothing noticed. Eight further sites were test
hygiene. `PortUnificationHandler` was deliberately left alone: `ReplayingDecoder` owns the
message and `SniHandler` releases its cumulation on close, which a real socket always does
and an `EmbeddedChannel` never did. Changing production to satisfy a harness would have been
the wrong repair.

Cost: about **10%** on the unit phase — cheap enough to leave on every run rather than
relegating to a nightly.

### Tier 2 — moderate cost, closes named mandate gaps

#### 8. Laptop profile: startup and footprint

*Serves: D5, D6 / profile L. Cost: 3-4 days. Where: daily, pinned `perf` queue.*

- **8a.** `docker run` to first successful `/mockserver/status`, as the **median of 9
  measured launches after discarding one warm-up launch** — ten total. Plus RSS and thread
  count of a fully idle instance 30 s after ready, at `--memory=256m`, `512m` and `1g`. The
  512 MB figure directly tests the recipe the website recommends on no evidence.
- **8b. The in-JVM path — the number the mandate actually asks for.** `MockServerExtension`
  calls `ClientAndServer.startClientAndServer(ports)`; there is no container. A user running
  500 test classes pays the in-JVM start cost 500 times and the `docker run` cost zero times.
  `bench_startup.py` has no variant for it. This is also the cheapest of the three to measure.
- **8c. Startup with a large expectation file** — `initializationJsonPath` at 0, 1,000 and
  10,000 expectations. Serves profile C as well as L.
- **8d. Compressed image size as a deterministic counter.** A median-of-9 with a pre-pulled
  image cannot see image growth, which is the laptop user's real first-run pain. Free, exact,
  gateable on any queue.

**Anti-flake:** pinned on-demand box only, never Spot; discard the first launch (cold page
cache); compare through the existing median + MAD machinery; never gate on a single launch.
Notify-only for 10 runs to establish the MAD, then `dir:"up"` with a 25% floor. Note a 25%
floor on a 566 ms baseline is a 141 ms dead band — wide enough to hide most real regressions
while catching a total AppCDS loss, so it is a backstop, not the signal.

#### 9. Proxy-path benchmarks — **the largest genuinely uncovered area the mandate names**

*Serves: D3 / all profiles. Cost: 9a about 2 days; 9b and 9c about a week.*

- **9a (k6, do first):** a `proxy.js` scenario driving MockServer **in proxy mode** rather
  than as a mock — absolute-URI forwarding, and a `CONNECT` tunnel carrying HTTPS, both to
  the upstream container the run already starts. Reuse `regression.js`'s shape so compare
  picks the behaviours up with **zero** script changes (verified: the `metrics` jq iterates
  `.behaviours | to_entries[]`).
  **Clone the FIXED `regression.js` shape (post-2026-09-16), never the pre-fix shape from an
  older commit.** Finding 3 is resolved, so 9a is unblocked — but the thing that made cloning
  dangerous (the 1-second tail) lived *in the scenario shape*: simultaneous `startTime`, a
  `preAllocatedVUs`→`maxVUs` ramp, and no settle window. The current shape fixes that (staggered
  starts, `preAllocatedVUs == maxVUs`, warm-every-path, a `K6_REG_SETTLE` exclusion, and the
  `settle_excluded`/`delivery_ratio` guards). Carry **all** of those into `proxy.js`; do not
  copy the four-orders-of-magnitude bug back in by starting from a pre-fix revision.
  **Shipped 2026-09-17:** `mockserver-performance-test/k6/proxy.js` (mode `forward`)
  cloning the fixed shape; `forward_absolute_proxy` / `forward_connect_proxy` land in
  `.behaviours` (covered by the existing `behaviours.*` budgets, resetting the k6 arm
  set once, as intended). `setup()` fails loud if the CONNECT tunnel carried no TLS
  handshake at all (it measures the proxy CONNECT path; MockServer may itself
  terminate the tunnel TLS with a generated cert). 9b (SOCKS5) and 9c (JMH relay) remain.
- **9b:** a SOCKS5 rung. k6 supports an HTTP proxy but not SOCKS, so this needs a small
  driver or a SOCKS-aware sidecar; if awkward, downgrade to a JMH benchmark of the handshake
  handlers rather than skipping the dimension.
- **9c (JMH):** a relay benchmark measuring bytes/s and allocation per relayed KB. The relay
  is byte-copy dominated and nothing like matching, so the matcher backstop says nothing
  about it.

**Naming trap:** `ForwardPathBenchmark` does **not** benchmark proxying — it measures the
*load generator's* outbound render path. Do not assume proxying is covered because that file
exists.

#### 10. Turn on the soak — weekly, not daily

*Serves: D4 / profile C. Cost: 1-2 days to wire.*

`soak.js` exists with p99-drift and error-rate thresholds and has never run. Add a weekly
schedule on the `perf` queue at 2 hours with a bounded `--memory`.

- **Schedule it out of the daily's slot.** The queue is `max_size = 1`; a 2-hour soak
  starting at 04:00 UTC blocks that day's regression run entirely.
- **10b — event-log verification cost as the log fills.** Issue `verify` and
  `retrieveRecordedRequests` at a low fixed rate throughout and record latency against log
  occupancy. This is the central-deployment pattern — pipelines assert — and a query against
  a full 100k ring is where an O(n) regression bites hardest. One extra scenario inside a run
  that is already happening.
- Notify-only until about 8 weekly runs of variance exist. That is **two months** to a usable
  budget; plan for it.
- This is what finally **demonstrates** the ring-buffer bound under load rather than
  asserting it.

#### 11. Re-measure the 8.0.0 multiplex cost, with a memory axis

*Serves: D1, D4 / profile C. Cost: 2-3 days.*

Add a **connections** axis (N connections x M streams) to the existing streams-per-connection
sweep and record heap delta per established connection — precisely what the changelog warned
had changed. Keep it notify-only until variance is known.

**Done when** a `bytes_per_connection` figure exists for 1x1, 10x10 and 100x10, dated, and is
compared against a **pre-8.0.0 build** once. The comparison is the entire point; a new number
with nothing to compare it to does not answer the changelog's warning.

**Shipped 2026-09-17:** `org.mockserver.benchmark.Http2ConnectionMemoryBenchmark` (+ `run-h2-connection-memory.sh`)
adds the connections axis (N connections x M in-flight streams; shapes 1x1/10x10/100x10) and records
`bytes_per_connection` as (loaded heap - baseline heap) / N with the event log CLEARED before each sample so
the delta is connection + stream child-channel state, not logged bodies. Four self-test gates fail loudly
(exit 2) rather than publish a number over nothing: distinct-connection count, `C*S` streams established
(impossible on fewer than `ceil(C*S/100)` connections given `MAX_CONCURRENT_STREAMS=100` — an independent
proof the axis is real), event-log-empty-at-sample, and a plausible-magnitude floor/ceiling. The step
`perf-test-h2multiplex.sh` runs it alongside the throughput sweep and merges both into `perf-h2-multiplex.json`,
which `perf-test-compare.sh` persists into the S3 run history (a dated trend). The
`h2_connection_memory.*.bytes_per_connection` budget key is committed NOTIFY-ONLY and DORMANT (the daily
compare's metrics jq does not yet read `.h2_connection_memory`, so it cannot perturb the fail-closed
missing-budget rule; wiring it is a one-line clause in that k6/compare-owned script). **The pre-8.0.0
comparison (the whole point) was run once** via `run-h2-connection-memory-compare.sh` (same external client,
diff server-container RSS): pre-multiplex 7.6.0 vs first-multiplex 8.0.0, `-m 512m`, 3 repeats, median.
At the 100x10 shape (the only one RSS resolves with low spread, ~4-6%): **271,581 -> 338,690 bytes/connection,
+24.7%** (~+67 KB/connection, roughly the cost of the 10 concurrent stream child-channels the multiplex
design adds). 1x1 (below RSS 0.1 MiB granularity) and 10x10 (spreads overlap) show no resolvable difference.
The changelog's warning is thus CONFIRMED and quantified: per-connection memory rose modestly (~25% at
100x10), not the ~2.7x a naive un-warmed measurement first suggested (fixed by warming the h2 path before the
idle baseline so first-traffic JVM warm-up is not mis-charged to the connections). The trustworthy quantity is
the cross-version DELTA (identical client + identical warm-up on both images cancel everything else); the
absolute `bytes_per_connection` is a marginal cost beyond a ~100-stream-warm process (the warm-up pre-grows
the Netty pooled arena) and, in-process, whole-JVM heap — both are order-of-magnitude/trend signals, not pure
per-connection costs. The in-process harness also warms the h2 path before the first shape's baseline (the
same correction), which removed a ~108 KB one-time upward bias from the 1x1 figure; its residual spread is
GC-read granularity at single-connection scale, so the low-noise 100x10 (spread ~1%) is the figure to trust.

#### 12. LLM and SSE streaming under concurrency — **new**

*Serves: D2, D4, D1 / profiles C, P. Cost: 3-4 days.*

**Why this is in scope rather than a future feature.** Per-token delays are scheduled onto a
pool sized `actionHandlerThreadCount()` (default `max(5, cores)`) — verified:
`Scheduler.java:82-86` builds `new ScheduledThreadPoolExecutor(actionHandlerThreadCount(), …,
CallerRunsPolicy)`, and `HttpSseResponseActionHandler.java:135,167-168` schedules each event's
delay there, chained per stream (the next event is scheduled only from the current write's
success listener). At the default 50 tokens/second every concurrent stream generates 50
scheduled tasks per second; 100 streams is 5,000 tasks/second onto that pool.

**Mechanism corrected (2026-09-17, from measurement — the original claim below was wrong).**
The original text said saturation makes `CallerRunsPolicy` run the task on the calling event
loop. It does **not**: a `ScheduledThreadPoolExecutor`'s `DelayedWorkQueue` is **unbounded**, so
its rejection handler (`CallerRunsPolicy`) fires only at executor **shutdown**, never from load —
a `CallerRunsPolicy` counter reads ~0 under load, and MockServer exposes none anyway (so metric
#3 below is recorded as a documented absence, not fabricated). The real degradation, both
observed on a constrained SUT (1 CPU / 2 scheduler threads): (1) **scheduler-thread starvation** —
when the small pool cannot service the due `writeEvent` tasks on time, per-token emission runs
LATE, so inter-token timing drifts (the p99 error went 5 ms → 57 ms, max → 103 ms while the
median stayed on time — a fat TAIL, so it must be reported as a distribution, never a mean); and
(2) **shared event-loop write pressure** — each `writeEvent`'s `ctx.writeAndFlush` enqueues onto a
Netty event loop, so heavy streaming loads the very loops a concurrent `match` uses (its p95 went
1.5 ms → 40.7 ms, a 27× within-run A/B ratio, under sustained load at saturation). So streaming
**does** degrade the hot path — via event-loop write pressure, not via `CallerRunsPolicy`.

Streaming is also the one feature whose **correctness claim is a latency claim**: the
implementation promises cumulative timing accuracy by carrying sub-millisecond remainders
forward. True for one stream in a unit test, untested for a thousand. A timing-fidelity
feature with no timing measurement is exactly the shape this programme exists to find.

Measure: **inter-token delay error** (the distribution of actual minus requested — a fidelity
metric, not a throughput one), heap per open stream, the p95 of a concurrent plain `match`
request while streams run (the within-run A/B showing whether streaming steals the hot path),
and — where MockServer instruments them — scheduler-saturation counters. A deterministic
`CallerRunsPolicy` counter was the original intent, but per the correction above it cannot move
under load; the meaningful deterministic saturation signal would be a **scheduler queue-depth**
or **task-lag** gauge (neither exists today). **Shipped 2026-09-17:** `streaming.js` sustains the
concurrency and drives the match A/B against a dedicated constrained SUT; a single-threaded SSE
reader (`k6/tools/sse-fidelity-reader.py`) times inter-token gaps idle (client-jitter floor /
positive control) then under load; `perf-test-run.sh` samples heap-per-open-stream. All
`streaming.*` budgets are notify-only.

#### 13. Clustered state under load — **new**

*Serves: D1, D2, D4 / profile C. Cost: about a week.*

The `StateBackend` SPI and the Infinispan backend are built for exactly the deployment the
owner named, and move expectation reads and event-log writes onto a network. No number exists
for what that costs.

Run `regression.js` unchanged against the in-memory backend and a two-node cluster **in the
same run**; the in-memory arm is the control and the metric is the **ratio**. That is the
within-run A/B pattern `CandidateIndexBenchmark` already establishes as the repo's gold
standard, and it cancels almost all environmental noise. Reuse the clustered-libs jars the
container-tests pipeline already builds rather than inventing a second build.

#### 14. TLS and mTLS handshake cost — **new**

*Serves: D2, D4 / profiles C, L. Cost: 1-2 days, sharing item 9a's run.*

The https_h2 run reuses connections per VU, so handshake cost is amortised to near zero and
appears in no measured number. A central deployment pays a handshake per short-lived CI
consumer; the laptop profile pays one per test class. Measure handshakes/second, CPU and
allocation per handshake, across TLS 1.3 server-only and mTLS, plus an arm with the native
provider absent — the Dockerfile carries a documented fallback that nothing exercises under
load.

**Shipped 2026-09-17, sharing item 9a's run:** `proxy.js` mode `handshake` drives the three
arms (`tls13` / `mtls` / `jdk`) with `noConnectionReuse` (a fresh handshake per iteration —
proven against a reuse control that collapses handshake time to 0). The native-absent arm
forces Netty's JDK provider via `-Dio.netty.handler.ssl.noOpenSsl=true` (verified to flip
`SslContext.defaultServerProvider()` from `OPENSSL` to `JDK`). `perf-test-run.sh` emits
`.tls_handshake` per arm — `handshakes_per_s`, `handshake_p50/p95_ms`, `cpu_ms_per_handshake`
(docker-stats CPU integrated) and `alloc_kb_per_handshake` — the last enabled by a new
`jvm_memory_allocated_bytes` JVM metric (a monotonic thread-allocation counter; the figure is
its delta ÷ the `requests_received_count` delta on that arm's SUT). All `tls_handshake.*`
budgets are notify-only.

#### 15. Cheap feature arms on measurements that already run — **near-free**

*Cost: 1-2 days for all four.*

- **15a.** `template` exercises **Velocity only**. Add Mustache and JavaScript ops — three
  lines of expectation seeding, and compare picks them up with no script change. The
  JavaScript engine carries a warm-up cost nobody has quantified.
- **15b.** Promote the four dark JMH benchmarks to the daily microbench step. They are
  written, unrun, and bit-rotting toward the same silent death `MatchingBenchmark` had. The
  OpenAPI one already has cached-versus-per-request arms — a within-run A/B, ready to go.
  **This also fixes the biggest weakness of the allocation backstop** (item 16).
- **15c.** Raise the JMH fork count to 2 for `time_per_op`. `-f 1` never samples inter-fork
  JIT variance, so the measured MAD understates the real dispersion and any derived budget is
  tighter than the data supports. **Land this before deriving any timing budget.**
- **15d.** A body-size axis on `large`: 4 KB, 1 MB, 10 MB, plus one file-backed body.

#### 16. Widen the allocation backstop, and wire it where it runs pre-merge

*Cost: folded into 15b plus half a day.*

Running `alloc_bytes_per_op` per merge is the right instinct — it is the one signal cheap and
deterministic enough to attribute to a single commit. Two things must be true first, and
neither is today.

**Its coverage does not support the claim.** `MatchingBenchmark` measures **matching only**.
It does not touch Netty decode, response serialisation, the event-log write, or the response
writer. An allocation regression that moves bytes *out of* the matcher and *into* decode
shows up as an **improvement**. A gate satisfiable by moving cost somewhere it cannot see is
a false green by construction. Promote the decode benchmark and add a response-write one, and
give the metric an **absolute committed budget**, not a rolling median — a rolling median over
per-merge history absorbs exactly the slow drift the gate exists to catch.

**"Per merge" is a property of wiring, not of the check — and "blocks" needs qualifying
for this project.** The gate is the step labelled `:scales: per-merge allocation gate
(item 16)` in `.buildkite/pipeline-java.yml`, with **no `if:` branch condition**, placed
before the `wait` preceding the master-gated block. That unconditional wiring is correct and
worth keeping: it runs on **both** PR builds and the master build. But this project commits
directly to `master` for its own work (PRs are for dependabot and community contributions),
so "blocks" is only literally true for the **PR-shaped** minority:

- **On a PR build** (dependabot, community) the gate runs pre-merge and genuinely **blocks
  the PR** — the regression is stopped before it reaches `master`.
- **On a direct-to-`master` commit — this project's normal path — the commit has already
  landed**, so the gate cannot block it. It is a **post-merge detector** that reds the
  **master build** after the fact, exactly as it did on build 2262 (see the item 16 negative
  control). An allocation regression *can* reach `master` this way; the gate then reports it,
  it does not prevent it.

Keep the step unconditional. Do not put it in the container-integration suite and do not add
a branch condition "for safety" — either choice silently converts it into a master-only
(post-merge, source-agnostic) gate that never runs on a PR at all, and nobody is told. Note
the java pipeline is itself orchestrator-path-filtered, so a JDK or base-image change that
moves allocation reaches it only via the daily run.

### Tier 3 — research-shaped, schedule deliberately

#### 17. N parallel instances on one host — **research, 1-2 weeks**

The real question behind "per test method on a laptop across lots of parallel tests". Launch
N in {1, 4, 8, 16, 32} and measure aggregate RSS, thread count, ephemeral-port consumption,
per-instance startup degradation, and per-instance p95 under light load.

**Run it two ways, because the profile has two shapes.** N containers: `availableProcessors()`
is cgroup-aware, so each sizes its pool off its own limit. N **in-JVM** instances in one test
JVM — the `MockServerExtension` case users actually hit — has no cgroup, so every instance
sizes off the whole machine. On a 10-core laptop, 32 instances is 32 x (5 event-loop + 10
action-handler) = **480 threads in one JVM** before any callback pool.

**Also measure the store-sizing order dependence.** `maxLogEntries` and `maxExpectations`
derive from free heap *at the moment of the call*, so in one JVM the first instance sizes off
a mostly-empty heap and the thirtieth off a full one — identical instances get different
capacities depending on test order. A plausible source of "flaky only on CI" reports, never
looked at. **And measure `devMode`** as the control arm: it exists for this profile and
nobody knows what it saves. If it saves a lot, the JUnit integrations should probably default
to it — a shippable outcome rather than a table.

**Measured 2026-09-17 (14-core laptop, `-Xmx2g`, 8.0.1-SNAPSHOT jar in-JVM /
`mockserver/mockserver:7.6.0` containers). Harnesses: `scripts/perf/InJvmParallelBench.java`
(in-JVM shape) and `scripts/perf/parallel_instances.py` (container shape).** Both shapes ran the
full {1,4,8,16,32}; 32 fit comfortably. Findings, several correcting the plan's own arithmetic:

- **The "480 threads" figure is a warm-pool ceiling, not the steady state — measured 222 live at
  N=32 in one JVM (6.7/instance, ~7× fewer).** Thread pools start LAZILY. The action-handler pool
  (`Scheduler`, a `ScheduledThreadPoolExecutor(actionHandlerThreadCount())`) starts **zero** core
  threads until a delayed/callback response schedules a task — verified by a positive control: 0
  scheduler threads under plain load, exactly **14** (`max(5, 14 cores)`) after 60 concurrent
  *delayed* responses. Netty's boss/worker `NioEventLoop` threads also start per registration, so
  the worker group shows ~2 alive of its 5 sized. Per-instance sized ceiling on this host is
  5 boss + 5 worker + 14 scheduler = 24 (the plan's 15 omits the boss group and assumes 10 cores);
  live under light load is ~5 server threads/instance. So the 480 ceiling is reachable only when
  every instance concurrently runs delayed/callback responses — not on a typical mock-only suite.

- **Store-sizing is worse and different from the hypothesis: the capacity is FROZEN at first read
  for the whole JVM, not recomputed per instance.** `readPropertyHierarchically`
  (`ConfigurationProperties.java:6531-6543`) caches the computed default string on first read and
  returns it forever. So all 32 instances get an *identical* `maxLogEntries`/`maxExpectations` —
  but that shared value is a lottery set by how full the heap was when the FIRST store was
  constructed. Proven with the harness's `--preconsumeHeapMb` flag (which holds heap before the
  first store read): the frozen `maxLogEntries` fell as pre-consumed heap rose — e.g. 100000 (0 MB)
  → 45957 (300 MB) at `-Xmx1g`, and down to ~8045 under a tighter `-Xmx512m` + pre-consumption — a
  multi-fold swing purely from heap-at-first-read, while a *later* 300 MB allocation did NOT change
  it (the freeze). The committed harness's per-instance `maxLogPre`/`maxLogPost` prove the freeze
  (constant across instances); `--preconsumeHeapMb` reproduces the swing across separate runs. That
  is the real "flaky only on CI" mechanism — a suite whose first MockServer start happens after a
  heavy fixture silently gets a tiny store for *every* instance, so log/expectation eviction (and
  "absence cannot be proven" verify failures) appears only on that machine/order.

- **`devMode` saves ~2 MB heap per instance and, more importantly, kills the freeze lottery.**
  In-JVM heap-used at N=32: 117 MB (default) → 52 MB (`devMode`), a 56% reduction scaling linearly
  at ~2 MB/instance; threads and startup are unchanged (it only fixes store sizes to 1000/1000).
  The decisive benefit is determinism: `devMode` sizes stores at a fixed 1000/1000 with no heap
  derivation, so it removes the order-dependent freeze entirely. **Two recommendations follow, both
  separate product units with their own review (not implemented here — this unit is measurement):**
  1. **Default the JUnit integrations (`MockServerExtension` / `MockServerRule`) to `devMode`** — to
     make test-store capacity deterministic (killing the flakiness above), with a secondary
     ~2 MB/instance saving. But "a suite that needs more can opt out" is **not sufficient on its
     own**, because exceeding the cap fails *silently*: the ring overwrites and a later verify
     quietly fails with no error. So a default change **must** be paired with a store-construction
     log line stating the effective `maxLogEntries`/`maxExpectations` and that `devMode` set them,
     so a suite that outgrows 1000 finds out from a log, not from a flaky verify.
  2. **The cleaner underlying fix is to stop caching *derived* defaults.** `devMode` only *masks*
     the freeze; the actual defect is that `readPropertyHierarchically` caches heap-based computed
     defaults as though they were resolved configuration. Caching only *explicitly-set* values
     (env var, properties file, system property) and never derived defaults would fix the freeze
     for ordinary users who never touch `devMode`.

- **The two shapes differ mostly in baseline replication, not per-instance pool sizing.**
  Containers: `availableProcessors()` IS cgroup-aware — verified `--cpuset-cpus=0,1` makes the JVM
  report 2 processors (vs 14 unpinned), so each container caps `actionHandlerThreadCount` at
  `max(5,2)=5` off its own limit. But because that pool is lazy, the practical cost difference is
  the JVM **baseline**: each container is a full JVM (**process RSS** ~175 MiB, ~14 threads,
  ~520 ms cold start, all flat regardless of N — cgroup-isolated), so N containers cost N
  baselines while the in-JVM shape shares one. **Container aggregate RSS therefore grows ~N× faster
  than the in-JVM footprint** — 2806 MiB (N=16) and 5330 MiB (N=32) of process RSS, versus the
  in-JVM shape's single shared baseline. A clean side-by-side *multiplier* is deliberately NOT
  claimed: the trustworthy in-JVM memory figure is **heap-used** (52→117 MB across N, monotonic),
  which is not the same quantity as container process RSS (that includes metaspace, code cache,
  thread stacks and Netty direct buffers); and the in-JVM **process-RSS** samples from `ps` are a
  post-`System.gc()` point read that came back non-monotonic (460 MiB at N=16, 394 MiB at N=32),
  so they are not trustworthy enough to anchor a ratio. Thread counts ARE like-for-like: 448
  (container, N=32) vs 222 (in-JVM) — ~2×, again the replicated per-JVM baseline.

- **Ephemeral ports and per-instance startup are non-issues at these N.** In-JVM held TCP sockets
  scaled linearly to 176 at N=32 (~5.5/instance) — no exhaustion risk. Per-instance startup did
  not degrade with N (both shapes: cold first launch ~520-630 ms dominated by one-time class
  loading, warm launches ~7-15 ms flat through N=32). Per-instance light-load p95 is reported as a
  distribution: in-JVM per-instance p95 rose from ~1 ms (N=1) to a median 2.6 ms / max 3.3 ms at
  N=32, with a fat tail (p99 max 23.5 ms) — reported per-instance, never as a mean.

These are laptop-profile research numbers, not daily-gated metrics; the harnesses write their own
`--out` JSON and do **not** emit into the daily perf result. To wire a `.laptop` parallel block
into `perf-test-compare.sh` later, the existing `laptop.*` leaves `ready_ms` / `cold_ready_ms` /
`rss_mb` / `threads` cover the reused metrics, but new **notify-only** wildcard budgets would be
needed first (compare is fail-closed on unbudgeted metrics): `laptop.*.heap_used_mb`,
`laptop.*.threads_per_instance`, `laptop.*.total_threads`, `laptop.*.tcp_sockets`,
`laptop.*.load_p95_median_ms`, `laptop.*.load_p99_max_ms`, `laptop.*.agg_rss_mb`,
`laptop.*.rss_mb_per_container`, `laptop.*.threads_per_container` (all `dir:"up"`, `gating:false`).

#### 18. req/s per core for the serving path — **research, about a week**

Pin the SUT to C in {1, 2, 4, 8, 16} cores and run the ladder at each, recording
`peak_achieved_rps`, `healthy_ceiling_rps` and `rps_per_core`. **Prerequisite easy to miss:**
at C = 16 the SUT wants more cores than the client has. On a 16 vCPU box you cannot pin 16 to
the server and still have a k6. Either the top rung moves to a second box or the curve stops
at C = 8 and says so.

**Shipped 2026-09-17.** `.buildkite/scripts/steps/lib/perf-percore.sh` pins ONE SUT to C cores
in {1, 2, 4, 8, 16} with `--cpuset-cpus` (item 17's lever — the JVM's `availableProcessors()`
follows it, sizing `actionHandlerThreadCount()` and its derived pools) and drives the `sweep.js`
ladder against it from a k6 on DISJOINT cores. Per C it records `peak_achieved_rps` (max achieved
over CLIENT-SOUND rungs — client CPU headroom + low error; dropped iterations *with* client
headroom are the server-saturation signal, `server_saturated`, NOT a client limit), the reused
Finding-1 `healthy_ceiling_rps` (each C's sweep is fed to `lib/perf-website-figures.jq` and its
headline read back — not a third copy of the rule), and `rps_per_core = healthy_ceiling_rps / C`.
Behind `PERF_SERVING_PERCORE` (opt-in; it spins a fresh pinned SUT per core-count, so it is
scheduled deliberately, not added to every daily run). Emits `.serving_percore` into `result.json`
+ a `serving-percore.json` artifact; `perf-test-compare.sh` reads `serving_percore.*` NON-GATING on
the FULL baseline, with a `serving_percore_attempted` presence gate (attempted-but-empty → RED).

**Pinning is PROVEN per C** (a one-shot probe container on the SAME image + cpuset prints
`availableProcessors()`; a C whose probe != C fails loud), warm-up is a separate un-measured drive
(the first-rung-vs-second p50 check flags residual warm-up bias rather than averaging it in),
per-rung spread is p50/p95/p99 with MIN_TAIL_SAMPLES suppression, event-log residence
(`maxLogEntries / achieved_rps`) is computed PER RUNG (it lengthens as rps falls), and readiness is
`PUT /mockserver/status`.

**C = 16 stops the curve, and the artifact says so** — on the 14-core measurement laptop it needs
16 SUT + client + reserve cores; it is recorded in `.serving_percore.skipped[]` with a reason,
`max_cores_measured`/`curve_complete_to_16` make the limit explicit, and compare surfaces
"curve stops at C=8" in the annotation body. **But the more important limit is MEASURED, not
skipped, and attributed with SUT-side CPU data rather than asserted:** the harness samples BOTH the
k6 client and the SUT container CPU each rung. On a single Docker-Desktop-for-Mac box the peak
throughput is flat at ~14.4k rps regardless of SUT cores (peak 14.7k C=1, 14.4k C=2, 14.5k C=4,
14.4k C=8), and the SUT-CPU series shows WHY: at C = 1 the SUT saturates its core (peak-rung SUT
CPU 101 % of its 100 % pin → `peak_limited_by: server`), but as cores grow the SUT tops out at a
FALLING fraction of its pin — 60 % (C=2), 45 % (C=4), just **20 % at C=8** — i.e. the 8-core server
sits ~80 % idle while throughput does not rise (`peak_limited_by: load_path_or_virtualization`).
So the server demonstrably has spare CPU it cannot use. That rules out MockServer being **CPU**-bound
at C >= 2, and strongly indicates the binding constraint is the containerised load path (k6 + the VM's
virtualised network). Be precise about what is and is not established: a server-internal NON-CPU
bottleneck — event-log disruptor backpressure, lock or stage serialisation, a GC-stall pattern — would
also present as low SUT CPU with flat throughput, and the CPU series cannot exclude it. The C = 1
datum weakens that alternative considerably (a server-internal serialisation cap would show below
100 % CPU even at C = 1, and it sits at 101 %), but does not eliminate it. The distinction matters
because the remedy differs: a load-path cap goes away on a bigger box, a serialisation cap follows
you there. Only **C = 1 is a clean server-side figure** here (1 core ≈ 8k healthy / 14.7k peak of
trivial `GET /simple`); C >= 2 is limited by something outside MockServer's CPU, and the re-run on a
real box — which records `peak_limited_by` per C — is what will say which. The clean per-core serving curve therefore needs a DEDICATED load
generator on a separate host (native-Linux, >= 16 cores) — the plan's "second box" fallback, needed
from C = 2 upward on this box, not only at C = 16. The harness is correct and re-runnable there
unchanged (env-overridable ladder/cores), and it now records `sut_cpu_frac_of_pin`, `sut_cpu_peak_pct`
and `peak_limited_by` per C so the next run on a real box states which side bound each rung. What
stopped short is the measurement box, not the method.

#### 19. Close the loop from S3 back to the website

*Cost: 2-3 days. Where: tail of the daily run, non-gating.*

Regenerate the chart data and **open a pull request** — deliberately not a direct commit,
because the figures are a customer-facing claim and a human should look at a 20% swing before
it ships. Trigger only when the committed figure is more than 30 days old **or** has moved
more than 10%, so it does not open a PR every day. A stale page then becomes an open PR
rather than invisible rot. Requires item 0 for the provenance line. Finding 3 is now resolved
(2026-09-16), so per-behaviour percentiles are publishable — but publish figures from the
**fixed** `regression.js` only, never the pre-fix rig-artefact numbers. **Publish
`healthy_ceiling_rps` with its latency, not `peak_achieved_rps` alone** — see Finding 1.

#### 20. HTTP/3 and QUIC — **research**

- **20a (do this):** a JMH benchmark of the HTTP/3 request bridge, compared **in the same
  run** against the HTTP/2 equivalent. In-process, deterministic, no driver needed, and it
  answers "is the QUIC path allocating an order of magnitude more per request" — the question
  a central deployment needs answered.
- **20b (defer):** an end-to-end HTTP/3 throughput ladder. There is no HTTP/3 client in k6, so
  this needs a purpose-built driver — most of the cost and most of the risk. Only worth it
  once 20a shows something, or a user reports a problem.

#### 21. Connection-scaling ceiling — **research, lowest priority**

Maximum concurrent established connections before latency degrades, separately for HTTP/1.1
keep-alive, HTTP/2 and TLS (session state is the interesting axis). k6 is not suited to
holding tens of thousands of idle connections; likely a purpose-built driver. Schedule after
everything above.

## Proving a performance change is still correct

**A performance PR whose only evidence is a faster number must not merge.** The benchmark is
the *motivation* for a change, never the verification of it.

This is the more dangerous half of the programme, and the reason is structural: an
optimisation is a behaviour change that **arrives with a success signal already attached**. A
feature change lands with no green light and attracts scrutiny until it earns one. An
optimisation lands with a chart showing it worked, and attention stops there.

The repo has already paid for this at scale. `Http2FlowControlBodies` records it in its own
javadoc: **four HTTP/2 defects — #2641, #2667, #2669, #2683 — all shipped while every HTTP/2
test was green**, for one structural reason. Every test used a body smaller than the
65,535-byte flow-control window, including one named
`shouldForwardHttp2RequestWithLargeBodyViaConnectProxy` at 50,000 bytes. The failure mode was
a silent hang, not a wrong answer.

### The evidence standard

Three things must hold. The second is the one that gets skipped.

**1. The full integration suite passes — not the unit suite.** `mvn test` **excludes**
integration tests here: surefire carries `<exclude>**/*IntegrationTest.java</exclude>`
(`mockserver/pom.xml:1622`) and failsafe picks them up separately (`:1654`). For
`mockserver-netty` that is roughly **1,219 tests under `test` against 2,275 under `verify`**
(2026-09-16). A perf change verified with `mvn test` has skipped nearly half the tests and
essentially all of the ones that drive a real socket. **"Tests pass" is not a claim; "`mvn
verify` passes on `mockserver-netty`" is.**

**2. A differential check: identical inputs produce identical outputs through the old and new
path.** Not "the tests still pass" — "the output is the same". Compare byte-for-byte: response
bytes, header order, status, trailers, observable frame boundaries, the serialised event-log
entry. This is the only evidence that catches drift nobody anticipated. Where a true A/B is
impractical, pin a golden corpus before the change and diff after.

**3. The correctness test is shown capable of catching the break.** Invert the repo's
standing discipline: the fix *is* the optimisation, so **deliberately introduce the hazard**
— skip an invalidation, drop a `release()`, reuse a buffer without clearing, remove the type
guard — and confirm something goes red.

### Hazard classes

Each has occurred in this repo.

| # | Hazard | Why it evades a benchmark | Required evidence |
|---|---|---|---|
| 1 | **Reuse and pooling** | Cross-request contamination needs concurrency; throughput is indifferent to *whose* bytes came back. **A security failure, not only a correctness one** | Concurrency test with **distinguishable per-request payloads** asserting zero cross-talk, at real concurrency |
| 2 | **Caching** | A cache is fastest and most wrong when it never invalidates. Hit-path tests get faster; staleness is invisible | The **invalidation path** tested — mutate the underlying thing, assert the cached view updates. A cache tested only for hits is a bug with a benchmark attached |
| 3 | **Reference counting** | A leak shows as growth over hours; a double-release as corruption under load | The suite run with leak detection at `paranoid` — **now wired and gated** (`a1158a104`), which found a shipped bug on its first run |
| 4 | **Laziness and init order** | A cold start is single-threaded; the race needs concurrent first use. The **dynamic CA race** presented as a ~10% launcher flake and was a *shipped TLS race* | **Concurrent** first-use, repeated. Treat an intermittent failure introduced by a lazy-init change as a shipped race until proven otherwise |
| 5 | **Concurrency and pool changes** | A deadlock under recursion is invisible to a load generator that never recurses | The **deadlock argument stated in the PR**, plus a test under contention. `localCallbackExecutor` is deliberately unbounded because a bounded pool self-deadlocks on a blocking loopback callback — the javadoc is all that stands between the next optimiser and that bug |
| 6 | **Topology changes** | Handlers attached to the wrong thing still forward traffic; throughput is unaffected | Assertions on the **type**, and both parent and child cases. The #2669 lesson: guard on `Http2StreamChannel` **type**, not `parent() != null` — on an HTTP/1.1 socket `parent()` is the server *listening* socket, so such a guard **shuts the whole server down on the first concurrent stream**. It looks right and benchmarks clean on one connection |

A seventh, live in this document: **init-order changes alter heap-derived capacities.**
`maxLogEntries` and `maxExpectations` derive from free heap at call time, so an optimisation
that moves *when* initialisation happens changes store sizes without touching store code.
Assert the derived capacities, not just behaviour that happens to fit inside them.

### The benchmark-shaped correctness loss

An optimisation can be **correct on the benchmark's inputs and wrong on real ones**, because
benchmark fixtures are chosen for convenience and stability — the two properties that make
them unrepresentative:

- **Sub-window bodies hid the flush family** for four releases.
- **All-ASCII fixtures hid a double-encoding defect.**
- **A 7-byte payload left frame-length bytes zero**, indistinguishable from default init.
- **2026-09-16, in this very programme:** a new assertion pinning the proxy-auth encoder
  against its predecessor **passed against a deliberately wrong encoder**, because the ASCII
  test credential's base64 contained no `+` or `/` — the only two characters where the
  standard and URL-safe alphabets differ. The fixture could not distinguish the two encoders
  it existed to distinguish. Only the degrade test found it.

So the corpus must be **adversarial in exactly the dimensions the change touches**. Buffering
or flushing: cross buffer and flow-control boundaries (`Http2FlowControlBodies.Size.OVER_WINDOW`
exists for this and fails the build if shrunk to the window). Encoding: non-ASCII, and the
characters where alphabets differ. Framing: empty, one-byte, boundary-minus-one, boundary,
boundary-plus-one, maximal. And **the error paths** — an optimisation that skips work on
success frequently skips cleanup on failure.

State in the PR which dimensions the change touches and which corpus arms cover them. If the
answer is "the existing fixtures", that is the answer that produced four shipped HTTP/2
defects.

### Where this plugs into the gate chain

| Evidence | Where | Why there |
|---|---|---|
| `mvn verify` on affected modules | **Per merge, blocks the PR** | The baseline, and the only thing that makes "tests pass" mean anything |
| Leak detection at `paranoid` | **Per merge**, gated at `verify` on `mockserver-netty` | Deterministic; a leak found a week later is a bisect across a week |
| Allocation-per-op budget | **Per merge** (item 16), unconditional step | Cheap, deterministic, attributes to one commit |
| **Differential corpus** | **Required in the landing PR as evidence**, not per-merge CI | Too slow for every merge, and meaningful only against the specific old path being replaced — which exists only in that PR |
| **Negative control** | **Required in the landing PR** | Nobody can automate "prove this test can fail"; it is a one-time act per change, and it is the act that converts a test into evidence |
| Adversarial corpus arms | **Required in the landing PR** | Which dimensions matter depends on what the change touches; no CI step can infer that |
| Deadlock argument (class 5) | **Required in the PR description** | An argument, not a test. Writing it down stops the next person undoing it |
| Hazard-class identification; type assertions; invalidation paths | **Review checklist** | Judgement, not automation. Cheap to ask, expensive to omit |
| Does the win survive contact | **Daily perf run**, after merge | The *last* step, not the first |

**Two rules, stated so they are not re-argued:**

1. **A performance PR states its hazard classes.** If the author cannot name which of the six
   the change belongs to, it has not been understood well enough to merge. Belonging to none
   is legitimate and common — say so, and that is the end of it.
2. **The benchmark result belongs in the PR body under a heading saying it is motivation, not
   verification.** The failure this prevents is not that people lie about testing; it is that
   a green chart *feels* like completion.

## Feature surfaces the first audit excluded

The first audit deferred HTTP/3, LLM mocking, async messaging, WASM rules and the dashboard
as "out of the mandate's framing". That was not defensible — the framing is a *deployment
profile*, not a feature list, and all five run inside the profile it names. But measuring all
of them is not defensible either; this programme already has more wall-clock measurements
queued than one serialised box can carry.

**Added**, with rationale above: LLM/SSE streaming (item 12), clustered state (13), TLS
handshake (14), four near-free feature arms (15), and HTTP/3 scoped down to an in-process
benchmark (20a).

**Deliberately excluded, with reasons**, so nobody re-litigates:

- **AsyncAPI broker mocking.** The transport is a broker the user supplies, so end-to-end cost
  is dominated by the broker client and is not attributable to MockServer. No existing harness
  shape to reuse. **Revisit when a user reports a problem**, or when a broker-independent
  in-process benchmark becomes cheap.
- **WASM rule bodies** as a *continuous* measurement — included in the **quarterly deep
  review** as a JMH benchmark only. A WASM body is opt-in per expectation and on no default
  path, so a regression there cannot affect a user who is not using it.
- **OpenTelemetry export** as a separate item — folded into a **once-per-release
  optional-feature ledger** (on versus off, same run, same load). Off by default, so it cannot
  regress the default path.
- **Dashboard WebSocket fan-out** as standalone — folded into item 12's within-run A/B:
  measure serving p95 with 0, 1 and 10 connected dashboards during a run already happening.
  The classic "the observability tool destroys the thing it observes" risk deserves one
  number, not a harness.
- **Expectation persistence as an ongoing write cost.** Its startup half is covered by 8c,
  which is where the user-visible cost is.
- **gRPC streaming.** It shares the HTTP/2 stream machinery item 11 already measures, so item
  11 is the cheaper first look. Add a gRPC arm only if item 11 finds something.

## Sequencing

```mermaid
flowchart LR
  i0["0. self-describing results"]
  i1["1. fail build on gating regression
  LANDED 2026-09-16"]
  f3["Finding 3 diagnosis
  DONE 2026-09-16 (fixed)"]
  done["2,3,4,5,6,7,7b LANDED"]
  budgets["perf-budgets.json
  committed absolute floors"]
  i15["15. cheap feature arms"]
  i16["16. widen allocation backstop"]
  i8["8. laptop startup and footprint"]
  i9["9a. proxy and CONNECT"]
  i14["14. TLS handshake"]
  i10["10. weekly soak plus verify cost"]
  i11["11. h2 memory axis"]
  i12["12. LLM SSE streaming"]
  i13["13. clustered state"]
  i19["19. loop back to the website"]
  rel["release preflight gate"]
  i0 --> i19
  i0 --> budgets
  f3 --> i9
  f3 --> budgets
  i1 --> budgets
  budgets --> i16
  i15 --> i16
  done --> i10
  i9 --> i14
  i9 --> i12
  i12 --> i13
  budgets --> rel
  i16 --> rel
```

### The first fortnight

1. **Item 0** — the `config` block. Half a day. Everything that compares, ratchets or
   publishes depends on it.
2. ~~**Item 1** — the webhook.~~ **DONE (2026-09-16).** No webhook: the decision was that a
   failing pipeline is the notification. The compare step now fails the build on a *gating*
   metric (JMH alloc/time, `forward.error_rate`); everything else stays notify-only until it
   earns a budget. It was the only item that made any other item matter — a green build on a
   real regression — and it now holds.
3. ~~**Finding 3 diagnosis.**~~ **DONE (2026-09-16).** Diagnosed as a client-side
   VU-allocation connection storm and fixed in `regression.js` (stagger + equal VU pool +
   warm-every-path + settle exclusion), verified to still catch a real slowdown. This
   unblocks the D2 latency budgets, item 5's resolution and item 9a's design.
4. **Item 15c** — JMH fork count. One line, and it changes the dispersion every future timing
   budget is derived from, so it must land before any budget is derived.

### The first quarter

- **Weeks 3-4:** `perf-budgets.json` extracted, existing floors migrated unchanged, the file's
  last-changed commit named in the annotation. Items 15a, 15b, 15d. The baseline-freshness
  assertion in `pipeline-infra.yml`.
- **Weeks 5-7:** item 8. Item 9a with item 14 sharing its containers and run. Item 16 once 15b
  has landed.
- **Weeks 8-10:** item 10 scheduled out of the daily's slot. Item 11. Start the eight-week soak
  window — it will not produce a budget inside the quarter, and that is fine as long as the
  clock starts.
- **Weeks 11-13:** item 12. Item 19 once item 0 has enough history for a meaningful provenance
  line. The release-preflight gate — highest leverage in the programme, needing only the budget
  file and one S3 query.
- **Deferred past the quarter:** 13, 17, 18, 20, 21.

**First budget attachable:** `peak_achieved_rps`, about three weeks after landing (10 daily
runs). **Last:** item 10's soak metrics, about ten weeks after landing. Nothing here produces
a tight control loop inside a month, and a plan implying otherwise is lying about the
statistics.

## Cost

**Every figure is an estimate from reading configuration, not a measurement** — which makes
step zero of this section "measure how long the current daily chain actually occupies the
box", because nobody has.

The `perf` queue is one on-demand `c5.4xlarge`, `min_size = 0`, `max_size = 1`. List price is
about **USD 0.68/hour**; confirm before quoting. Storage and boot add perhaps 20%.

| Workload | Box-min each | Per month | Box-hours | USD |
|---|---:|---:|---:|---:|
| Today's daily chain — **unmeasured, estimated** | ~60 | 30 | 30 | ~20 |
| Item 2, extended sweep with 30 s steps above 16k | +4 | 30 | 2 | ~1.4 |
| Item 3, `forward.js` | +3 | 30 | 1.5 | ~1.0 |
| Item 6, bounded heap | +0 | 30 | 0 | 0 |
| Item 8, startup matrix | +18 | 30 | 9 | ~6.1 |
| Items 9a + 14 sharing one run | +8 | 30 | 4 | ~2.7 |
| Item 11, connections x streams | +8 | 30 | 4 | ~2.7 |
| Item 12, streaming | +10 | 30 | 5 | ~3.4 |
| Item 15, all four arms | +5 | 30 | 2.5 | ~1.7 |
| Item 10, 2-hour weekly soak | +120 | 4 | 8 | ~5.4 |
| Items 17, 18 research, occasional | +90 | 1 | 1.5 | ~1.0 |
| **Total** | | | **~68** | **~USD 45** |

Per-merge JMH runs on the `default` Spot queue and is dominated by the Maven build, not the
benchmark — **under USD 2/month**.

**The conclusion changes the ordering, and not as the open question assumed:**

- **Money is not the constraint.** The whole programme lands around USD 45-60/month on a box
  costing USD 20 today. Do not sequence this around dollars.
- **Serialisation is the constraint.** `max_size = 1`. At ~68 box-hours the box is busy about
  9% of the time — comfortable until a 2-hour soak occupies a contiguous block and any daily
  run behind it waits. **Item 10 must be scheduled into a slot the daily does not use**, and
  that should be stated in terraform next to the schedule so the next person does not undo it.
- **Share runs rather than adding steps.** 9a and 14 use the same containers. 12 and the
  dashboard A/B share a run. 15's arms attach to runs that already happen. Item 8 is the only
  genuinely additive step, because launching a container ten times is inherently serial.

## Cadence: is daily right at all?

Daily was inherited, not chosen. It deserves an argument, because the box serialises and
"daily" is a strange unit for a project whose merges arrive in bursts.

**Against daily.** A daily run bundles every merge in a 24-hour window, so a flagged
regression starts as a bisect across everything that landed — precisely how performance work
gets abandoned. On a quiet week it measures the same commit repeatedly. The commit guard
already recognises this: it dispatches only if master moved, so "daily" is really "at most
once per day, if something changed".

**For daily, which wins.** The repo chose **median plus MAD over the last ten runs**. That
method needs run density. At a per-release cadence — perhaps monthly — ten runs is most of a
year, and a budget derived from a window that wide is derived from a different codebase and
probably different hardware. **The statistical method the repo already committed to requires
a cadence faster than the release cadence.** A second reason: a daily wall-clock run is the
only way to learn the *noise* of a new measurement, and every item here runs notify-only for
ten runs before getting a budget. Ten days is tolerable; ten releases is not.

**So: keep daily, but stop pretending one cadence fits everything.**

| Trigger | What runs | Why |
|---|---|---|
| **Per merge to master** | Allocation per op, deterministic counters — thread count, class-load count, image size | Cheap, hardware-independent, collapses the bisect surface to one commit. **This is where attribution is solved, not by changing the wall-clock cadence** |
| **Daily, commit-gated** | Everything wall-clock | The median-plus-MAD method needs the density; the commit guard already suppresses no-op days |
| **Weekly** | Soak | Too long to serialise daily; its metric is a slope needing hours |
| **Per release, and per significant change** | The deep research set, the optional-feature ledger, the quarterly profile diff — **plus the release-preflight gate**, where teeth belong | These answer sizing questions, not regression questions. A sizing curve goes stale when the architecture changes, and a release is a good proxy |

**"Significant change" concretely**, so it is not a judgement call every time: a JDK or Netty
bump, a change under `mockserver-netty/.../netty/` or `mockserver-core/.../mock/` exceeding
some size, a change to any default in `ConfigurationProperties`, or a changelog entry
mentioning memory or throughput — the 8.0.0 multiplex entry being the worked example of one
that should have triggered a deep run and did not.

**Honest summary: daily is right for the trend line and wrong for attribution, and the fix for
attribution is per-merge deterministic counters, not a different clock.**

## Acceptance criteria

Every item's definition of done has the same three parts, and the third is the one that is
usually skipped.

1. **The measurement exists** — a named field in a named artefact, with a stated unit and
   method.
2. **It has history** — at least 10 runs for daily measurements, 8 for weekly, before any
   budget is attached. Notify-only until then, no exceptions. **This is exactly the line item
   1 enforces mechanically:** a metric carries `gating: false` in the compare `metrics()` jq
   until it has that history and a budget derived from it, at which point the owner flips it to
   `gating: true`. Only the JMH allocation/time metrics and `forward.error_rate` gate today;
   everything else is notify-only awaiting its ten runs.
3. **It has been demonstrated capable of failing** — a specific, recorded negative control.
   Not "the step exists and is green", but "on this date, with this deliberate change, this
   check went red, and here is the evidence."

A fourth, added after the pipeline-gating error: **a gating claim names the pipeline file, the
step label and the branch condition.** "Merge-blocking" asserted without those three is an
inference, and this programme has been burned by exactly that inference once.

| Item | Negative control that must be executed and recorded |
|---|---|
| 0. self-describing results | **Done** — control found the check FALSE-GREEN, then found the defect was LIVE, then fixed both. (a) The verbatim resolution snippet was driven with a fake `curl`: unreachable IMDS stored `instance_type="unknown"` at exit 0; a mangled 200 (`<html>…502 Proxy Error…</html>`) was stored verbatim at exit 0. Added shape validation + fail-closed. (b) Turning the reviewer's IMDSv2 concern into a measurement found the real finding: the perf ASG launch template `lt-01f1ac1070d561f50` sets `HttpTokens=required` (IMDSv2 mandatory), so the unauthenticated GET always 401'd and **every stored baseline point carries `instance_type:''`** (confirmed on `runs/master/2026-09-10T04-15-15Z__42193bc4f6.json`) — the `instance_type:""` worked example, live since inception, invisible because it wrote a falsy value at exit 0. Fix: IMDSv2 two-step (`PUT /latest/api/token` → metadata GET with `X-aws-ec2-metadata-token`), reviewer's tightened regex `^[a-z][a-z0-9-]*\.(nano\|micro\|small\|medium\|large\|metal\|[0-9]+xlarge)$`, fail-closed with distinct token-PUT-failure vs metadata-garbage diagnoses, `PERF_INSTANCE_TYPE` override, `instance_type_source` recorded. Proven by execution against a dispatching curl stub: token-PUT-fails→`exit 1` ("token PUT returned nothing / not on EC2"); token-OK+metadata-mangled→`exit 1` ("token acquired but metadata GET returned '<html>…'"); token-OK+non-2xx→`exit 1`; override→`m5.large` `declared` exit 0 in both failing cases; happy path (token→`c5.4xlarge` `observed` exit 0) is **stubbed, not observed** — the next real perf run confirms the live handshake. History-comparability judgement (hardware was constant `c5.4xlarge`, on-demand, min=max=1): the series stays comparable, field populated from here on, no re-baseline — see note below the table |
| 1. fail-on-regression | **Done** — a flagged gating metric exits non-zero (red build); a flagged notify-only metric exits 0 with the informational annotation; the invalid-run and warming-up paths still exit 0. All four cases proven against fixtures |
| 2. sweep | **Done** — a synthetic degraded value flags; 36,324 reported where saturation would report 16,000 |
| 3. `forward.js` | **Done** — pooling disabled gave error rate 0.997, k6 exit 99 |
| 4. AppCDS | **Done** — zero-byte and garbage archives both fail, through different JVM code paths; `-Xshare:auto` serves 200 with the same garbage |
| 5. `throughput_rps` | **Done** — live `dropped_iterations` 3-10 and delivery ratios 0.9985-0.999 recorded |
| 6. live set | **Done** — flags at 950 MB against a ~705 MB baseline |
| 7. validity | **Done** — `valid:false` and an absent block both refuse; zero-heap now refused rather than baselined |
| 7b. leak detection | **Done** — 50 deliberately leaked buffers fail the build; the same leak is invisible on unmodified master |
| 8. startup | **Done** (2026-09-18, arm64 laptop) — an AppCDS-disabled image (baked `/mockserver.jsa` overwritten with 4 KB of garbage; runtime `-Xlog:cds` confirms `bad magic number` → `Unable to use shared archive`, and `-Xshare:auto` still serves 200) raised the `laptop.docker_ready.ready_ms` median-of-9 (1 warm-up discarded, `bench_laptop.py ready`, readiness = `PUT /mockserver/status` 200) from **394.9 ms** AppCDS-on (min 380/max 404, tight) to **680–726 ms** AppCDS-off (+64–83%; every off launch ≥ 641 ms exceeded every on launch ≤ 433 ms). Feeding perf-test-compare.sh's verbatim compare jq the 9 real on-launches as baseline (median 395 → notify-only threshold **493.75 ms** = median×1.25, since `laptop.*.ready_ms` is floor:null / min_pct:0.25) flags the 680 and 726 ms off heads `regression:true` and the 394.9 ms on control `regression:false`; a synthetic 707/708 ms boundary confirms the threshold governs exactly. NOTIFY-ONLY: it flags loudly (`nongating_count` 1) but does NOT fail the build until the metric earns ≥10 runs of history and a MAD floor — so "red" here is the flagged-regression annotation, not a non-zero exit. What is proven is ENVIRONMENT-INDEPENDENT — the check logic firing when a >25% relative degrade crosses a floor:null threshold that rides only the relative move, not any box-calibrated absolute; NOT proven from here is that the perf box's own numbers cross (its threshold is likewise relative, so the ~34% distroless-JDK25 loss the startup doc records also clears the 25% band). Local absolutes (395/680 ms) differ from the shipped image's ~566/855 ms because these are full `eclipse-temurin:21-jdk` images built from the `8.0.1-SNAPSHOT` fat jar, not the jlink-trimmed distroless JDK25 artifact. One OFF median of 1705 ms during a load-6.1 spike was discarded as noise; clean passes ran at load ~4.2–5.0 |
| 9a. proxy | **REFUTED — the control text describes something that cannot happen** (2026-09-18, x86_64 laptop, pre-built `8.0.1-SNAPSHOT` fat jar; two local MockServer JVMs — SUT forward proxy :1080, upstream :1090 — driven by curl; Docker up but not needed). Disabling forward pooling does **not** move the CONNECT behaviour, because the CONNECT tunnel is **architecturally unreachable from the forward pool**: `RelayConnectHandler.channelRead0` builds its own `new Bootstrap().connect(remoteSocket)` per tunnel and never touches `NettyHttpClient`/`HttpForwardConnectionPool`; `mockserver.forwardConnectionPoolEnabled` governs only the absolute-URI/HTTP-forward path. Measured (distinct SUT→upstream TCP connections — a **pool-reuse count**, load-independent per this plan's own anti-flake note; timing deliberately NOT quoted, the box was under a concurrent Maven `verify`): absolute-URI arm N=150 → pool ON **1** connection (TIME_WAIT-exact), pool OFF **150**; CONNECT arm same continuous both-column sampler → pool ON **~30** vs pool OFF **~49** (both sampler lower bounds, same order — **unmoved**), while under that SAME pool-ON run the absolute-URI arm was **1–2**. A future reader must NOT re-attempt this control and conclude the proxy is broken on seeing the same non-movement. **(2) The pooling lever IS guarded — just on the other arm.** The "disable forward pooling" degrade is covered by **item 3** (`forward.js`, absolute-URI path) with a genuinely **gating** metric: `forward.error_rate` **0.997**, k6 **exit 99**, at 1500 rps. So the lever is not unguarded; 9a was pointing at the wrong arm. My 1→150 connection count reproduces item 3's mechanism load-independently. **(3) The proxy.js arms ARE capable of failing (criterion 3), recorded load-independent:** with the upstream killed, BOTH arms' `error_rate` → **1.000** (0/20 got 200) — the check catches a genuine broken-proxy fault. These arms land in `.behaviours` (`forward_absolute_proxy` / `forward_connect_proxy`) under the **notify-only** `behaviours.*` budgets (`error_rate` floor 0.005, `p95_ms`/`p99_ms` floor null, all `gating:false`), so that red is a compare `regression:true` **annotation at exit 0, NOT a build failure**, and is **NOT** the gating `forward.error_rate` (that is item 3's key, not a `.behaviours` key). At proxy.js's own 200 rps the gateable signal does not even move: a pool-OFF CONNECT burst of 3000 at conc 120 (~97 rps) gave `error_rate` **0.0000** (3000/3000 → 200) — only item 3's port-exhaustion regime reddens `error_rate`. **Uncovered residual:** the CONNECT-tunnel forward path has no forward-pool coupling and only notify-only observability; a CONNECT-specific gating guard (relay/upstream-connect failure) remains unbuilt — 9b/9c are the follow-ups |
| 10. soak | Reduce `maxLogEntries` so the ring never fills; the verification-query metric flattens, proving it is sensitive to occupancy |
| 11. h2 memory | **Done** (2026-09-18, arm64 laptop, load avg ~12–34 during the run). This is a **memory** control — per-connection **retained heap** — not a timing one, so a saturated box does not corrupt it (the property the timing controls lacked); relied on explicitly. Independent of the shipped **RSS** comparison and by a **different metric (on-heap, not RSS)**: last **pre-multiplex 7.6.0** vs **first-multiplex 8.0.0** standalone `mockserver-netty` fat jars from Maven Central, run as servers under the **same** JDK (Zulu 21.0.3), **same** `-Xmx512m -Xms512m`, driven by the **same** external h2c client (`Http2ConnectionMemoryBenchmark hold` mode — version-portable raw control-plane HTTP + raw Netty multiplex client, so an identical client hits both versions), log **cleared before each sample** so the delta is connection/channel state not logged bodies, metric = `jmap -histo:live` **Total** (a full-GC on-heap live-set total), `per_conn = (H1_loaded − H0_idle-warm) / C`, **fresh server JVM per rep, 5 reps/shape**. **The per-connection figure DIFFERS — decisively and outside noise.** At the low-noise **100×10** shape (1000 in-flight streams over 100 distinct connections, per-side spread **0.1%**, the 5-rep ranges **disjoint**): **7.6.0 = 179,537 → 8.0.0 = 196,932 bytes/conn, = +17,395 (+9.7%)**. Corroborating shapes: **10×10** 195,705 → 212,376 (**+8.5%**, disjoint); **100×1** (1 stream/conn) 133,638 → 135,316 (**+1.3%**, disjoint but small); **1×1** 280,104 → 273,688 (**−2.3%**, ranges **overlap** → no resolvable difference — expected at C=1, where fixed per-connection-independent cost is charged to one connection, matching the plan's own 1×1 caveat). **Attribution (why this is the multiplex change, not just a two-version diff):** the excess **scales with concurrent streams per connection** — the connection-fixed cost (100×1) barely moves (+1.3%) while the 10-stream cost (100×10) rises +9.7%; decomposed, **~+1,746 bytes per extra concurrent stream** on 8.0.0 vs 7.6.0 (×10 ≈ 17,463 ≈ the observed +17,395/conn), i.e. the cost tracks the **child-channel-per-stream** structure the 8.0.0 migration introduced. **Honest limits:** on-heap **only** — this is why these figures (~135–197 KB/conn) are **lower** than the shipped **RSS** figures (271k → 339k, +24.7% at 100×10), which also count off-heap Netty direct buffers; the two **corroborate direction, not magnitude**. Two versions differ in more than the codec, so "8.0.0 retains ~17 KB/conn more **on-heap** at 100×10" is directly supportable; the per-stream scaling makes the multiplex attribution **strong but not exclusive** (another per-stream 8.0.0 change could contribute). Raw data `.tmp/item11/results.tsv` (40 rows), harness `.tmp/item11/measure.sh`, analysis `.tmp/item11/analyse.py` |
| 12. streaming | **Control was calibrated against a slower server; recalibrated, CI confirmation owed** (2026-09-18). Build 290 read a match-A/B p95 ratio of **1.056** at the committed concurrency 300 — no movement, proving nothing — where the step's own comment recorded 8.0× and 3.9× at that exact setting. Both were true: `3d7a2f9c8` (stop keeping a parsed copy of every logged JSON body) cut retained heap per entry ~7× (429 MB → 61 MB over 20,000 entries; 1,840,013 Jackson nodes → 0), so far less GC competes with the two action-handler threads on one CPU and the knee moved well past 300. **The control was not broken — the product got faster.** Recalibrated by measurement, not guess: on a post-fix image against the same constrained SUT the ratio climbs monotonically (~2.0 at 300, ~2.6 at 600, ~4.5 at 900, **min 6.08** across repeats at 1200) with zero stream errors, zero match errors and full delivery throughout. Default raised 300 → **1200** (`4fbce6d5b`). **The knee is box-dependent** and the figure is deliberately margin-carrying, not exact: the same concurrency 300 read ~2.0 on a laptop against CI's 1.056 on identical code, because a 1-CPU quota buys more real throughput on a dedicated CI core than a contended laptop vCPU — so CI needs MORE concurrency than a laptop for the same ratio, and laptop figures are a lower bound. 1200 survives the worst observed box sensitivity (~3× on CI); 900 would not. **DELIVERED (build 306, 2026-09-19) — and it refutes the extrapolation that set the number.** At concurrency 1200 CI read `match_p95_ratio` **93.8** (match p95 25.514 ms against an idle baseline of ~0.27 ms, matching build 290's 0.263 ms), with inter-token error p99 39.0 ms. The control therefore FIRES decisively and the owed evidence exists. But the prediction was ~3×, derived from the laptop reading ~2.0 where CI read 1.056 at concurrency 300 — i.e. CI was assumed to need MORE concurrency for the same ratio, making laptop figures a lower bound. Wrong: CI has a far **sharper** knee than the laptop, whose own contention flattened its curve, so the same step from 300 → 1200 that moved a laptop 2.0 → 6.08 moved CI 1.056 → 93.8. **Residual:** 1200 sits far past the knee rather than just beyond it, which is a cruder tripwire than designed (a saturated server has little headroom left in which a future regression can show up, and variance at saturation is large). The real CI knee lies between 300 and 1200 and is worth bisecting — but the control is no longer unfalsifiable, which was the thing owed |
| 13. clustered state | **Done** (2026-09-18, arm64 laptop, in-JVM two-node JGroups REPL_SYNC cluster — no Docker). **The check** is the module's in-JVM cluster suite in `mockserver-state-infinispan`, which the java pipeline runs under `clean install`, so a failed assertion is a **red build (surefire exit 1, not retried)** — a real gate, NOT the notify-only k6 clustered A/B in `perf-test-run.sh` (that arm captures `member_count` BEFORE the run, never re-checks mid-run, needs the clustered Docker image, and is notify-only; it is untouched here). New `ClusteredMemberDeathTest` seeds an expectation, a scenario state, a CRUD entity and a bounded-`Times` counter (the `9642abe8e` dedicated replicated cache, consumed once to N-1=4) on node A, confirms REPL_SYNC put a copy on node B, then **stops node A mid-run** (`nodeA.close()`). Recorded on survivor B (verbatim passing asserts, exit 0): JGroups view drops **2→1** (`getMembers().size()==1` — the death is observed); B still serves the expectation, the scenario state, the CRUD entity, and the counter **at 4, NOT resurrected to 5**; a never-seeded id stays absent (anti-vacuity); B still accepts a fresh write/CAS after the peer died. Survival is **ownership-independent by construction** — every cache is REPL_SYNC so each node holds a full replica, the opposite of node-local `evict()` (the `9642abe8e` coin-flip trap); the counter surviving at 4 is the direct proof. NEGATIVE CONTROL (degrade-and-confirm-red): `-Dmemberdeath.degrade=evict-survivor` evicts the survivor's replicas just before the kill so the fleet holds the value nowhere → `survivor must still serve the expectation seeded on the dead peer` fails `expected: <true> but was: <false>`, **surefire exit 1**; without the flag **exit 0**. Full module **118→119** green (`./mvnw -o verify -pl mockserver-state-infinispan`, BUILD SUCCESS). CAVEAT recorded, not a defect: a CRUD namespace cache the survivor never materialised while a source was live comes up EMPTY after the peer dies (REPL_SYNC state transfer needs a live member); the realistic central-deployment model is that both nodes already serve the namespace, so the test materialises it on B pre-kill, matching `ClusteredTwoNodeTest`'s CRUD-visibility test. NOT covered: the perf harness's per-request ratio under member death, and multi-node (>2) or unclean-crash (kill -9 / partition) failure modes — this is a clean-leave, two-node, state-layer control |
| 14. TLS | **Control REFUTED as worded, and replaced** (2026-09-18, build 290). The rate did not move: `handshakes_per_s` read 51.40 (tls13), 51.42 (mtls), 51.40 (jdk/native-absent). It **cannot** move — `proxy.js` drives the handshake arms from a constant-arrival-rate executor at a fixed offered rate (`K6_HS_RATE`, default 50/s), so the column reports what k6 OFFERED, not server capacity, and falls only if the server drops below a modest fixed load. The budget entry behind it (a 25% `dir:down` band) was a gate on a quantity with nothing to say — the same shape as 9a. **What is provider-sensitive is per-handshake COST**: `handshake_p50_ms` 3.992 → 6.02 (**+51%**) and `cpu_ms_per_handshake` 7.133 → 8.951 (**+25%**), both budgeted `dir:up`/0.25 and both shown to flag against a native baseline in a replayed `perf-test-compare.sh`. The +25% CPU move only just clears its band, so 0.25 is the **loosest defensible** figure there until ≥10 runs allow a MAD-derived bound. **Keep-up** moved to the scale-free `delivery_ratio` (throughput ÷ offered, `dir:down`, floor 0.90), which `proxy.js` already computed and compare never extracted: proven at a *different* offered rate (200/s) to flag a 20%-short server the old absolute floor of 45 passed blind. `handshakes_per_s` is demoted to a liveness floor of 1. Landed `6fbd2eb7e` | **Confirmed on a second, clean-tier run (build 306, 2026-09-19):** tls13 p50 **4.234 ms** vs jdk **6.516 ms** (+54%) and cpu_ms_per_handshake 5.396 → 8.333 (+54%), while `handshakes_per_s` read 52.12 vs 52.14 — identical to two decimal places, exactly as the refutation predicts. Two independent runs now agree that the rate column cannot move and the cost columns do |
| 16. allocation gate (master path) | **Done** — executed by accident on 2026-09-17, not staged, which makes it a stronger control than a rehearsed one. Build **2262** of `mockserver-java` (branch `master`, `pull_request: None`, commit `0d1d4f4db`): the `:scales: per-merge allocation gate (item 16)` job **failed exit 1** with `ERROR: allocation gate measured 4 benchmark row(s), expected 3`. Real cause, not contrived: `0d1d4f4db` added a `declareBodyCharset` `@Param` to `ResponseWriteBenchmark` **without pinning it in the gate's `-p` list**, so JMH expanded the axis and the class emitted **2 rows instead of 1** (`declareBodyCharset=false` → 36409 B/op, `=true` → 20121 B/op), giving 4 rows against the pinned expectation of 3. Build **2263** (repair, commit `4cab2a47d`) shows the same job passing **exit 0**. What it demonstrates: the **exact-row-count** assertion fired on a **real surface change, on master** — and **both** offending rows were individually *within* their floor (36409 and 20121 both < `floor=46000`, logged `:white_check_mark:`), so a gate checking only "is each row within its floor" would have passed **green while measuring a different workload than its floor describes**. It also demonstrates the master-path framing above: the gate reddened the master build **after** the offending commit had already merged — a post-merge detector, not a blocker, on the direct-to-main path |
| 16. allocation gate (PR path) | **Done** (2026-09-19, PR #2715, java build 2310). A throwaway branch added ~16 KB/op to the decode path (`new byte[16384]` escaping into a static sink so the JIT cannot scalar-replace it away — a dead local would have made the gate see nothing and *look* like a passing control). The gate failed on the **PR** build, pre-merge: `InboundDecodeBenchmark alloc=53968 B/op floor=47000`, with the other three benchmarks green (`MatchingBenchmark` 1,498,011/1,850,000; `MatchingBenchmark_detailed` 2,096,357/2,650,000; `ResponseWriteBenchmark` 36,537/46,000) — so the probe hit only the path it targeted. **The load-bearing detail is what PASSED:** `:maven: build` and the dashboard gate were both green, making the allocation gate the SOLE blocker. A first attempt was discarded because checkstyle also failed (the probe field was `static` but not `final`, so SCREAMING_SNAKE violated the static-variable rule), which would have left the claim ambiguous — "the PR was blocked" is not "the gate blocked it". PR closed and branch deleted; it was never merged |
| Baseline freshness | **Done** — control found the content check MISSING, then added it. First established by execution that the dedicated watchdog (`perf-baseline-freshness.sh`) keys off producer LIVENESS only and cannot read the object (no perf-bucket S3 on the trigger queue): fed a live+passed producer via fake `aws`/`curl`, it exits 0 regardless of what the producer wrote. Then ran the real `perf-test-compare.sh` against a structurally-valid-but-empty head (`validity.valid:true`, `behaviours:{}`, `peak_achieved_rps:null`) over a 6-run baseline: it exited **0 GREEN** ("No performance regressions", empty table) and would have persisted the empty object — the false green. Fix adds a content-plausibility gate in compare (the reader that HAS the object), independent of `validity.valid`: ≥1 behaviour arm with `0 < p95_ms < 600000`, plus range sanity on `peak_achieved_rps` (`0 < rps ≤ 1e8`) and `forward_guard.error_rate` (`[0,1]`) when present. After: empty head→`exit 1` IMPLAUSIBLE; `peak=-5`→`exit 1`; `forward.error_rate=1.7`→`exit 1`; normal head→`exit 0` GREEN; legitimately-partial (`forward_guard.status:infra_error`, error_rate null)→`exit 0` GREEN (no false red) |

That last row is the one to read twice. A freshness assertion that checks an object's
timestamp passes forever against a producer writing valid empty JSON every day. **It must
assert the newest object contains the expected keys with non-null values in plausible
ranges** — the same plausibility rule demanded of producers, applied to the watchdog itself.

### Note (2026-09-18) — what the first profiled run found, and the three fixes it produced

The diagnostics built for this programme paid for themselves the first time they ran on a
healthy sweep. Build 290 carried tier-2 instrumentation (`PERF_JVM_DIAGNOSTICS=deep`) and its
JFR repository chunks survived the container teardown — the `dumponexit` recording did not,
because the SUT is removed with `docker rm -f` and SIGKILL runs no exit hook, which is why the
repository lives on the mounted volume. The same teardown is why `PrintNMTStatistics`, which
prints at exit, produced nothing: **NMT is unavailable for exactly the death we most want it
for.** Worth fixing separately.

**The load-bearing question was answered.** At the collapse rungs every `jdk.ExecutionSample`
is `STATE_RUNNABLE`, the host sampler shows 5.5–5.8 of 6 cores busy, the worker event loops
record **zero** parks, and socket samples are negligible. So the server is **CPU-saturated, not
blocked and not parked** — which eliminates "add threads" and "find the lock" as directions and
points entirely at cost per request. Note the sampling trap this had to be checked against:
JFR's execution sampler only samples runnable threads, so a parked server looks *idle* rather
than slow, and a hot-methods list alone cannot tell the two apart.

**What got more expensive at the knee was the logging path, not request serving.** Three fixes
followed, each with the profile share it targeted:

| Finding | Evidence | Fix |
|---|---|---|
| Match-failure diffs formatted for **every** field comparison, read or not | `StringFormatter` #1 and #2 allocation sites, ~28–33% of all sampled allocation | `a8898b263` — lazy, snapshotting args to strings at comparison time so a later mutation cannot be reported; 3,486 → 86 B/op on the recording path |
| Event-log consumer re-resolving `logLevelOverrides` per entry | that single thread **2.1% → 22.6%** of runnable samples, peak → collapse | `65392d6d3` — generation-gated memo, invalidated by a token bumped last in `setProperty`/`clearProperty` so it cannot freeze the way `readPropertyHierarchically` once did |
| Every `LogEntry` minting a **cryptographically secure** UUID | ~5% of samples, and the run's **only** material lock contention — 261 contended enters on the `SecureRandom` monitor, concentrated in the collapse minute | `0b9cc71a7` — opt-in non-secure path at the call site; 26 uniqueness-only sites switched, 17 security-sensitive ones (session ids, client ids, keystore names) deliberately left secure |

The GC storm in that window (~290 pauses, ~5.4s stop-the-world, ~9% of wall) is a
**consequence** of the allocation rate, not an independent problem — it nearly stops when load
drops, and the allocation type mix does not change between peak and collapse. So no GC tuning
was done, and none should be until the allocation fixes have been re-measured.

**Two gate gaps surfaced while fixing the above, and they matter more than the fixes.**

1. **The allocation gate never measured the largest allocation source.** `MatchingBenchmark`
   pinned `detailedMatchFailures=false`, the flag gating that path, so neither it nor
   `premerge_alloc.MatchingBenchmark.*` ever executed it. Fixed in `048cff77c`: both arms are
   measured with the param pinned to both values (deterministic, `EXPECTED_ROWS` 3 → 4) and the
   detailed arm carries its own floor — necessarily its own, because the healthy detailed value
   (2,096,344 B/op) sits *above* the base 1,850,000 floor and a shared floor would have failed
   every healthy build.
2. **`ConfigurationCallSiteGuardTest`'s coverage is set by Maven reactor order — still open.**
   It scans compiled class output, and `mockserver-junit-rule` depends on `mockserver-netty`, so
   it builds afterwards: on a clean CI build the junit modules have no classes when the guard
   runs and are silently not scanned. A real violation (`applyDevModeDefault` reading
   `devMode`/`maxLogEntries`/`maxExpectations` static-only) has sat on master since
   `ada0619c2` through many green builds, invisible. Its own sanity assertions
   (`moduleClassRoots > 1`, core and netty scanned) all pass while that is true. **Every module
   downstream of netty is outside its reach.**

That second one is the programme's own pattern turned on its own instrumentation: not a check
that cannot fail, but a check whose **scope** silently excludes what it claims to cover. The
generalisable rule it earns: *a scanning guard must assert what it actually scanned against
what it was supposed to scan, and fail loudly on a gap.* Coverage is a load-bearing property,
and an unasserted one is an assumption.

**The fixes are validated on real hardware, and the collapse is gone (build 306, 2026-09-19).**
The first perf run of the programme to complete every phase, on the **default 2 GiB** SUT that the two
preceding runs died on. The sweep, against the same ladder:

| offered | build 302 (before the fixes) | build 306 (after) |
|---|---|---|
| 16,000 | 15,475 | 15,480 |
| 32,000 | 26,020 | **26,937** |
| 48,000 | 23,463 ↓ | **28,533 ↑** |
| 64,000 | 19,517 ↓↓ | **25,488** |

Before, throughput FELL past the knee — the congestion-collapse signature. After, it keeps climbing to
28,533 and holds 25,488 at 64,000 instead of collapsing. Same hardware, same ladder, same 1,230 MiB heap.
This also retires the rig-sizing question the heap-cap change raised: no container-memory override is
needed, and the 60% cap is validated in the configuration users actually run.

Read `peak_achieved_rps` with care all the same: it still reports **2,000**, because it admits only rungs
with ZERO dropped iterations. It has not moved and must not be read as "no improvement" — it measures a
different property from the 26,937 knee, which is precisely the ambiguity item 19 must not publish past.

**A memory bound that did not bound.** Separately, `estimatedHeapSize()` — the weigher behind
`maxEventLogSizeInBytes` — counted raw body bytes and essentially nothing else. Measured by
degrading a test until it went red: ten retained entries with 10,000-byte bodies weighed
*exactly* 100,000. Live heap dumps at two body sizes then separated fixed from proportional
cost (2,054 B overhead at 1 KB, 2,037 B at 8 KB — flat, so ~2 KB of structural graph per entry
counted as zero; byte arrays scaled 1:1, confirming no hidden second body copy). Corrected in
`f3ade3b73`: real retention moves from ~2.6× the budget to ~1.0× at WARN, and ~4.8× to
~1.6–2.2× at INFO. The formatted message is deliberately still not counted — it is
materialised after the weight is memoised and only at rendering levels, and the default budget
divisor (`heap/8` at INFO against `heap/4` at WARN) already compensates for it, so counting it
too would compensate twice. **Open decision:** the divisor was tuned against the under-counting
weigher, so an honest weigher means the default budget now retains less real heap than before.
Restoring prior default capacity is a sizing decision, not a fix.

**Note (item 0) — is the pre-fix baseline history still comparable, given every stored
point has `instance_type:''`?** Yes: no re-baseline is needed, only the field populated from
here on. The empty field records nothing, but the hardware was in fact *constant* — the perf
queue is pinned to a single instance type (`perf_instance_types = "c5.4xlarge"`,
`terraform/buildkite-agents/variables.tf`, no `terraform.tfvars` override), on-demand (not a
Spot type-list, so no reclamation-driven type substitution), with `min = max = 1` (at most one
concurrent run). So the rolling median+MAD series was always same-on-same hardware; the missing
attribution was a *recording* gap, not a *comparability* gap. Two caveats that do not change the
conclusion: (1) the guarantee holds only while that terraform variable is unchanged — once
`instance_type_source:"observed"` values start landing, a future silent hardware change becomes
*visible* rather than assumed, which is the point of the fix; (2) points predating
`schema_version 2` are already flagged by compare's `PRE_CONFIG_COUNT` config-boundary warning
for the separate reason that they carry no `config` block (JDK/GC/heap/log-level), so they are
weighed with that caveat regardless of the instance-type field.

## Who does what

There is deliberately **no notification channel** (item 1): a regression on a gating metric
fails the build, and a red pipeline the owner already checks is the signal — no room to
terminate in, and no channel to leave unread. What still needs an owner is **judgement**:
which flagged metric is fixed, bisected or accepted, and when a notify-only metric has earned
promotion to gating. A rota is the wrong prescription for that on a small project: a rota
with one person fails the first time that person is on holiday, and one with three is a
fiction.

**What this needs is one named role and three mechanisms that do not need a human watching.**

- **Perf owner — one named person, named in `perf-budgets.json`.** Owns the budget file,
  reviews every ratchet PR and budget change, and decides whether a flagged metric is fixed,
  bisected or recorded as accepted — and **which notify-only metrics have earned promotion to
  gating** (≥ 10 clean runs plus a history-derived budget; see item 1). The signal reaches
  them as a red build, not a message to read. **Not a rota**, because rotating destroys the
  only thing that makes it work: continuity of judgement about what the numbers normally look
  like. A few minutes most days; an hour when something moves.
- **Per-measurement owner, recorded beside each budget.** Whoever landed a measurement owns
  its noise. If it flaps, the owner either fixes it or **demotes it to informational — a
  legitimate, recorded outcome, not a failure**. This is what stops the known pattern of a
  flaky gate being ignored, then deleted.
- **Mechanism 1: the release-preflight gate needs no human.** The most important
  organisational point here. If the owner is away three weeks, notifications pile up unread —
  and the release still cannot ship with a stale baseline or an unaccepted flagged
  regression, because the gate reads the budget file and S3 and fails on its own. **Design the
  loop so neglect is caught at the release boundary rather than assumed not to happen.**
- **Mechanism 2: the freshness assertion lives in a different pipeline.** A watchdog inside
  the system it watches dies with it. `pipeline-infra.yml`, not the perf pipeline.
- **Mechanism 3: the annual break-a-producer drill.** Same calendar slot as the quarterly
  review, so it is not a separate thing to remember.

**What deliberately has no owner:** the research items. They are scheduled work, not standing
duties, and pretending otherwise creates a backlog that guilts people rather than a queue
that gets picked from.

## Sustaining the goals

### Budgets that ratchet

The repo's preference is **never-regress on both small and large cases, with thresholds
derived empirically rather than picked.**

**Where budgets live.** Today all absolute floors are hardcoded inside
`perf-test-compare.sh`'s jq. Move them to a committed, reviewed `perf-budgets.json`:

- A **committed** budget cannot be quietly loosened — loosening is a reviewed diff with a
  required justification.
- The **rolling median + MAD** then does only what it is good at: absorbing noise. It can no
  longer normalise a slow real regression away over ten runs, because the committed floor does
  not move unless someone changes it.
- The annotation names the budget file's last-changed commit, so a silent loosening is visible
  in the output, not just in git history.

**How a budget is set.** Never picked. Run notify-only for at least 10 successful runs, take
the median and MAD, set the budget at `median + 3 * 1.4826 * MAD`, floored at a minimum
sensible percentage so a freakishly quiet window does not produce an impossibly tight budget.
Record the window — **including the `instance_type` and `config` of every run in it**, which
item 0 makes possible and which is currently impossible.

**How a budget tightens — the ratchet.** A budget that only ever loosens is not a control.

- **What fires:** after each successful daily run, if the head value has beaten the budget by
  more than 20% for **5 consecutive runs**, the compare step opens a PR tightening it.
- **When:** daily, at most one ratchet PR per metric per fortnight.
- **Guard against ratcheting on a rig improvement.** Only ratchet when `instance_type` and the
  `config` block are **identical across all five runs**. Otherwise a faster k6 image or a new
  agent generation permanently tightens a budget the server never earned, and every subsequent
  hardware change reads as a regression.
- **Who sees it:** the perf owner. Merging accepts the improvement as the new normal;
  declining with a reason is also legitimate.
- **Automated as a proposal, manual as a decision.** Never auto-merge a budget change in
  either direction.

**Small and large arms both.** `CandidateIndexBenchmark` sweeps n in {1, 2, 5} *and*
{100, 1000, 5000} precisely so a large-case optimisation cannot regress the small case
unnoticed. Every new budget should have both arms where the dimension admits one.

### Feedback latency: what should block a merge

The discriminator is **determinism, not importance**.

| Signal | Noise | Cadence |
|---|---|---|
| `gc.alloc.rate.norm` | **Essentially deterministic** — an allocation count, hardware-independent | **Per merge to master**, *once item 16 widens its coverage* |
| JMH `time_per_op` | low but hardware-sensitive; **understated today at `-f 1`** | Daily, pinned queue, two forks |
| Deterministic counters — threads, class-loads, connections, image size | none to low | Per merge, cheap |
| AppCDS mapped (boolean) | none | **Blocks the master build immediately post-merge** — a considered placement, not a limitation |
| ByteBuf leaks (boolean) | none | **Per merge**, gated at `verify` |
| k6 latency percentiles, knee | wall-clock | Daily, pinned queue, validity-gated |
| SSE inter-token delay error | wall-clock but *distributional*, so more robust than a single percentile | Daily |
| Soak slope, verification cost | long wall-clock | Weekly |
| Per-core, N-instance, connection ceiling, HTTP/3 end-to-end | very high | Occasional deep run |

**Recommendation — deliberately narrow:**

- **Nothing wall-clock blocks a pull request.** The queue is max-one-instance and
  scale-to-zero; a PR gate would serialise every merge behind a 45-minute run, and a
  wall-clock PR gate on shared agents is how a flaky gate gets born, ignored, then deleted.
- **Three things block, at different points, and the differences are deliberate.** The
  **leak gate** and **`mvn verify`** block the PR. The **AppCDS boolean** blocks the master
  build immediately post-merge. The **allocation budget** can block a PR as an unconditional
  step in `pipeline-java.yml` — but note that pipeline is itself orchestrator-path-filtered,
  so a JDK or base-image change reaches it only via the daily run. **Neither gate replaces the
  daily run**, which is the only always-runs backstop either has — and the daily run now has
  teeth of its own: a regression on a *gating* metric fails it (item 1), so a slowdown that
  reaches only the daily is no longer a green build.
- **The release gate is where teeth belong.** Fail preflight when the newest successful perf
  run is older than the release candidate's merge base, when any budget is in an unaccepted
  flagged state, or when the newest run's `validity` is false. One S3 query and one JSON read,
  and it is where "we shipped a 2x slowdown" actually gets caught.

**Standing rule: earlier is not automatically better.** A pre-merge gate that is path-filtered
may cover **fewer causes** than a post-merge gate that always runs:

- For a **defect gate** — catching a mistake in the change under review — earlier wins, because
  the cause is by definition inside the changed paths.
- For a **decay detector** — catching something that stops working for reasons unrelated to any
  one change — **always-runs usually beats runs-earlier**, because the causes are exactly the
  ones path filters miss.

The AppCDS check is a decay detector. So is baseline freshness. So is most of this programme.
**Anywhere this plan recommends moving a check earlier, it must say which causes that move
stops covering** — and if the answer is "the likely ones", do not move it.

### Keeping the system itself alive

**Its own risk, not a footnote.** The evidence that controls decay silently here is direct:

- The JMH backstop produced **no signal from 2026-09-12 to 2026-09-16** and nobody noticed;
  the only symptom was a red square on a notify-only build.
- `PERF_NOTIFY_WEBHOOK` was referenced by the compare step and **configured nowhere**, so the
  notification path never fired. *(Historical: still a valid illustration of the decay mode —
  a hook that looks like a notification path but is wired to nothing — but no longer a live
  gap. Item 1 removed the webhook and replaced it with a build that fails on a gating
  regression, so the notification is now the red pipeline itself, which cannot be
  "configured nowhere".)*
- A CI cache reported success while storing nothing (`1490c5ad4`).
- `agent.instance_type` has been the empty string in every stored run because `curl -s` exits
  zero on an empty body — a field that exists, is populated, and is **silently wrong**, for
  months.
- The pre-merge container slot is empty because `docker-build-verify.sh` was **un-wired after
  it turned the pipeline red and blocked PRs** — a control removed rather than repaired, and
  the reason item 4 has no cheap pre-merge home.

The general failure mode: **a control that stops working goes quiet rather than loud.** Green
is not the same as measuring. **And a populated field is not the same as a correct one** —
that is the `instance_type` lesson, and why every plausibility assertion must check *values*,
not presence.

1. **Baseline freshness assertion, owned by a different pipeline.** Fails if the newest object
   is older than 7 days **or if its expected keys are absent, null, or implausible**. Age alone
   is gameable by a producer writing valid empty JSON. On the `trigger` queue, in
   `pipeline-infra.yml` — **a check that lives inside the system it monitors dies with it.**
2. **Plausibility assertions, not exit codes.** Every producing step asserts a *plausible
   non-empty result*: keys present, non-null, within sane absolute ranges; `instance_type`
   matching a known pattern; sample log non-empty. Promote the existing empty-sample-log
   warning to a hard failure.
3. **Validate the measurement before trusting it.** Generalise the inject harness's discipline
   to every harness. **A number that has not been validated is not evidence — and an assertion
   that has never been false is not a validation.**
4. **Every step annotates its own failure.** The compare step owns the annotation and runs
   only *after* the `wait`, so a dead producer silently produces nothing.
   `perf-test-microbench.sh` gained a trap for this reason; copy it to the others.

**Acceptance test for this whole section: deliberately break one producer and confirm the
system says so within 24 hours.** Once when the programme lands, once a year after. If the
answer is "nothing happened", the controls are theatre.

### Re-baselining and drift

- **The rolling median handles noise only.** A 10-run window absorbs a 3%-per-run drift
  invisibly. That is why absolute budgets live in the committed file.
- **An intentional move is a reviewed commit** — date, metric, old and new values, cause, who
  approved. No other mechanism may change a budget.
- **Hardware changes invalidate history, loudly.** Make compare **refuse to compare** across a
  differing `instance_type` and annotate "baseline invalidated — re-derive". **This cannot be
  implemented before item 0**, because every stored run currently has `""` and a rule
  comparing empty strings compares everything to everything.
- **Configuration changes invalidate history too.** Extend the rule to the `config` block. The
  2026-06-24 figures are the worked example: `logLevel=ERROR`, `DISABLE_SYSTEM_OUT=true`,
  possibly ZGC on an 8 GB heap, and nothing recorded it.
- **Accepted regressions are recorded, not forgotten.** A flagged regression that is neither
  fixed nor recorded stays flagged, and the release gate keeps failing until somebody decides.
  **The only way to silence a regression is to write down why.**

### Handling noise without disabling the control

1. **Wall-clock runs only on the pinned `perf` queue.** Never Spot, never the mixed-instance
   `default` queue. *(`perf-test-load.sh` currently violates this.)*
2. **Never gate on a single sample.** Median of at least 5 for load-shaped, 9 for startup.
   Report dispersion so a widening spread is itself visible. **If the MAD exceeds 15% of the
   median, the measurement is informational, not gating** — stated in the annotation so the
   decision is made by data rather than by whoever is annoyed that day.
3. **Prefer within-run comparison.** `CandidateIndexBenchmark` comparing arms in the same JVM
   on the same run is the gold standard; items 13, 14, 15b and the dashboard A/B are shaped
   this way deliberately.
4. **Prefer deterministic counters over wall-clock** wherever the question can be reframed.
   These can gate on noisy hardware; wall-clock cannot.
5. **Prefer a distribution over a single percentile** where the question is fidelity rather
   than speed — item 12's delay-error distribution has a known correct value (zero) rather
   than a baseline.
6. **Anything that cannot meet 1-5 is informational, labelled, never gating.**
7. **Validity gating beats threshold loosening.** When a measurement is noisy because the rig
   was compromised, exclude the point. **Do not widen the threshold until noise fits inside
   it — that is how a gate becomes unable to fail.**

### Continuous improvement, not only defence

- **Quarterly deep review.** A JFR or async-profiler wall-clock **and** allocation profile of
  the measured behaviours plus at least one proxy and one streaming path, diffed against the
  previous quarter. Output the top ten allocation sites and CPU frames to a dated page so the
  trend is visible. Also run it **before every major release**.
- **Run the remaining dark benchmarks there** — the WASM interpreter, the optional-feature
  ledger — the ones that do not justify daily cost but should not bit-rot.
- **The ratchet is itself an improvement mechanism.** Every tightening PR is a recorded win;
  reviewing that history answers "did we actually get faster this quarter" with evidence.
- **Re-read the coverage map when a major feature lands and at each major release.** The first
  audit deferred five feature surfaces to "the first annual re-read"; three months later two
  were in Tier 2. **Tie the re-read to the release, not the calendar.**

## Open questions and risks

**Why no baseline has been written since 2026-09-11 — two sequential causes, not one (established
2026-09-18 from the Buildkite API).** It is tempting to attribute the whole gap to the SUT dying under
load, and that is wrong for most of it:

- **2026-09-12 to 2026-09-16** — the `run + sample` load step PASSED every time (builds 208, 209, 211,
  224, all exit 0). What failed was the **micro-benchmark** step, on dependency resolution:
  `Could not find artifact org.mock-server:mockserver-netty:jar:8.0.1-SNAPSHOT`. The benchmark module
  sits outside the Maven reactor, so its in-reactor dependencies must be installed via a named module
  plus `-am`. Fixed by `b98d18f0c` (2026-09-16), and because no full chain ran between that commit and
  2026-09-18, the fix went unexercised for two days. It passes on all five runs of 2026-09-18.
- **2026-09-18 onward** — the micro-benchmark passes and the **load step** fails instead, the SUT dying
  of JVM heap exhaustion at ~37k iterations. This is a NEW failure, not a continuation: the MB-scale
  body arms that trigger it landed the same day (`764731d10`). Root-caused to parsed JSON bodies
  retained on every log entry and invisible to the byte budget, fixed in `3d7a2f9c8`, and awaiting a
  run to confirm.

The lesson worth keeping: a pipeline that has been red for a week is not necessarily red for one
reason, and the duration signature said so before the logs did — the 2026-09-12 failures ran ~31
minutes, the 2026-09-18 ones ~69-110, against ~44 for a healthy chain. Three different shapes, and
only the middle one was the same bug.


1. ~~**Why is `regression.js`'s p95 a thousand times the sweep's, in the same run?**~~
   **ANSWERED and FIXED (2026-09-16).** A client-side VU-allocation connection storm: four
   `constant-arrival-rate` scenarios starting simultaneously with a `preAllocatedVUs`→`maxVUs`
   ramp, each new VU opening a connection, on a core-limited SUT — a feedback loop that
   overshoots then settles. Fixed with staggered starts, an equal (fixed) VU pool, warming
   every path (the `/large` path had been left cold), and a settle-window exclusion; verified
   to still move with a real +25 ms server delay. See Finding 3. This unblocked every D2
   latency budget and item 9a's design.
2. **Is the published 36,000 req/s knee real, or is it k6's ceiling?** The client is pinned to
   six cores — the same count as the server. Item 2 now asserts client headroom; until a run
   with it lands, treat the published figure as unverified.
   **PARTIAL (2026-09-18, build 290) — a third possibility was ruled out, and a shape recorded.**
   The first sweep to survive its own top rungs gives: 15,475 achieved at 16,000 offered,
   **26,020 at 32,000**, then 23,463 at 48,000 and 19,517 at 64,000 — error rate 0 at every
   rung, the losses all `dropped_iterations`. So the curve has a genuine knee around 32,000
   offered and **collapses beyond it**, which is a server-side congestion signature, not a
   client ceiling. It does **not** settle the 36,000 claim: build 290 was a deep-tier run
   (JFR + NMT instrumentation depresses throughput by design, which is why it sets
   `baseline_eligible:false`), so 26,020 is a **floor**, not a refutation. A clean-tier run on
   an image carrying the corrected heap cap is what closes this.
   Worth separating from the headline: the same sweep reports `peak_achieved_rps` of **2,000**,
   because that metric counts only rungs with ZERO dropped iterations. The published 36,000 and
   that 2,000 measure very different things, and item 19 must not put them on the same page
   without saying so.
3. **What did the 8.0.0 multiplex change cost per connection? ANSWERED (2026-09-18) — item 11's
   negative control was executed.** Pre-multiplex **7.6.0** vs first-multiplex **8.0.0** (Maven
   Central fat jars, same JDK/heap, same external h2c client, on-heap retained heap via
   `jmap -histo:live`, 5 reps/shape): 8.0.0 retains **+17,395 bytes/conn (+9.7%)** more at the
   low-noise **100×10** shape (5-rep ranges disjoint, 0.1% spread), and the excess **scales with
   concurrent streams per connection** (100×1 only +1.3%), consistent with the child-channel-per-
   stream design the migration named. Corroborates the shipped RSS comparison's **direction**
   (+24.7% RSS at 100×10; RSS is larger because it also counts off-heap direct buffers). **No
   longer an open risk** — the per-connection cost is modest and quantified. See item 11's
   acceptance-criteria row for method, honest limits, and raw data.
4. **Does the documented `-Xmx512m` sidecar configuration actually work under load?** Never
   tested. If it does not, the website recommends a configuration that OOMs.
   **The general form of this question is now ANSWERED, and the answer was no (2026-09-18).**
   The shipped images capped the heap at `MaxRAMPercentage=75.0`, which implicitly assumes the
   whole process fits in heap ÷ 0.75. Measured under sustained load with the heap pinned to what
   a 2 GiB container yields: peak RSS **2,271 MiB** against a **1,536 MiB** heap and **133 MiB**
   of metaspace + code cache — so **~735 MiB** of non-heap and native, dominated by Netty's
   pooled direct buffers, which scale with concurrency and body size and not with heap at all.
   That is a ratio of **1.48×**, against the 1.33× the 75% cap assumed: in a 2 GiB container the
   JVM wanted 2,271 MiB and had 2,048, a 223 MiB shortfall. Builds 284 and 294 were both
   OOM-killed by the kernel (`OOMKilled:true`, exit 137) mid-sweep, exactly as that arithmetic
   predicts. Default lowered to **60.0** across every image that runs `Main`, with the guard that
   enforces it updated in the same change (`ec2373d86`) — which also surfaced that
   `docker/aot/Dockerfile` had never been in that guard's coverage at all.
   The specific `-Xmx512m` sidecar claim still needs its own run, but note the sizing rule now
   published: the overhead is **additive and load-driven**, not a fixed multiple, so it gets
   proportionally worse as the container shrinks — a 512 MiB container is the size most likely
   to be killed, not the safest.
5. **`logLevel=INFO` (shipped default) or `ERROR` (what CI uses)?** Measuring the default makes
   the numbers representative but breaks comparability with the entire stored baseline.
   Recommendation: keep `ERROR` for the tracked baseline, **add** an `INFO` rung for the
   published figure, label both. Decide before item 19 refreshes the site.
   **DECIDED + measurement capability landed (2026-09-18).** The tracked, gated baseline stays
   `ERROR` and is unchanged. `perf-test-run.sh` now adds a second SUT at the shipped-default
   `INFO` level and re-measures ONLY the two published families — the knee curve (`sweep.js`)
   and per-behaviour percentiles (`regression.js` http+https) — emitting them under a DISTINCT
   result key (`.info_log_level_arm.*`, self-describing via `.config.log_level`), never under
   `.behaviours` / `.sweep` / `peak_achieved_rps`, so an `INFO` number can never be confused
   with or diffed against the `ERROR` series. Non-gating and excluded from `validity` (open
   question 9); `PERF_INFO_ARM=false` disables it; estimated ~10 min added wall-clock (open
   question 6 — verify against a real run). `perf-budgets.json` carries staged, notify-only
   `provisional` `info_*` budgets. **Still pending:** `perf-test-compare.sh` does not yet
   surface these keys and item 19 does not yet publish the `INFO` figure — sequence that after
   a run emits both series (a number is not published until it is measured).
6. ~~**Cost is not the constraint; the serialised box is.** One estimate unverified: nobody has
   measured how long the daily chain occupies it.~~ **MEASURED (2026-09-18): ~44 minutes.** From the
   Buildkite API, the last seven chains that ran to completion — builds 197, 198, 199, 200, 201, 204,
   205, 206, 207, spanning 2026-09-01 to 2026-09-11 — took 44.0, 44.3, 44.3, 44.0, 44.3, 44.2, 44.2,
   43.3 and 44.3 minutes. A spread of one minute across nine runs, so the figure is stable enough to
   plan against. A guard-skipped build (the per-commit case, where the run does not dispatch) costs
   ~0.5 min and two jobs, which is what most builds on the pipeline are. The cost table can now be
   read against a measured occupancy rather than an estimate.
7. **Is `alloc_bytes_per_op` really agent-independent?** One cheap experiment: same commit,
   five runs on each queue. Item 16 depends on the answer.
8. **Are the heap-derived store defaults order-dependent in a shared JVM?** Item 17 answers it.
   If true, it is both a performance finding and a flakiness finding.
9. **Flakiness risk remains real.** Items 8, 9, 10, 12, 13, 14, 17 and 18 are all wall-clock.
   **Land each notify-only first, observe 10 runs, and only then attach a budget.** Never ship
   a new wall-clock gate with a threshold on day one.
10. **The programme is larger than one person can land in a quarter.** Stated plainly rather
    than hidden in the ordering. The first fortnight and quarter are scoped to be achievable;
    Tier 3 explicitly is not.

## What to publish versus what to gate internally

**Published and kept current**, each figure carrying **version, date, core count, heap, GC and
log level** — none of which the schema records today:

- The knee curve, with **`healthy_ceiling_rps` as the headline** and `peak_achieved_rps` beside
  it labelled as degraded, with the latency measured at each. Never a ceiling without its
  latency.
- Per-behaviour percentiles — **now publishable (Finding 3 resolved 2026-09-16).** The tail
  everyone could not explain is explained and fixed; publish only figures from the **fixed**
  `regression.js`, and never again publish only the flattering half of an artefact — the
  `settle_excluded` / `delivery_ratio` fields make the whole run legible.
- Matcher scaling, scan versus index — already good.
- **New:** proxy-path latency; startup medians per artifact including in-JVM, and compressed
  image size; a laptop sizing table; TLS and mTLS handshake rates; and SSE streaming fidelity
  at concurrency, which is a differentiating claim nobody else publishes.

**Correct two published claims while you are there.** The page lists **soak** and **stress**
as part of how MockServer is tested; neither has ever executed. And it implies the figures are
default-configuration figures; they are not.

**Gated internally, never published:** the JMH absolute backstops, the growth and soak
live-set slope and absolute, the event-log verification cost, the forward-pool guard, the
AppCDS boolean, the leak gate, the streaming match-A/B ratio, the startup median-of-9, and
the baseline freshness assertion. These are regression detectors tuned for sensitivity rather
than defensibility; publishing them invites arguments about numbers that exist only to move.

## What survives this plan

This file is deleted by the change that completes the work. Four pieces deserve to persist;
move them before deleting.

1. **A corrected account of what each harness measures, and which ones run.** *Destination: a
   new `docs/code/performance-measurement.md`.* Must state plainly: which k6 scripts CI
   executes and which it only lints; that `ForwardPathBenchmark` measures the load generator's
   render path and **not** proxying; which JMH benchmarks run daily and which are dark; that
   the inject harness answers "how much load can MockServer generate", not "how fast does it
   serve"; and that `throughput_rps` is a delivery ratio against a fixed offered rate, not a
   throughput ceiling.
2. **The dating and provenance rule.** *Destination: `docs/code/startup-performance.md`*, which
   already half-states it. Every performance figure carries its date, version, hardware and
   configuration, or it is not a figure. **Add the corollary this audit learned the hard way:
   a populated field is not a correct one.**
3. **The corrections to the harness READMEs.** The k6 README described `forward.js` as a
   regression guard in the present tense while it never ran. Item 3 fixed the code; the README
   must not keep claiming guards the pipeline does not execute.
4. **The hazard-class table and the evidence standard** from
   [Proving a performance change is still correct](#proving-a-performance-change-is-still-correct).
   *Destination: alongside (1), or its own `docs/code/optimisation-safety.md` cross-linked from
   `docs/code/netty-pipeline.md`*, whose HTTP/2 testing convention is the worked example the
   whole section generalises.

Everything else here — the programme, the sequencing, the budgets — is scaffolding for the
work and goes when the work is done.
