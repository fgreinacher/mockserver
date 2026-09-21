# Optimisation Safety

**A performance PR whose only evidence is a faster number must not merge.** A benchmark is the
motivation for a change, never its verification.

An optimisation arrives with a success signal already attached — a chart showing it worked.
That signal ends scrutiny. A feature change lands with no green light and attracts scrutiny
until it earns one. The asymmetry is structural, and the repo has paid for it:
`Http2FlowControlBodies` records in its javadoc that **four HTTP/2 defects — #2641, #2667,
#2669, #2683 — all shipped while every HTTP/2 test was green**, because every test used a body
smaller than the 65,535-byte flow-control window. The failure mode was a silent hang.

## The Data Plane Wins Ties

**When a change trades data-plane cost for control-plane gain, the data plane wins unless the case
for the reverse is very compelling.** Serving a mocked response is what MockServer is for and what
it does millions of times; the control plane is what a suite touches between tests. A millisecond
added to every request to save ten from a teardown is a bad trade even though both numbers moved in
a direction a benchmark would applaud.

This is a tie-breaker, not a veto. A control-plane change that costs the data plane nothing needs no
argument. One that costs it something needs the cost stated in the PR, measured rather than
asserted, and a reason the exchange rate is favourable — and "the control plane got much faster" is
not that reason on its own.

**Where the trade hides.** It is rarely a line that says "slow down the data plane". It is usually a
SHARED STRUCTURE that the control plane wants indexed and the data plane has to maintain. Ask: what
does this add to the per-request path, and what does it add to every mutation? Then ask the question
that catches the subtle case — **is that mutation path actually control-plane-only?** In this
codebase it is not: `firstMatchingExpectation` schedules lazy removal of `once()` and
limited-`Times` matchers during its own scan, so the SERVING path mutates the store. Anything
maintained per-mutation is therefore partly paid by the data plane, however control-plane the
feature sounds.

*Worked example, applied to this repo's own change.* The `clear` fast path (`4cda4041f`) is a
control-plane optimisation: it made `clear` O(1)-ish instead of O(n), worth 1,585 us -> 0.2 us at
15,000 expectations. It paid for that with a SECOND index dimension maintained on every structural
mutation. Under this principle that is exactly the shape to scrutinise, and the verdict is that it
passes — but the reasoning, not the conclusion, is the point:

- data-plane READS are untouched: the matching path reads the `(method, path)` buckets and the
  fallthrough, never the path-only dimension;
- the added maintenance is O(1) per mutation, not O(n);
- but it is NOT free to the data plane, because of the lazy-removal path above — a workload using
  `once()` expectations mutates the store from the serving thread and now does marginally more work
  there.

So the honest statement is "a small constant added to data-plane-triggered mutations, buying three
orders of magnitude on a control-plane operation" — not "free". It was shipped before this principle
was written down; it is recorded here because a worked example that judges our own change is worth
more than an invented one.

## The Evidence Standard

Three checks, in order. The second is the one that gets skipped.

**1. `mvn verify` passes on the affected module — not `mvn test`.** `mvn test` excludes
integration tests: surefire carries `<exclude>**/*IntegrationTest.java</exclude>` and failsafe
picks them up under `verify`. For `mockserver-netty` this is roughly 1,219 tests under `test`
against 2,275 under `verify` (measured 2026-09-16). A perf change verified with `mvn test` has
skipped nearly half the suite and essentially all tests that drive a real socket. **"Tests pass"
is not a claim; "`mvn verify` passes on `mockserver-netty`" is.**

**2. Identical inputs produce identical outputs through the old and new path.** Not "the tests
still pass" — "the output is the same." Compare byte-for-byte: response bytes, header order,
status, trailers, observable frame boundaries, the serialised event-log entry. This is the only
evidence that catches drift nobody anticipated. Where a true A/B is impractical, pin a golden
corpus before the change and diff it after.

**3. Confirm the correctness test can catch the break.** Invert the usual discipline: the fix
is the optimisation, so deliberately introduce the hazard — skip an invalidation, drop a
`release()`, reuse a buffer without clearing, remove the type guard — and confirm something goes
red. A test that cannot fail does not provide evidence; it provides false confidence.

## Hazard Classes

Every class below has occurred in this repository.

| # | Hazard | Why it evades a benchmark | Required evidence |
|---|---|---|---|
| 1 | **Reuse and pooling** | Cross-request contamination requires concurrency; throughput is indifferent to whose bytes came back. A security failure, not only a correctness one | Concurrency test with distinguishable per-request payloads asserting zero cross-talk, at real concurrency |
| 2 | **Caching** | A cache is fastest and most wrong when it never invalidates. Hit-path tests get faster; staleness is invisible | The invalidation path tested: mutate the underlying data, assert the cached view updates. A cache tested only for hits is a bug with a benchmark attached |
| 3 | **Reference counting** | A leak shows as heap growth over hours; a double-release as corruption under load | `mvn verify` on `mockserver-netty` with leak detection at `paranoid` — wired and gated since `a1158a104`, which found a shipped production leak on its first run |
| 4 | **Laziness and init order** | Cold start is single-threaded; the race needs concurrent first use. The dynamic CA race presented as a ~10% launcher flake and was a shipped TLS race | Concurrent first-use, repeated. Treat an intermittent failure introduced by a lazy-init change as a shipped race until proven otherwise |
| 5 | **Concurrency and pool changes** | A deadlock under recursion is invisible to a load generator that never recurses | The deadlock argument stated in the PR, plus a test under contention. `localCallbackExecutor` is deliberately unbounded because a bounded pool self-deadlocks on a blocking loopback callback — the javadoc is all that stands between the next optimiser and that bug |
| 6 | **Topology changes** | Handlers attached to the wrong thing still forward traffic; throughput is unaffected | Assertions on the **type**, and both parent and child cases. See note below |

A seventh hazard specific to MockServer: **init-order changes alter heap-derived capacities.**
`maxLogEntries` and `maxExpectations` derive from free heap at call time, so an optimisation
that moves *when* initialisation happens changes store sizes without touching store code. Assert
the derived capacities, not just behaviour that happens to fit inside them.

### Worked example: topology change (hazard class 6)

The HTTP/2 multiplex migration (issue #2669, `docs/code/netty-pipeline.md`) is the definitive
example for hazard class 6. Every HTTP/2 test passed before the fix; every test after ran on
per-stream `Http2StreamChannel` child channels rather than the connection channel. The
correctness test used:

- `Http2FlowControlBodies.body(OVER_WINDOW, marker)` — a body > 65,535 bytes, which crosses the
  HTTP/2 flow-control window. Every pre-fix test used a body smaller than the window; smaller
  bodies never triggered the flush path and the failure mode was a silent hang, not a wrong
  answer.
- Guard on `Http2StreamChannel` **type**, not `parent() != null`. On an HTTP/1.1 socket,
  `parent()` is the server *listening* socket; a guard written as `parent() != null` would shut
  down the entire server on the first concurrent stream, benchmarks cleanly on one connection,
  and look correct in review.

`Http2FlowControlBodies` (in `mockserver-testing`) exposes named sizes — `SMALL`, `OVER_WINDOW`
(256 KB), `LARGE` (~1 MB) — and stamps the body with a caller-supplied marker so a mis-routed or
truncated body fails an equality assertion, not a length check. Its static initialiser fails the
build if `OVER_WINDOW` is shrunk to at or below 65,535 bytes, so the blind spot cannot be
silently reintroduced.

## The Benchmark-Shaped Correctness Loss

An optimisation can be **correct on the benchmark's inputs and wrong on real ones**, because
benchmark fixtures are chosen for convenience and stability — the two properties that make them
unrepresentative:

- Sub-window bodies hid the flush family for four releases.
- All-ASCII fixtures hid a double-encoding defect.
- A 7-byte payload left frame-length bytes at zero, indistinguishable from default init.
- A new assertion pinning the proxy-auth encoder against its predecessor
  passed against a deliberately wrong encoder, because the ASCII test credential's base64
  contained no `+` or `/` — the only two characters where the standard and URL-safe alphabets
  differ. The fixture could not distinguish the two encoders it existed to distinguish. Only
  the degrade test (hazard check 3, above) found it.

The corpus must be **adversarial in exactly the dimensions the change touches**. Buffering or
flushing: cross buffer and flow-control boundaries. Encoding: non-ASCII, including the
characters where alphabets differ. Framing: empty, one-byte, boundary-minus-one, boundary,
boundary-plus-one, maximal. And the error paths — an optimisation that skips work on success
frequently skips cleanup on failure.

## A Performance Change That Removes Waiting Is a Concurrency Change

This recurred multiple times during the performance programme and is worth stating separately.

Fixed sleeps, poll granularity, and "quiet periods" in the test suite routinely compensate for
races nobody has written down. An optimisation that:
- removes or shortens a sleep,
- changes a polling interval,
- moves work to a different thread pool,
- reorders initialisation,
- or reduces how long a lock is held

...is a concurrency change. The race it exposes may have been latent for years and never
appeared because the original code was slow enough that the timing window never opened in
practice.

**Protocol**: treat any intermittent failure introduced by a performance change as evidence of a
real race, not test flakiness. Isolate the failure by bisect to the specific commit before
theorising about mechanism.

## Where This Plugs Into the Gate Chain

| Evidence | Where | Why there |
|---|---|---|
| `mvn verify` on affected module | Per merge, blocks the PR | The baseline — the only thing that makes "tests pass" mean anything |
| Leak detection at `paranoid` | Per merge, gated at `verify` on `mockserver-netty` | Deterministic; a leak found a week later is a bisect across a week |
| Allocation-per-op budget (`perf-alloc-gate.sh`) | Per merge | Cheap, deterministic, attributes to one commit |
| Differential corpus | Required in the landing PR as evidence, not per-merge CI | Too slow for every merge; meaningful only against the specific old path, which exists only in that PR |
| Negative control (degrade test) | Required in the landing PR | Nobody can automate "prove this test can fail"; it is a one-time act per change |
| Adversarial corpus arms | Required in the landing PR | Which dimensions matter depends on what the change touches; no CI step can infer that |
| Deadlock argument (class 5) | Required in the PR description | An argument, not a test. Writing it down stops the next person undoing it |
| Hazard-class identification; type assertions; invalidation paths | Review checklist | Judgement, not automation |
| Daily perf run confirms the win survives | After merge | The last check, not the first |

**Two rules:**

1. **A performance PR states its hazard classes.** If the author cannot name which of the six
   apply, the change has not been understood well enough to merge. Belonging to none is
   legitimate — say so, and that is the end of it.

2. **The benchmark result belongs in the PR body under a heading saying it is motivation, not
   verification.** The failure this prevents is not that people lie about testing; it is that a
   green chart feels like completion.
