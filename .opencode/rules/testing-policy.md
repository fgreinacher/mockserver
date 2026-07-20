# Testing Policy

## Post-Change Testing

After making code changes, ALWAYS run unit tests for the affected module(s).

- Identify which Maven module(s) were modified based on file paths (e.g., files in `mockserver-core/` → module `mockserver-core`)
- Run unit tests with Maven targeting the specific module: `./mvnw test -pl <module>`
- If tests fail, fix the issues before considering the task complete
- When a specific test fails, re-run just that test: `./mvnw test -pl <module> -Dtest=<TestClassName>#<testMethodName>`
- Do NOT run integration tests automatically — they are slow and run in CI
- If changes span multiple modules, run tests for ALL affected modules: `./mvnw test -pl <module1>,<module2>`

## Docker-Gated Tests

**Docker is available locally** (Docker Desktop on the developer Mac) — see `AGENTS.md` → "Local Development Environment".

- Tests guarded by `Assume.assumeTrue(DockerClientFactory.instance().isDockerAvailable())` (Testcontainers live-broker tests, `NET_ADMIN` transparent-proxy e2e, QUIC/HTTP-3 client tests, etc.) **actually run here** — when validating such a change, run it and confirm it PASSES, not merely that it skips.
- **Keep the `assumeTrue(...isDockerAvailable())` gating in place** regardless. It is still correct so the suite degrades gracefully on machines/CI agents without Docker. Docker being present changes how we *validate*, not how we *write* the tests.
- The `DockerClientFactory.isDockerAvailable()` probe works correctly with Testcontainers 1.21.4+ (docker-java 3.4.2) on Docker Desktop 4.67 / Engine 29.x / API 1.54. Always confirm a Docker-gated test actually RAN (not skipped) before claiming local validation.
- `docker build` / `docker run` are available for Dockerfile smoke checks (see `commit-workflow.md`).

## Before Committing (MANDATORY)

Follow the full pre-commit workflow in `commit-workflow.md`. That workflow covers all file types (Java, Terraform, Bash, Docker, Helm, docs). This file covers the Java-specific testing details.

When the user asks to commit Java changes:
1. **Run unit tests** — `./mvnw test -pl <modules>` for all affected modules. Fix failures before committing.
2. **Adversarial review** — launch `review-cheap` subagent (see `commit-workflow.md` Step 4; control / AI-component changes use `review-final` + gated approval).
3. **Only then commit.**

**Skip condition:** If user explicitly says to skip (e.g., "skip tests", "just commit"), skip corresponding steps.

If unit tests already passed earlier in this conversation for the exact same changes (no further edits since), skip re-running.

## Maven Module Mapping

| Directory | Maven Module |
|-----------|-------------|
| `mockserver-core/` | `mockserver-core` |
| `mockserver-netty/` | `mockserver-netty` |
| `mockserver-client-java/` | `mockserver-client-java` |
| `mockserver-war/` | `mockserver-war` |
| `mockserver-proxy-war/` | `mockserver-proxy-war` |
| `mockserver-junit-jupiter/` | `mockserver-junit-jupiter` |
| `mockserver-junit-rule/` | `mockserver-junit-rule` |
| `mockserver-spring-test-listener/` | `mockserver-spring-test-listener` |
| `mockserver-testing/` | `mockserver-testing` |
| `mockserver-integration-testing/` | `mockserver-integration-testing` |
| `examples/java/` | `mockserver-examples` |

## Maven Test Commands

```bash
# Unit tests for a specific module
./mvnw test -pl mockserver-core

# Unit tests for multiple modules
./mvnw test -pl mockserver-core,mockserver-netty

# Run a specific test class
./mvnw test -pl mockserver-core -Dtest=HttpRequestTest

# Run a specific test method
./mvnw test -pl mockserver-core -Dtest=HttpRequestTest#shouldCreateRequest

# All unit tests (slow — avoid unless needed)
./mvnw test

# Quick build (compile + test, skip integration tests)
./mvnw verify -DskipITs
```

## Test Quality

- **New tests:** Follow existing test patterns in the module. Use JUnit 5 (Jupiter) only in `mockserver-junit-jupiter`; all other modules use JUnit 4.
- **Flaky tests:** Never just re-run — investigate root cause. Common causes: port contention, timing-dependent assertions, shared mutable state.
- Descriptive test names that explain the expected behavior.

## Local-First Verification

**Prove fixes locally before pushing.** Reproduce the failure and confirm the fix passes locally before pushing. Do not push speculatively and rely on CI to confirm correctness — CI runs under a PTY and some repros require `pty.fork`; a green CI run is not a substitute for a local prove-out.

## Observable-Behaviour Tests

**Prefer tests that assert observable behaviour** over tests coupled to implementation details (specific class names, shaded-jar contents, internal field values). Observable-behaviour tests assert what the system *does* — HTTP response codes, JSON payloads, log entries, emitted events — so they survive safe refactors without false-failure noise.
