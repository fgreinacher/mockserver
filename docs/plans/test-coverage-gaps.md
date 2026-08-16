# Test Coverage Gaps

Twelve open gaps, distilled from the 2026-07-21 coverage audit after re-verifying all
93 original entries against `master` on 2026-08-17. **81 were closed**; this file is
what remains. The original audit JSON and its working notes have been deleted — their
`status` fields were wrong for 81 of 93 rows, which made them actively misleading.

Ranked by real risk of an undetected defect, not by the original severity label.

## Priority

| # | Gap | Why it matters |
|---|-----|----------------|
| 1 | node/python testcontainers start no container | Two published client libraries have a CI job that goes green having exercised nothing |
| 2 | Composer codegen has no compile gate for 5 of 7 languages | Broken generated code reaches users directly |
| 3 | GCS/Azure config → client factory wiring | A credential or endpoint wiring bug ships silently |
| 4 | `docker_compose_war_tomcat` skipped in CI | A working behavioural test that never runs, in a demonstrated weak spot |

The MQTT TLS-listener gap that previously sat here is **closed** — see below.

---

## 1. node and python testcontainers modules start no container

`mockserver-testcontainers/node` runs `npm run test:unit`; the python module runs
`pytest -m "not docker"`. Neither starts a container, so both CI jobs pass having
verified nothing about the thing they exist to verify. Go, .NET and Rust already mount
`--docker-socket` and start real containers (`91de27d4a`), so this is a same-shape
completion rather than new design.

These gate **published artifacts**, which is what separates this from the CI-plumbing
items below.

**Done when:** both jobs start a real MockServer container and assert against it, with
`assert-suite-ran.sh` proving they were not skipped.

## 2. Composer codegen compile gate covers only 2 of 7 languages

The dashboard's composer emits client code in seven languages, verified only by string
assertions. Java is gated by `ui-java-codegen-compile.sh` (`pipeline-java.yml:161`) and
C# by `ComposerCodegenEquivalenceTests.cs`. Python, Go, Ruby and Rust have no gate at all.

`typecheck-node-codegen.mjs` already exists and is invoked by **no pipeline step and no
npm script** — the Node arm is nearly free.

**Done when:** each emitted language is compiled or type-checked in CI. Start with Node
(the script exists), then the remaining four.

## 3. GCS and Azure blob config → client factory wiring

S3 is covered by `S3BlobStoreRegistrarConfigWiringTest` (`36258e097`). The GCS and Azure
contract tests still hand-build their clients (`new GcsBlobStore(storage, …)`,
`new AzureBlobStore(containerClient, …)`), so the registrar that turns configuration into
a client is never exercised. A bucket, endpoint or credential wiring bug in either would
ship undetected.

The S3 test is a direct template.

## 4. `docker_compose_war_tomcat` never runs in CI

`container_integration_tests/.../integration_test.sh:37` still skips with *"WAR artifact
not present (built locally only); CI wiring is a follow-up"*. The test itself works.

WAR deployment is a demonstrated weak spot — the ROOT-context percent-decode regression
(`66b5d51d2`) broke builds for some time. Needs the WAR published to the artifact store;
plumbing, not test design.

---

## Lower priority

**6. CassetteRegistry auto-population** — `register(...)` is called only from
`HttpState.java` `handleCassettesPut`; load and record do not auto-register. This is a
**product decision, not a test gap**: decide whether `load_expectations_from_file` should
register a cassette, then either implement and test it, or document PUT-only and close.
It will keep resurfacing until someone decides.

**7. Cross-cutting false-green sweep as a standing axis** — the three "tell" categories
from the original audit exist only in plan documents; no guard or meta-test enforces
them. Their named instances were all fixed, so the residual value is preventing
recurrence. Worth a standing CI grep only if this audit style continues.

**8. LLM codec golden bodies are still self-derived** — token counts are now hand-authored
(`10e8af0d8`), but golden **bodies** are still regenerated from the codec via
`-Dmockserver.updateLlmGoldens=true`, so a structural codec error bakes into its own
golden. Narrowed by the provider wire tests in `LlmAgentLoopE2eTest`.

**9–11. Kubernetes-bound (grouped — one shared blocker)** — all need a real k3d cluster,
which the corporate TLS proxy prevents locally, making them CI-only work:
- `helm_clustered_convergence` is still `non_blocking || true` (`integration_tests.sh:671`) — a one-line flip once the k3d image-import race is stable
- No k8s MutatingWebhook live sidecar-injection test
- `JGroupsKubernetesStackTest` only parses XML; `ClusteredTwoNodeTest` uses loopback TCP/MPING, so DNS_PING discovery is unproven

**12. Mutation smoke-gate over COVERED verdicts** — originally rated HIGH as an
audit-integrity control. **Do not do this because of that label.** Its practical value has
largely been paid down: recent commits repeatedly document degrade-and-confirm-red having
been performed by hand (`f7ca781d3`, `7ce64a4c4`, `ad421ec22`). Building a pitest gate now
is a large investment against a much-reduced risk.

---

## Closed while distilling this list

**MQTT TLS-listener enforcement.** Implemented in `c8584bf05`, reverted 80 minutes later
by `a89772480` with no stated reason, then stranded as an untracked file in an abandoned
worktree. Recovered and landed.

The revert reason came from Buildkite `mockserver-java#1796`: the server certificate's
SAN was hard-coded to `localhost`/`127.0.0.1`, but CI runs this step inside a container
against a mounted Docker socket, so Testcontainers returns the bridge gateway IP
(`172.16.0.1`). Paho enables HTTPS endpoint identification by default, so the handshake
failed on **identity, not trust** — nothing was wrong with what the test asserted.

The fix resolves the connect host before the container starts, bakes it into the SAN, and
asserts `getHost()` still matches afterwards so a future Testcontainers change fails
readably rather than as an opaque handshake error.

Worth knowing: `MqttTlsLiveBrokerIntegrationTest` is **misnamed** — despite "Tls" it runs
`tcp://` on 1883 with a password file and never performs a handshake. It covers
credentials over plaintext. Renaming it to `MqttCredentialsLiveBrokerIntegrationTest`
would stop it being mistaken for TLS coverage.

## Not in this list, and worth knowing

**The certificate/TLS programme's own surface has never been audited.** The four
TLS-touching gaps in the original audit (runtime mTLS, SNI cert selection, per-host
forward-proxy client certs, IPv6 SANs) are all closed — but by the July coverage wave
(`807df3daa`, `fa74ddd42`, `2bc698943`, `3f3fa40cd`), not by the certificate programme,
which came later and was orthogonal.

That programme added genuinely new behaviour: leaf validity windows, EKU/keyUsage/AKI,
expiry-driven cache renewal, SAN bounding, upstream hostname verification and the
dynamic-CA generation race fix. None of it is assessed here, because this audit predates
all of it. If a TLS coverage question is worth answering, that is the surface to look at.

## Method caveat

The re-verification located named test classes and read their assertions; it did not run
the build. That is strong evidence a test exists and targets the right boundary, but it
does not prove each test *bites*. Several commits document that check having been done by
hand. Treat any single row as high-confidence rather than certain.
