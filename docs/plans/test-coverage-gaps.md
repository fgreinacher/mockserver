# Test Coverage Gaps

Five open gaps. Distilled from the 2026-07-21 coverage audit, re-verified against
`master` on 2026-08-17 (81 of the original 93 were already closed), then worked down
from twelve to five on 2026-08-17.

**All five that remain are blocked or judged not worth doing** — none is simply
outstanding work. Each says why below, so nobody re-opens them without new information.

## Blocked on a Kubernetes cluster

These three share one blocker: they need a real k3d cluster, which the corporate
TLS-inspection proxy prevents standing up locally. They are CI-only work and cannot be
verified from a developer machine behind the proxy.

**1. `helm_clustered_convergence` is non-blocking.** `container_integration_tests/.../integration_tests.sh`
still runs it as `non_blocking || true`. A one-line flip once the k3d image-import race is
stable — the cheapest of the three, and the one to do first.

**2. No live sidecar-injection test for the k8s MutatingWebhook.** No
`container_integration_tests/helm_*` case references webhook, inject or sidecar, so the
admission path ships unproven.

**3. JGroups `DNS_PING` discovery is unproven.** `JGroupsKubernetesStackTest` only parses
XML, and `ClusteredTwoNodeTest` uses loopback TCP/MPING. The Kubernetes discovery path
itself is never exercised.

## Judged not worth doing

**4. Cross-cutting false-green sweep as a standing axis.** The three "tell" categories from
the original audit exist only in plan documents; no guard enforces them. Their named
instances were all fixed, so the residual value is preventing recurrence — bookkeeping
unless this audit style continues. Worth a standing CI grep only in that case.

**5. Mutation smoke-gate over COVERED verdicts.** Originally rated HIGH as an
audit-integrity control. **Do not do this because of that label.** Its value has been paid
down: degrade-and-confirm-red is now performed by hand as a matter of course, and every
gap closed on 2026-08-17 carries that evidence in its commit message. A pitest gate is a
large investment against a much-reduced risk.

---

## Closed on 2026-08-17

| Gap | Commit | Note |
|-----|--------|------|
| MQTT TLS-listener handshake | `f0706e32d` | Reverted commit recovered; SAN now covers the container host |
| GCS/Azure config → client wiring | `2ac86344a` | Registrar driven from config against real emulators |
| Composer codegen compile gates | `e1e08e2e2` | Python, Ruby, Go, Rust added; Node was already gated |
| node/python testcontainers | `ff5c5df8e` | Both now start a real container, guarded fail-closed |
| `docker_compose_war_tomcat` in CI | `12758d003` | WAR published as an artifact; skip is now a failure |
| CassetteRegistry auto-population | `aa1369f56` | Already implemented in July; endpoint seam now tested |
| LLM codec golden self-derivation | `d0872c22e` | Structural contract test that regeneration cannot reach |

## What this exercise taught, worth keeping

**A quarter of the twelve had stale premises.** Three were wrong when acted on, barely a
day after the list was written from a careful re-audit:

- `typecheck-node-codegen.mjs` was recorded as wired to nothing. It runs under `npm test`
  via `node.test.ts`, with a negative control.
- CassetteRegistry auto-registration was recorded as an open product decision. It was
  implemented and documented in July by `76ae2f232`.
- The WAR case was expected to run from the container-tests pipeline. That pipeline only
  lints the script; the Java pipeline runs it.

None of this means the audit was careless — it means a moving repository outruns any
static catalogue. **The safeguard is that each piece of work verified its own premise
before acting**, and all three staleness findings came from doing that. Treat every row
here as a hypothesis to check, not a fact.

**The gaps that mattered were not the ones with the highest severity labels.** The most
valuable find was rated MEDIUM: two published client libraries whose CI jobs passed having
started no container — and in python's case the Docker socket was mounted and then
deliberately unused via `pytest -m "not docker"`. Wiring it up then exposed four further
defects that would each have prevented a container starting, every one caught by the new
guard failing closed rather than by anything passing.

## Method caveat

The re-verification located named test classes and read their assertions; it did not run
the build for the 81 it marked closed. That is strong evidence a test exists and targets
the right boundary, but it does not prove each one *bites*. Treat any single closed row as
high-confidence rather than certain.
