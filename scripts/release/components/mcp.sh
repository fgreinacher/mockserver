#!/usr/bin/env bash
# Publish the MockServer MCP server manifest (server.json) to the official MCP
# registry (registry.modelcontextprotocol.io).
#
# Namespace: com.mock-server/mockserver — a DNS-verified namespace derived from
# the mock-server.com domain we own. Authentication is fully non-interactive
# (an ed25519 private key in Secrets Manager + a one-time DNS TXT record), so
# unlike the old io.github.mock-server/* namespace (interactive GitHub OAuth)
# this can run hands-off in Buildkite.
#
# FAILURE POLICY (no silent error-swallowing):
#   - Downloading + verifying the mcp-publisher CLI, the DNS login, and the
#     publish ARE the real actions. Network ops are wrapped in `retry` and
#     HARD-fail on a genuine error.
#   - The download checksum is a SECURITY control: when MCP_PUBLISHER_SHA256 is
#     set, a mismatch HARD-fails (never tolerated). Pin it on the agent to make
#     the verification mandatory; if unset, the gap is surfaced with a :warning:
#     (not silently skipped).
#   - The DNS key secret (mockserver-release/mcp-dns-key) EXISTS — a missing key
#     or a login/auth failure is a real misconfiguration and HARD-fails.
#   - KNOWN ordering dependency: the registry verifies that the referenced
#     Docker Hub image carries the ownership label, and that image is published
#     by an earlier step. If the image is not yet visible the publish fails with
#     a recognisable "image/label not found" error — that specific case is
#     eventually-consistent, so it is retry-then-tolerate with a :warning:. Any
#     OTHER publish error (auth, schema, registry 5xx after retries) HARD-fails.
#
# Dry-run: validate the committed server.json only; never mutate it; skip
# auth + publish.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$SCRIPT_DIR/_lib.sh"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY_RUN=true; shift ;;
    --execute) DRY_RUN=false; shift ;;
    -h|--help) echo "Usage: $0 [--dry-run|--execute]"; exit 0 ;;
    *) log_error "Unknown arg: $1"; exit 2 ;;
  esac
done

require_cmd jq
require_cmd curl
require_release_inputs
skip_unless_release_type "mcp" full,post-maven

log_step "Publish MCP server manifest $RELEASE_VERSION (dry-run=$DRY_RUN)"
sync_to_origin_master

SERVER_JSON="$REPO_ROOT/server.json"
SERVER_NAME="com.mock-server/mockserver"
MCP_DOMAIN="mock-server.com"
IMAGE_REF="docker.io/mockserver/mockserver:${RELEASE_VERSION}"
# Pin the CLI version; pin MCP_PUBLISHER_SHA256 on the agent to make the
# download checksum-verified (a security control). When set, a mismatch is HARD;
# when unset, the gap is surfaced with a :warning:.
# v1.7.0+ is the first line that ships the `validate` subcommand used below; the
# old 1.2.3 pin only had init/login/logout/publish, so `validate` hard-failed
# with "Unknown command: validate". Note the release asset-name scheme also
# changed at v1.7.0 (no version in the filename) — see the download URL; pinning
# back to a 1.6.x or older version would 404 against the URL below.
# To enforce the download checksum, set on the agent (linux/amd64, v1.7.9):
#   MCP_PUBLISHER_SHA256=ab128162b0616090b47cf245afe0a23f3ef08936fdce19074f5ba0a4469281ac
MCP_PUBLISHER_VERSION="${MCP_PUBLISHER_VERSION:-1.7.9}"
MCP_PUBLISHER_SHA256="${MCP_PUBLISHER_SHA256:-}"

# A missing committed manifest is a real defect — HARD-fail.
[[ -f "$SERVER_JSON" ]] || { log_error "$SERVER_JSON missing — cannot publish MCP manifest"; exit 1; }

# ---- Locate the mcp-publisher CLI ------------------------------------------
# Prefer one already on the agent; otherwise download the pinned release into
# .tmp/. The download is a network op (retry) and the checksum is HARD.
MCP_BIN=""
if command -v mcp-publisher >/dev/null 2>&1; then
  MCP_BIN="mcp-publisher"
else
  os="linux"; arch="amd64"
  case "$(uname -m)" in aarch64|arm64) arch="arm64" ;; esac
  case "$(uname -s)" in Darwin) os="darwin" ;; esac
  # Asset name carries no version (changed from the 1.2.x `..._${ver}_${os}_${arch}`
  # scheme); the tag still does.
  url="https://github.com/modelcontextprotocol/registry/releases/download/v${MCP_PUBLISHER_VERSION}/mcp-publisher_${os}_${arch}.tar.gz"
  log_info "mcp-publisher not on PATH — downloading v${MCP_PUBLISHER_VERSION} ($os/$arch)"
  mkdir -p "$REPO_ROOT/.tmp"
  # HARD-fail (with retry) on a real download error — without the CLI we cannot
  # publish, and a failed fetch is not eventually-consistent.
  retry 3 5 -- curl -fsSL --max-time 120 -o "$REPO_ROOT/.tmp/mcp-publisher.tgz" "$url"
  # Checksum is a SECURITY control: a mismatch HARD-fails (never tolerated).
  if [[ -n "$MCP_PUBLISHER_SHA256" ]]; then
    if ! echo "${MCP_PUBLISHER_SHA256}  $REPO_ROOT/.tmp/mcp-publisher.tgz" | sha256sum -c - >/dev/null 2>&1; then
      log_error "mcp-publisher checksum mismatch — refusing the download"
      rm -f "$REPO_ROOT/.tmp/mcp-publisher.tgz"
      exit 1
    fi
    log_info "mcp-publisher download checksum verified"
  else
    log_info ":warning: MCP_PUBLISHER_SHA256 unset — download NOT checksum-verified; pin it to harden"
  fi
  # A corrupt/unpackable archive is a real failure — HARD.
  if ! tar -xzf "$REPO_ROOT/.tmp/mcp-publisher.tgz" -C "$REPO_ROOT/.tmp" mcp-publisher; then
    log_error "could not unpack mcp-publisher archive"
    exit 1
  fi
  chmod +x "$REPO_ROOT/.tmp/mcp-publisher"
  MCP_BIN="$REPO_ROOT/.tmp/mcp-publisher"
fi

# ---- Dry-run: validate the committed manifest, mutate nothing --------------
if is_dry_run; then
  log_info "Validate committed server.json against the live registry schema"
  if "$MCP_BIN" validate "$SERVER_JSON"; then
    log_info "server.json valid (name=$(jq -r .name "$SERVER_JSON"), version=$(jq -r .version "$SERVER_JSON"))"
  else
    log_info ":warning: server.json failed schema validation (non-fatal in dry-run)"
  fi
  log_dry "skip: server.json mutation + mcp-publisher login dns + publish"
  exit 0
fi

# ---- Execute: sync server.json to this release -----------------------------
# A jq rewrite failure here is a real defect in our own manifest/tooling, not a
# transient condition — HARD-fail.
log_info "Sync server.json -> version=$RELEASE_VERSION identifier=$IMAGE_REF name=$SERVER_NAME"
TMP_JSON="$REPO_ROOT/.tmp/server.json.$$"
mkdir -p "$REPO_ROOT/.tmp"
if ! jq --arg v "$RELEASE_VERSION" --arg ref "$IMAGE_REF" --arg name "$SERVER_NAME" '
      .name = $name
      | .version = $v
      | .packages[0].identifier = $ref
    ' "$SERVER_JSON" > "$TMP_JSON"; then
  log_error "could not rewrite server.json with jq"
  rm -f "$TMP_JSON"
  exit 1
fi
mv "$TMP_JSON" "$SERVER_JSON"

# Fail fast on the registry's fixed 100-char limit for `description`. The live
# `validate` below already rejects an over-length value, but only as an opaque
# upstream HTTP 422 ("expected length <= 100"). Surface it here with a clear,
# actionable message and the actual length so a manifest edit is obvious — this
# is the one server.json field a human routinely changes. (MCP registry
# constraint, see docs/operations/mcp-registry-publishing.md.)
DESC_LEN=$(jq -r '.description | length' "$SERVER_JSON")
if [[ "$DESC_LEN" -gt 100 ]]; then
  log_error "server.json description is $DESC_LEN chars — the MCP registry allows <= 100. Shorten the \"description\" field in server.json."
  exit 1
fi

log_info "Validate server.json against the live registry schema"
if ! "$MCP_BIN" validate "$SERVER_JSON"; then
  log_error "server.json failed schema validation — refusing to publish"
  exit 1
fi

# ---- Authenticate via DNS (non-interactive) --------------------------------
# Requires a one-time DNS TXT record on $MCP_DOMAIN carrying the ed25519 public
# key, and the matching private key (hex seed) in Secrets Manager. The secret
# EXISTS, so its absence is a real misconfiguration — HARD-fail.
if ! aws secretsmanager describe-secret --region "$REGION" \
     --secret-id mockserver-release/mcp-dns-key >/dev/null 2>&1; then
  log_error "MCP DNS key secret (mockserver-release/mcp-dns-key) not found — see docs/operations/mcp-registry-publishing.md"
  exit 1
fi

log_info "Authenticate to the MCP registry via DNS ($MCP_DOMAIN)"
# mcp-publisher accepts the ed25519 key ONLY via the -private-key flag (hex);
# it has no file/env input (verified: `mcp-publisher login --help`). The key is
# therefore briefly on the argv of the login subprocess. We bound the exposure:
# the script runs with no xtrace (the value never reaches the Buildkite log),
# output is redirected, the value is unset immediately after, and the release
# agent is single-tenant + ephemeral (only same-uid root could read
# /proc/PID/cmdline during the sub-second login). `login` then writes a token to
# its own config; `publish` below uses that token, not the key.
mcp_key=""
if ! mcp_key=$(load_secret "mockserver-release/mcp-dns-key" "private_key"); then
  log_error "could not read MCP DNS private key from Secrets Manager"
  exit 1
fi
# Login is an auth network op: retry transient errors, HARD-fail a real auth
# failure. Keep the key off the log (>/dev/null) and unset it right after.
login_rc=0
retry 3 5 -- "$MCP_BIN" login dns --domain "$MCP_DOMAIN" --private-key "$mcp_key" >/dev/null 2>&1 || login_rc=$?
mcp_key=""; unset mcp_key
if [[ "$login_rc" -ne 0 ]]; then
  log_error "mcp-publisher DNS login failed (check the TXT record on $MCP_DOMAIN and the key)"
  exit 1
fi

# ---- Publish ----------------------------------------------------------------
# Registry preconditions (enforced server-side): the referenced image must
# carry LABEL io.modelcontextprotocol.server.name="com.mock-server/mockserver"
# (set in docker/local/Dockerfile — the buildx context the docker step actually
# builds + pushes as mockserver/mockserver:<ver>; NOT docker/Dockerfile, which
# is not used by the release). The docker step publishes that image before this
# step runs, so on a full/post-maven release the label is live.
#
# KNOWN eventually-consistent case: if the $RELEASE_VERSION image with the
# ownership label is not yet visible on Docker Hub, the registry rejects the
# publish with a recognisable "image/label not found" error. THAT case is
# tolerated (retry-then-:warning:) because it is genuine propagation lag. Any
# OTHER publish failure (auth, schema, registry 5xx persisting past retries) is
# a real failure and HARD-fails.
log_info "Publish to registry.modelcontextprotocol.io"
PUBLISH_LOG="$REPO_ROOT/.tmp/mcp-publish.$$.log"
publish_rc=0
retry 3 5 -- "$MCP_BIN" publish >"$PUBLISH_LOG" 2>&1 || publish_rc=$?

if [[ "$publish_rc" -eq 0 ]]; then
  log_info "MCP server published: $SERVER_NAME @ $RELEASE_VERSION"
  rm -f "$PUBLISH_LOG"
elif grep -qi 'duplicate version' "$PUBLISH_LOG"; then
  # Idempotent rerun: this exact version is already live in the registry (a
  # previous build published it). That IS the desired end state — succeed.
  log_info "MCP registry already has $SERVER_NAME @ $RELEASE_VERSION (duplicate version) — treating as published"
  rm -f "$PUBLISH_LOG"
elif grep -qiE 'image .*not found|label .*not found|ownership|not.*visible|no such (image|manifest)|denied.*manifest' "$PUBLISH_LOG"; then
  # Eventually-consistent: the ownership-labelled image has not propagated yet.
  # Surface, don't swallow — the docker step already pushed it; it will appear.
  log_info ":warning: MCP publish failed because the $RELEASE_VERSION image with the ownership label is not yet visible on Docker Hub — non-fatal (eventually consistent; re-run the mcp step once the image propagates)"
  sed 's/^/    /' "$PUBLISH_LOG" >&2 || true
  rm -f "$PUBLISH_LOG"
else
  # Any other failure is a genuine publish error — HARD-fail.
  log_error "mcp-publisher publish failed (not the known image-propagation case)"
  cat "$PUBLISH_LOG" >&2 || true
  rm -f "$PUBLISH_LOG"
  exit 1
fi

# Commit the synced server.json so the repo reflects the published manifest.
# A commit failure is a real problem (the repo would drift from what was
# published) — HARD-fail.
git_commit_and_push "release: MCP manifest $RELEASE_VERSION" server.json

log_info "MCP publish complete"
