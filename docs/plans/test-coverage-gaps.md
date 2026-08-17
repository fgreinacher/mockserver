# Test Coverage Gaps

Five open gaps. Distilled from the 2026-07-21 coverage audit, re-verified against
`master` on 2026-08-17 (81 of the original 93 were already closed), then worked down
from twelve to five on 2026-08-17.

**Two are judged not worth doing; the three Kubernetes gaps are now CLOSED** — each has a
live k3d test that was proven red by degrading the behaviour it names before being trusted
green. Each says why below, so nobody re-opens the deferred ones without new information.

## Kubernetes gaps — CLOSED (2026-08-17)

~~Blocked on a Kubernetes cluster.~~ **The proxy blocker was wrong and is now resolved.**
These three needed a real k3d cluster, which was believed impossible to stand up locally
behind the corporate TLS-inspection proxy. It is not: a k3d cluster stands up and the full
Helm suite passes from a developer machine behind the proxy. All three now ship as live
`container_integration_tests/helm_*` cases.

The misdiagnosis was a **two-trust-stores** trap. The *host* Docker daemon already trusts
the corporate root, so `k3d cluster create` pulls the k3s node image fine — which made
"Docker works, so k3d works" look true. But **containerd *inside* the k3s node has its own
trust store** (`/etc/ssl/certs/ca-certificates.crt`, public roots only); without the
corporate root there it cannot pull even the `rancher/mirrored-pause` sandbox image, so
every pod fails at sandbox creation with `x509: certificate signed by unknown authority`.
The fix — overmounting the **combined** bundle as containerd's trust store when the cluster
is created — is now wired into `helm-deploy.sh`'s `start-up-k8s` behind the opt-in,
CI-inert `K3D_LOCAL_CA_BUNDLE` env var (see
[docs/operations/build-system.md](../operations/build-system.md#local-development-behind-a-corporate-tls-inspection-proxy)).

**1. `helm_clustered_convergence` — now BLOCKING.** Flipped from `non_blocking || true` to a
blocking `test "helm_clustered_convergence"`. The flip was earned, not asserted: the
swallowed `k3d image import ... 2>/dev/null || true` (the stated "image-import race") is
replaced by `import_image_into_k3d`, which imports deterministically and asserts the image is
present in the node's containerd (via `crictl`) before deploying; and the real back-to-back
flake — `helm install` into a still-`Terminating` namespace left by a prior run/retry — is
fixed by a pre-deploy `ensure_namespace_absent` guard. Characterised over 6 local runs: 5
green, the 1 failure was the namespace-termination collision (now fixed), after which 3
back-to-back runs were green. Fails loudly on a genuinely broken deploy (proven: a deploy
that could not proceed recorded `Failed: helm_clustered_convergence`).

**2. Live sidecar-injection test — `helm_sidecar_injection`.** Deploys the chart with
`webhook.enabled=true` (self-signed TLS bootstrap + handler Deployment + MWC), drives a real
labelled pod CREATE through the admission path, and asserts the resulting pod **spec** carries
the injected `mockserver-sidecar` container, `mockserver-iptables-init` init container, and
`mockserver.org/injected` annotation — plus a negative-control pod (no annotation) that must
**not** be injected. Proven red by disabling the webhook (deleting the MWC): the annotated
pod then has only `[app]`.

**3. JGroups `DNS_PING` discovery — `helm_jgroups_dns_ping`.** Deploys 2 clustered replicas
and asserts the headless Service is truly headless (`clusterIP: None`), that `JGROUPS_DNS_QUERY`
is wired to its FQDN, that it resolves to ≥2 pod IPs (Endpoints + an in-cluster `nslookup`),
that a ≥2-node JGroups view forms (the anti "two clusters of one" guard), and that state
converges across the pods. Chosen as a k3d/helm case rather than Java Testcontainers because
DNS_PING genuinely requires Kubernetes DNS + a headless Service, which a JVM/Testcontainers
harness cannot provide without re-implementing k8s DNS. Proven red by deleting the headless
Service and rolling the pods: both then report a 1-node view and state does not converge.

### CI reach (important caveat)

All three depend on Java-built images — the `-clustered` variant and the webhook handler —
that the current CI helm step (`helm-integration-test.sh`, `SKIP_JAVA_BUILD=true`, no JDK)
does **not** build. So in CI today they record an honest **SKIP** (not a pass, not a warn),
gated on `clustered_image_available` / `webhook_image_available`; they run **blocking** only
where those images exist (local dev, or a future CI step that builds them). Making them run
green in CI requires extending the helm CI step to build the `-clustered` and
`mockserver-webhook` images — tracked separately as it touches `.buildkite/**`.

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
