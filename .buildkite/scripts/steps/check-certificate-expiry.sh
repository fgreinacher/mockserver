#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────
# Fail closed when a committed test certificate is about to expire.
#
# MockServer's TLS/mTLS tests are pinned to committed PEM fixtures. Those
# certificates have finite lifetimes, and expiry has historically been
# discovered only by the build going red (2021-06, 2022-01, 2023-02, 2026-05).
# Worse, some fixtures are used as PKIX TrustAnchors, which RFC 5280 §6.1 does
# NOT date-check — so an expired anchor does not fail cleanly, it silently goes
# stale. This guard is the early-warning system that neither of those failure
# modes provides.
#
# What it does, over every git-tracked PEM that contains a certificate:
#   * checks EVERY certificate in a chain file, not just the first;
#   * hard-fails (exit 1) on any cert already expired or expiring within
#     ${CERT_EXPIRY_HARD_FAIL_DAYS} days (default 30);
#   * warns (does not fail) on any cert expiring within
#     ${CERT_EXPIRY_WARN_DAYS} days (default 180), annotating the build when
#     `buildkite-agent` is available;
#   * allow-lists the intentional NEGATIVE fixture expired-leaf-cert.pem AND
#     asserts it really IS expired, so the allow-list can never quietly mask a
#     real regression;
#   * FAILS if any PEM cannot be parsed (a corrupt fixture must not pass as
#     "nothing to check");
#   * FAILS if an allow-list entry names a file that no longer exists (so the
#     allow-list cannot rot into a silent no-op);
#   * asserts the invariant that actually bit us — for every `leaf-cert.pem`,
#     notAfter(leaf) <= notAfter(sibling ca.pem) — so a leaf can never outlive
#     the CA that signed it;
#   * asserts the shipped default CA stays in lockstep — the PKCS#1 and PKCS#8 CA
#     private keys are the same key (by modulus), that key matches the CA
#     certificate, and the two committed CA-certificate copies (module resource +
#     legacy-client compat shim) are byte-identical.
#
# Thresholds are overridable via the environment, which is also how the guard
# is self-tested: set CERT_EXPIRY_HARD_FAIL_DAYS to a value larger than the
# fixtures' remaining life and every cert trips the hard-fail path.
#
# Requires: openssl, git, awk, date. Runs directly on the agent (no Docker).
# ──────────────────────────────────────────────────────────────────────
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
cd "$REPO_ROOT"

HARD_FAIL_DAYS="${CERT_EXPIRY_HARD_FAIL_DAYS:-30}"
WARN_DAYS="${CERT_EXPIRY_WARN_DAYS:-180}"

# Intentional negative fixtures: certificates that are DELIBERATELY expired.
# Each entry is a repo-relative path. The guard both exempts these from the
# hard-fail path AND asserts each is genuinely expired; an entry that is not
# expired, or that no longer exists, fails the build.
ALLOWLIST=(
  "mockserver/mockserver-core/src/test/resources/org/mockserver/authentication/mtls/expired-leaf-cert.pem"
)

HARD_FAIL_SECONDS=$(( HARD_FAIL_DAYS * 86400 ))
WARN_SECONDS=$(( WARN_DAYS * 86400 ))

errors=0
warnings=0
WARN_LINES=()
# Newline-delimited set of allow-list paths actually seen in the sweep
# (kept as a plain string for bash 3.2 compatibility — no associative arrays).
ALLOWLIST_SEEN=$'\n'

is_allowlisted() {
  local path="$1" entry
  for entry in "${ALLOWLIST[@]}"; do
    if [ "$path" = "$entry" ]; then
      return 0
    fi
  done
  return 1
}

# Portable "notAfter as epoch seconds" — GNU date first, BSD date fallback.
cert_end_epoch() {
  local certfile="$1" end
  end=$(openssl x509 -in "$certfile" -enddate -noout 2>/dev/null | cut -d= -f2-)
  [ -n "$end" ] || return 1
  date -u -d "$end" +%s 2>/dev/null && return 0
  date -u -j -f "%b %e %H:%M:%S %Y %Z" "$end" +%s 2>/dev/null && return 0
  return 1
}

echo "--- :lock: checking committed certificate expiry (hard-fail < ${HARD_FAIL_DAYS}d, warn < ${WARN_DAYS}d)"

# Every git-tracked PEM that actually contains a certificate.
PEM_FILES=()
while IFS= read -r f; do
  [ -n "$f" ] || continue
  if grep -q "BEGIN CERTIFICATE" "$f" 2>/dev/null; then PEM_FILES+=( "$f" ); fi
done < <(git ls-files '*.pem')

if [ "${#PEM_FILES[@]}" -eq 0 ]; then
  echo "+++ :bangbang: no git-tracked certificate PEMs found — the sweep matched nothing, failing closed" >&2
  exit 1
fi

workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT

for file in "${PEM_FILES[@]}"; do
  allow=0
  if is_allowlisted "$file"; then
    allow=1
    ALLOWLIST_SEEN="${ALLOWLIST_SEEN}${file}"$'\n'
  fi

  # Split into one file per certificate block so chains are fully checked.
  rm -f "$workdir"/cert-*.pem
  awk -v dir="$workdir" '
    /-----BEGIN CERTIFICATE-----/ { n++; out=sprintf("%s/cert-%03d.pem", dir, n) }
    n>0 { print > out }
  ' "$file"

  shopt -s nullglob
  parts=( "$workdir"/cert-*.pem )
  shopt -u nullglob
  if [ "${#parts[@]}" -eq 0 ]; then
    echo "+++ :bangbang: ${file}: contains BEGIN CERTIFICATE but no cert block could be extracted — parse failure" >&2
    errors=$(( errors + 1 ))
    continue
  fi

  idx=0
  for part in "${parts[@]}"; do
    idx=$(( idx + 1 ))
    label="$file"
    if [ "${#parts[@]}" -gt 1 ]; then label="${file} [cert ${idx}/${#parts[@]}]"; fi

    if ! openssl x509 -in "$part" -noout 2>/dev/null; then
      echo "+++ :bangbang: ${label}: certificate could not be parsed by openssl — failing closed" >&2
      errors=$(( errors + 1 ))
      continue
    fi

    enddate=$(openssl x509 -in "$part" -enddate -noout 2>/dev/null | cut -d= -f2-)

    if [ "$allow" -eq 1 ]; then
      # Negative fixture: must be genuinely expired.
      if openssl x509 -in "$part" -checkend 0 -noout >/dev/null 2>&1; then
        echo "+++ :bangbang: ${label}: allow-listed as an intentional EXPIRED negative fixture, but it is NOT expired (notAfter=${enddate}) — the allow-list is masking a real cert" >&2
        errors=$(( errors + 1 ))
      else
        echo "    :white_check_mark: ${label}: intentional negative fixture, confirmed expired (notAfter=${enddate})"
      fi
      continue
    fi

    if ! openssl x509 -in "$part" -checkend "$HARD_FAIL_SECONDS" -noout >/dev/null 2>&1; then
      if openssl x509 -in "$part" -checkend 0 -noout >/dev/null 2>&1; then
        echo "+++ :bangbang: ${label}: expires within ${HARD_FAIL_DAYS} days (notAfter=${enddate}) — renew now (see .opencode/skills/renew-test-certs/SKILL.md)" >&2
      else
        echo "+++ :bangbang: ${label}: ALREADY EXPIRED (notAfter=${enddate}) — renew now (see .opencode/skills/renew-test-certs/SKILL.md)" >&2
      fi
      errors=$(( errors + 1 ))
    elif ! openssl x509 -in "$part" -checkend "$WARN_SECONDS" -noout >/dev/null 2>&1; then
      msg="${label}: expires within ${WARN_DAYS} days (notAfter=${enddate}) — schedule renewal (see .opencode/skills/renew-test-certs/SKILL.md)"
      echo "    :warning: ${msg}"
      WARN_LINES+=( "$msg" )
      warnings=$(( warnings + 1 ))
    else
      echo "    :white_check_mark: ${label}: OK (notAfter=${enddate})"
    fi
  done
done

# ── Invariant: every leaf-cert.pem must expire at or before its sibling ca.pem ──
echo "--- :link: asserting leaf notAfter <= sibling CA notAfter"
LEAF_FILES=()
for f in "${PEM_FILES[@]}"; do
  case "$f" in */leaf-cert.pem) LEAF_FILES+=( "$f" );; esac
done
for leaf in "${LEAF_FILES[@]}"; do
  ca="$(dirname "$leaf")/ca.pem"
  if [ ! -f "$ca" ]; then
    echo "    :grey_question: ${leaf}: no sibling ca.pem — skipping invariant (leaf is signed by a CA in another directory)"
    continue
  fi
  leaf_epoch=$(cert_end_epoch "$leaf") || { echo "+++ :bangbang: ${leaf}: could not read notAfter — failing closed" >&2; errors=$(( errors + 1 )); continue; }
  ca_epoch=$(cert_end_epoch "$ca")     || { echo "+++ :bangbang: ${ca}: could not read notAfter — failing closed" >&2; errors=$(( errors + 1 )); continue; }
  if [ "$leaf_epoch" -gt "$ca_epoch" ]; then
    echo "+++ :bangbang: ${leaf}: leaf outlives its CA (leaf notAfter $(date -u -d @"$leaf_epoch" 2>/dev/null || echo "$leaf_epoch") > CA notAfter $(date -u -d @"$ca_epoch" 2>/dev/null || echo "$ca_epoch")) — re-issue the leaf with a shorter -days than the CA" >&2
    errors=$(( errors + 1 ))
  else
    echo "    :white_check_mark: ${leaf}: leaf notAfter <= sibling CA notAfter"
  fi
done

# ── Allow-list rot check: every entry must have matched a real, tracked file ──
for entry in "${ALLOWLIST[@]}"; do
  if ! printf '%s' "$ALLOWLIST_SEEN" | grep -Fxq "$entry"; then
    echo "+++ :bangbang: allow-list entry '${entry}' matched no git-tracked certificate PEM — remove it or fix the path (the allow-list must not rot into a no-op)" >&2
    errors=$(( errors + 1 ))
  fi
done

# ── Default-CA lockstep integrity ─────────────────────────────────────────────
# The shipped default CA is spread across several files that MUST be regenerated
# together; nothing else enforces that. If the CA is ever re-minted and only some
# copies are updated, TLS silently breaks. Assert the set stays internally
# consistent:
#   1. the PKCS#1 CA private key and the PKCS#8 CA private key are the SAME key
#      (compare RSA modulus, not bytes — the encodings differ by design);
#   2. that key matches the CA certificate (same modulus);
#   3. the two committed copies of the CA certificate (the module resource and the
#      root-level legacy-client compat shim) stay byte-identical.
echo "--- :key: asserting shipped default-CA files stay in lockstep"
# Paths are overridable from the environment purely so the assertions can be
# self-tested (point one at a mismatched file and confirm the guard goes red),
# exactly like CERT_EXPIRY_HARD_FAIL_DAYS above.
CA_KEY_PKCS1="${CERT_CA_KEY_PKCS1:-mockserver/mockserver-core/src/main/resources/org/mockserver/socket/CertificateAuthorityPrivateKey.pem}"
CA_KEY_PKCS8="${CERT_CA_KEY_PKCS8:-mockserver/mockserver-core/src/main/resources/org/mockserver/socket/PKCS8CertificateAuthorityPrivateKey.pem}"
CA_CERT="${CERT_CA_CERT:-mockserver/mockserver-core/src/main/resources/org/mockserver/socket/CertificateAuthorityCertificate.pem}"
CA_CERT_SHIM="${CERT_CA_CERT_SHIM:-mockserver-core/src/main/resources/org/mockserver/socket/CertificateAuthorityCertificate.pem}"

key_modulus() { openssl rsa -in "$1" -noout -modulus 2>/dev/null; }
cert_modulus() { openssl x509 -in "$1" -noout -modulus 2>/dev/null; }

if [ -f "$CA_KEY_PKCS1" ] && [ -f "$CA_KEY_PKCS8" ] && [ -f "$CA_CERT" ]; then
  m_pkcs1=$(key_modulus "$CA_KEY_PKCS1")
  m_pkcs8=$(key_modulus "$CA_KEY_PKCS8")
  m_cert=$(cert_modulus "$CA_CERT")
  if [ -z "$m_pkcs1" ] || [ -z "$m_pkcs8" ] || [ -z "$m_cert" ]; then
    echo "+++ :bangbang: default-CA: could not read a modulus from the CA key/cert files — failing closed" >&2
    errors=$(( errors + 1 ))
  else
    if [ "$m_pkcs1" != "$m_pkcs8" ]; then
      echo "+++ :bangbang: default-CA: ${CA_KEY_PKCS1} and ${CA_KEY_PKCS8} are DIFFERENT keys (modulus mismatch) — regenerate both encodings from the same key" >&2
      errors=$(( errors + 1 ))
    else
      echo "    :white_check_mark: default-CA: PKCS#1 and PKCS#8 CA keys are the same key"
    fi
    if [ "$m_pkcs8" != "$m_cert" ]; then
      echo "+++ :bangbang: default-CA: ${CA_CERT} does not match the CA private key (modulus mismatch) — cert and key are out of step" >&2
      errors=$(( errors + 1 ))
    else
      echo "    :white_check_mark: default-CA: CA certificate matches the CA private key"
    fi
  fi
else
  echo "+++ :bangbang: default-CA: expected CA key/cert resources are missing — failing closed" >&2
  errors=$(( errors + 1 ))
fi

if [ -f "$CA_CERT" ] && [ -f "$CA_CERT_SHIM" ]; then
  if cmp -s "$CA_CERT" "$CA_CERT_SHIM"; then
    echo "    :white_check_mark: default-CA: the two CA-certificate copies are byte-identical"
  else
    echo "+++ :bangbang: default-CA: ${CA_CERT} and the legacy compat shim ${CA_CERT_SHIM} differ — the shim must stay byte-identical for legacy clients" >&2
    errors=$(( errors + 1 ))
  fi
else
  echo "+++ :bangbang: default-CA: expected CA-certificate copy is missing (${CA_CERT} / ${CA_CERT_SHIM}) — failing closed" >&2
  errors=$(( errors + 1 ))
fi

# ── Surface warnings as a Buildkite annotation when running in CI ──
if [ "$warnings" -gt 0 ] && command -v buildkite-agent >/dev/null 2>&1; then
  {
    echo "**Certificate expiry warnings (${warnings})** — renew before they hard-fail the build:"
    echo
    for line in "${WARN_LINES[@]}"; do echo "- ${line}"; done
  } | buildkite-agent annotate --style warning --context cert-expiry || true
fi

echo "--- :bar_chart: certificate expiry summary: ${#PEM_FILES[@]} PEM file(s) swept, ${warnings} warning(s), ${errors} error(s)"

if [ "$errors" -gt 0 ]; then
  echo "+++ :bangbang: certificate expiry guard FAILED with ${errors} error(s)" >&2
  exit 1
fi

echo "--- :white_check_mark: certificate expiry guard PASSED"
