#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────
# Run the mockserver-testcontainers Rust suite against a real MockServer
# container started via Testcontainers.
#
# WHY THIS STEP ASSERTS EXPLICITLY (fail closed)
#
#   The container-start test (tests::start_mockserver_container) is marked
#   #[ignore] so a bare `cargo test` — including the `cargo test --all-targets`
#   run below and any local developer run — skips it for ergonomics (it needs a
#   Docker daemon and pulls the mockserver image). That means `cargo test` alone
#   exits 0 having started NO container: a silent false positive, the same one the
#   Java/Go/.NET testcontainer steps exist to remove.
#
#   So this step, which DOES mount the Docker socket, runs that ignored test
#   EXPLICITLY (`cargo test -- --ignored`), captures its output, and greps for the
#   RUST_TESTCONTAINERS_STATUS_OK marker the test prints only after a real
#   container answered PUT /mockserver/status with 200. If the marker is absent —
#   the test was filtered out, renamed, or the socket was unusable — the step
#   fails loudly instead of passing green.
#
# clippy is also run for lint coverage (-D warnings).
# ──────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Testcontainers tests require Docker — mount the socket.
exec "$SCRIPT_DIR/../run-in-docker.sh" \
  -i rust:1 \
  -w /build/mockserver-testcontainers/rust \
  --docker-socket \
  -- bash -ec '
    rustup component add clippy >/dev/null 2>&1 || true

    # Fast, Docker-free tests (the #[ignore]d container test is skipped here).
    cargo test --all-targets

    # Container-start integration test: run the #[ignore]d test EXPLICITLY so a
    # real MockServer container is started and hit on /mockserver/status. Capture
    # output with --nocapture so the test print reaches stdout, then fail closed
    # on the evidence marker rather than trusting cargo'"'"'s exit code.
    out="$(cargo test -- --ignored --exact tests::start_mockserver_container --nocapture 2>&1)"
    echo "$out"

    if ! echo "$out" | grep -q "RUST_TESTCONTAINERS_STATUS_OK"; then
      echo "+++ :bangbang: rust container-start test did not emit its status marker — no real container was exercised, failing closed" >&2
      exit 1
    fi
    if ! echo "$out" | grep -Eq "test tests::start_mockserver_container \.\.\. ok"; then
      echo "+++ :bangbang: rust container-start test did not run/pass — failing closed" >&2
      exit 1
    fi

    cargo clippy --all-targets -- -D warnings
  '
