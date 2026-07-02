#!/usr/bin/env bash
# Post-release verification: hit every public URL we publish to and confirm
# $RELEASE_VERSION is actually live. This is the safety net against silent-
# skip / silent-success failure modes that individual component scripts may
# not surface themselves — exactly the class of bug that left 6.1.0 with
# Javadoc unpublished while the Javadoc step reported "passed" in build #36.
#
# Hard checks (failure aborts the build):
#   Maven Central core + plugin, brew-tar artifact, Docker Hub, npm × 2,
#   PyPI, RubyGems, GitHub Release, Helm chart (tarball + index.yaml),
#   Website, JSON Schema, Javadoc, SwaggerHub (version + default).
#
# Soft checks (warn, don't fail):
#   Homebrew formula — bumped asynchronously by BrewTestBot, may not be
#   live for a few hours after release.
#
# Versioned-site check is skipped when CREATE_VERSIONED_SITE != yes.
#
# Dry-run: skip all URL checks (the artifacts aren't actually published).

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

require_cmd curl
require_cmd jq
require_release_inputs
skip_unless_release_type "verify" full,post-maven

log_step "Post-release verification $RELEASE_VERSION (dry-run=$DRY_RUN)"

if is_dry_run; then
  log_dry "skip: every public URL check"
  exit 0
fi

V="$RELEASE_VERSION"

# SwaggerHub registers specs under the major.minor ".x" label (e.g. 7.0.x), not
# the full patch version — see swaggerhub.sh. Verify against that label.
V_MINOR_REST="${V#*.}"
API_V="${V%%.*}.${V_MINOR_REST%%.*}.x"
# Dot-escaped form for grep -E containment checks of the website's spec links.
API_V_RE="${API_V//./\\.}"

# Failure accumulators — collect ALL failures rather than abort on the first,
# so the operator sees the full picture in one pass. Hard failures fail the
# build; soft failures emit a warning summary.
declare -a HARD_FAILS=()
declare -a SOFT_FAILS=()

# check_http <label> <url> [expected_codes_regex]
# Default expected: 200|301|302 (HEAD redirects on some CDNs).
check_http() {
  local label="$1" url="$2"
  local expected="${3:-200|301|302}"
  local code
  # Send a real User-Agent: crates.io returns 403 to UA-less requests, which made
  # the crates.io checks report a misleading HTTP 403. A UA is harmless for every
  # other host, so set it unconditionally.
  code=$(curl -sS --connect-timeout 10 --max-time 30 \
    -A 'mockserver-release (+https://github.com/mock-server/mockserver-monorepo)' \
    -o /dev/null -w '%{http_code}' -L -I "$url" 2>/dev/null || echo "000")
  if [[ "$code" =~ ^(${expected})$ ]]; then
    log_info "  PASS  $label  (HTTP $code)"
  else
    log_error "  FAIL  $label  (HTTP $code, expected $expected) — $url"
    HARD_FAILS+=("$label")
  fi
}

# check_http_soft — same as check_http but logs WARN and routes failures
# to SOFT_FAILS instead of HARD_FAILS.
check_http_soft() {
  local label="$1" url="$2"
  local expected="${3:-200|301|302}"
  local code
  # Send a real User-Agent: crates.io returns 403 to UA-less requests, which made
  # the crates.io checks report a misleading HTTP 403. A UA is harmless for every
  # other host, so set it unconditionally.
  code=$(curl -sS --connect-timeout 10 --max-time 30 \
    -A 'mockserver-release (+https://github.com/mock-server/mockserver-monorepo)' \
    -o /dev/null -w '%{http_code}' -L -I "$url" 2>/dev/null || echo "000")
  if [[ "$code" =~ ^(${expected})$ ]]; then
    log_info "  PASS  $label  (HTTP $code)"
  else
    log_info "  WARN  $label  (HTTP $code, expected $expected) — $url [soft check]"
    SOFT_FAILS+=("$label")
  fi
}

# check_json <label> <url> <jq-filter-expecting-true>
# Treats a curl-level failure or a jq filter that doesn't return truthy as
# a hard failure.
check_json() {
  local label="$1" url="$2" filter="$3"
  local response
  response=$(curl -sS --connect-timeout 10 --max-time 30 "$url" 2>/dev/null || echo "")
  if [[ -z "$response" ]]; then
    log_error "  FAIL  $label  (empty response from $url)"
    HARD_FAILS+=("$label")
    return
  fi
  if echo "$response" | jq -e "$filter" >/dev/null 2>&1; then
    log_info "  PASS  $label"
  else
    log_error "  FAIL  $label  (jq filter \"$filter\" not truthy) — $url"
    HARD_FAILS+=("$label")
  fi
}

# check_body_contains <label> <url> <grep-pattern>
# Plain-text containment check, for non-JSON endpoints (e.g. Helm index.yaml, HTML pages).
check_body_contains() {
  local label="$1" url="$2" pattern="$3"
  local response
  response=$(curl -sS --connect-timeout 10 --max-time 30 "$url" 2>/dev/null || echo "")
  if [[ -z "$response" ]]; then
    log_error "  FAIL  $label  (empty response from $url)"
    HARD_FAILS+=("$label")
    return
  fi
  if echo "$response" | grep -qE "$pattern"; then
    log_info "  PASS  $label"
  else
    log_error "  FAIL  $label  (pattern not found: $pattern) — $url"
    HARD_FAILS+=("$label")
  fi
}

log_info ""
log_info "Verifying $V is live on every public channel..."

log_info ""
log_info "== Maven Central =="
check_http "mockserver-netty $V pom" \
  "https://repo1.maven.org/maven2/org/mock-server/mockserver-netty/$V/mockserver-netty-$V.pom"
check_http "mockserver-maven-plugin $V pom" \
  "https://repo1.maven.org/maven2/org/mock-server/mockserver-maven-plugin/$V/mockserver-maven-plugin-$V.pom"
check_http "mockserver-netty $V brew-tar (for Homebrew livecheck)" \
  "https://repo1.maven.org/maven2/org/mock-server/mockserver-netty/$V/mockserver-netty-$V-brew-tar.tar"

log_info ""
log_info "== npm =="
check_http "mockserver-node@$V on registry.npmjs.org" \
  "https://registry.npmjs.org/mockserver-node/$V"
check_http "mockserver-client@$V on registry.npmjs.org" \
  "https://registry.npmjs.org/mockserver-client/$V"

log_info ""
log_info "== PyPI =="
check_http "mockserver-client $V on pypi.org" \
  "https://pypi.org/pypi/mockserver-client/$V/json"

log_info ""
log_info "== RubyGems =="
check_json "mockserver-client $V on rubygems.org" \
  "https://rubygems.org/api/v1/versions/mockserver-client.json" \
  "any(.[]?; .number == \"$V\")"

log_info ""
log_info "== Docker Hub =="
check_http "mockserver/mockserver:$V tag" \
  "https://hub.docker.com/v2/repositories/mockserver/mockserver/tags/$V/"

log_info ""
log_info "== Binary bundles (GitHub Release, HARD — every client launcher 404s at runtime without these) =="
# HARD gate: the per-OS bundles are what the Node/Python/Ruby/Go/.NET/Rust client
# launchers download at runtime. 7.0.0 shipped with zero assets (the binary step
# soft-failed and this check was soft), so every launcher 404'd. Treat a missing
# bundle as a release failure so it is caught before users hit it. Two
# representative platforms are enough to detect a wholesale "no assets uploaded".
check_http "mockserver $V linux-x86_64 bundle" \
  "https://github.com/mock-server/mockserver-monorepo/releases/download/mockserver-$V/mockserver-$V-linux-x86_64.tar.gz"
check_http "mockserver $V windows-x86_64 bundle" \
  "https://github.com/mock-server/mockserver-monorepo/releases/download/mockserver-$V/mockserver-$V-windows-x86_64.zip"

log_info ""
log_info "== GHCR mirror (soft — convenience mirror, not a release gate) =="
# GHCR requires a bearer token even to read a public package, so fetch an
# anonymous pull token first, then HEAD the manifest. Soft: the mirror is a
# best-effort convenience surface (see docker.sh MIRROR_GHCR), never a gate.
ghcr_token=$(curl -sS --max-time 20 \
  "https://ghcr.io/token?service=ghcr.io&scope=repository:mock-server/mockserver:pull" 2>/dev/null \
  | jq -r '.token // empty' 2>/dev/null)
if [[ -n "$ghcr_token" ]]; then
  ghcr_code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 20 \
    -H "Authorization: Bearer $ghcr_token" \
    -H "Accept: application/vnd.oci.image.index.v1+json,application/vnd.docker.distribution.manifest.list.v2+json" \
    "https://ghcr.io/v2/mock-server/mockserver/manifests/$V" 2>/dev/null)
  if [[ "$ghcr_code" == "200" ]]; then
    log_info "  PASS  ghcr.io/mock-server/mockserver:$V"
  else
    log_info "  WARN  ghcr.io/mock-server/mockserver:$V returned HTTP ${ghcr_code:-?} [soft] (mirror disabled, lagging, or token absent)"
    SOFT_FAILS+=("GHCR mirror")
  fi
else
  log_info "  WARN  could not obtain GHCR anonymous pull token [soft]"
  SOFT_FAILS+=("GHCR mirror")
fi

log_info ""
log_info "== Helm =="
check_http "mockserver-$V.tgz" \
  "https://www.mock-server.com/mockserver-$V.tgz"
check_body_contains "$V listed in Helm index.yaml" \
  "https://www.mock-server.com/index.yaml" \
  "^[[:space:]]+version:[[:space:]]+\"?${V}\"?$"

# == Helm index integrity (issue #2282 — HARD) ==
# Checking only the just-released $V is insufficient: the original bug was that
# HISTORICAL chart versions (5.14.0/5.15.0/6.0.0) 404'd while still LISTED in
# index.yaml, because a new major/minor release lands in a freshly created
# bucket and the versioned-site mirror failed to carry older .tgz files across.
# helm.sh now self-heals by syncing every chart on each run; this gate proves it
# stuck. Enumerate EVERY .tgz URL the live index advertises and HEAD-check each
# one resolves to a real artifact — a single dangling entry fails the release.
# No yq in this image (only curl + jq + grep/sed, see require_cmd above), so the
# `urls:` list is parsed with grep: helm writes one `    - <url>.tgz` line per
# version (see `helm repo index --url` in helm.sh).
log_info ""
log_info "== Helm index integrity (every listed .tgz must resolve — issue #2282, HARD) =="
index_body=$(curl -sS --connect-timeout 10 --max-time 30 \
  "https://www.mock-server.com/index.yaml" 2>/dev/null || echo "")
if [[ -z "$index_body" ]]; then
  log_error "  FAIL  could not fetch Helm index.yaml for integrity check"
  HARD_FAILS+=("Helm index integrity (fetch)")
else
  # Extract every chart .tgz URL advertised under any entry's urls: list.
  # Portable array fill (no `mapfile` — absent in the bash 3.2 shipped on macOS,
  # where these release scripts are also dry-run tested; see binary.sh).
  index_tgz_urls=()
  while IFS= read -r _tgz; do
    [[ -n "$_tgz" ]] && index_tgz_urls+=("$_tgz")
  done < <(echo "$index_body" | grep -oE 'https?://[^[:space:]"]+\.tgz' | sort -u)
  if [[ ${#index_tgz_urls[@]} -eq 0 ]]; then
    log_error "  FAIL  Helm index.yaml advertised no .tgz URLs (parse error or empty index)"
    HARD_FAILS+=("Helm index integrity (no urls)")
  else
    log_info "  index.yaml advertises ${#index_tgz_urls[@]} chart .tgz URL(s) — HEAD-checking each"
    index_dangling=0
    for tgz_url in "${index_tgz_urls[@]}"; do
      tgz_code=$(curl -sS --connect-timeout 10 --max-time 30 \
        -A 'mockserver-release (+https://github.com/mock-server/mockserver-monorepo)' \
        -o /dev/null -w '%{http_code}' -L -I "$tgz_url" 2>/dev/null || echo "000")
      if [[ "$tgz_code" =~ ^(200|301|302)$ ]]; then
        log_info "    PASS  $tgz_url  (HTTP $tgz_code)"
      else
        log_error "    FAIL  $tgz_url  (HTTP $tgz_code) — listed in index.yaml but does not resolve"
        index_dangling=$((index_dangling + 1))
      fi
    done
    if [[ "$index_dangling" -eq 0 ]]; then
      log_info "  PASS  all ${#index_tgz_urls[@]} index.yaml chart URLs resolve"
    else
      log_error "  FAIL  $index_dangling chart URL(s) listed in index.yaml do NOT resolve (issue #2282)"
      HARD_FAILS+=("Helm index integrity ($index_dangling dangling .tgz)")
    fi
  fi
fi

# == OCI chart publish (issue #2281 — HARD) ==
# The image-mirror block above probes the container IMAGE (ghcr.io/mock-server/
# mockserver). The Helm chart is a SEPARATE OCI artifact at a different repo —
# ghcr.io/mock-server/charts/mockserver — pushed by helm.sh (fix 11bd3808a) and
# never previously verified. Probe it the same way: an anonymous GHCR pull token
# scoped to the chart repo, then HEAD the chart manifest by version tag. HARD:
# unlike the convenience image mirror, the OCI chart is the documented install
# source (`helm pull oci://ghcr.io/mock-server/charts/mockserver`) and Artifact
# Hub listing, so a missing publish is a release defect, not a lagging mirror.
log_info ""
log_info "== Helm OCI chart (ghcr.io/mock-server/charts/mockserver — issue #2281, HARD) =="
chart_ghcr_token=$(curl -sS --max-time 20 \
  "https://ghcr.io/token?service=ghcr.io&scope=repository:mock-server/charts/mockserver:pull" 2>/dev/null \
  | jq -r '.token // empty' 2>/dev/null)
if [[ -n "$chart_ghcr_token" ]]; then
  chart_ghcr_code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 20 \
    -H "Authorization: Bearer $chart_ghcr_token" \
    -H "Accept: application/vnd.oci.image.manifest.v1+json" \
    "https://ghcr.io/v2/mock-server/charts/mockserver/manifests/$V" 2>/dev/null)
  if [[ "$chart_ghcr_code" == "200" ]]; then
    log_info "  PASS  oci://ghcr.io/mock-server/charts/mockserver:$V"
  else
    log_error "  FAIL  oci://ghcr.io/mock-server/charts/mockserver:$V returned HTTP ${chart_ghcr_code:-?} (OCI chart not published — issue #2281)"
    HARD_FAILS+=("Helm OCI chart")
  fi
else
  log_error "  FAIL  could not obtain GHCR pull token for the chart repo (issue #2281)"
  HARD_FAILS+=("Helm OCI chart (token)")
fi

log_info ""
log_info "== GitHub Release =="
check_http "release tag mockserver-$V" \
  "https://github.com/mock-server/mockserver-monorepo/releases/tag/mockserver-$V"

log_info ""
log_info "== Website =="
check_http "main mock-server.com" "https://www.mock-server.com/"
check_http "Javadoc $V apidocs" \
  "https://www.mock-server.com/versions/$V/apidocs/index.html"
# The deployed docs must link to THIS release's OpenAPI spec label (X.Y.x), not
# a stale one. update-version-references.sh bumps mockserver_api_version before
# the website build, but a stale-config build or a botched versioned-site
# snapshot would silently ship docs that point at the previous version's spec
# (as the legacy 6-0 -> 5.15.x and 5-15 -> 5.14.x sites still do). Fail loudly.
check_body_contains "live site links to OpenAPI spec $API_V" \
  "https://www.mock-server.com/mock_server/clearing_and_resetting.html" \
  "mock-server-openapi/${API_V_RE}[\"#/]"

log_info ""
log_info "== JSON Schema =="
check_http "expectation schema" "https://www.mock-server.com/schema/expectation.json"
check_http "expectations schema" "https://www.mock-server.com/schema/expectations.json"

log_info ""
log_info "== SwaggerHub =="
check_http "spec $API_V" \
  "https://api.swaggerhub.com/apis/jamesdbloom/mock-server-openapi/$API_V"
# Default-version check — SwaggerHub's settings/default endpoint returns
# `{"version":"X.Y.x"}` for the current default; if swaggerhub.sh's PUT
# /settings/default step silently failed, this is where we'd surface it.
default_version=$(curl -sS --connect-timeout 10 --max-time 30 \
  "https://api.swaggerhub.com/apis/jamesdbloom/mock-server-openapi/settings/default" 2>/dev/null \
  | jq -r '.version // empty' 2>/dev/null)
if [[ "$default_version" == "$API_V" ]]; then
  log_info "  PASS  SwaggerHub default version is $API_V"
else
  log_error "  FAIL  SwaggerHub default version is '${default_version:-<empty>}', expected '$API_V'"
  HARD_FAILS+=("SwaggerHub default version")
fi

log_info ""
log_info "== Postman collection (soft — convenience mirror, indexing may lag) =="
# Public "Run in Postman" endpoint for the MockServer Control Plane collection — a
# reachability proxy for the published public workspace; not a release gate.
check_http_soft "MockServer Control Plane collection (Run in Postman)" \
  "https://god.gw.postman.com/run-collection/3256712-63a2d67a-46d6-41fd-a544-0535e7393e7d"

if [[ "$CREATE_VERSIONED_SITE" == "yes" ]]; then
  log_info ""
  log_info "== Versioned site =="
  SUBDOMAIN=$(version_to_subdomain "$V")
  check_http "${SUBDOMAIN}.mock-server.com" "https://${SUBDOMAIN}.mock-server.com/"
  # The frozen versioned snapshot must also link to this release's spec label.
  check_body_contains "${SUBDOMAIN}.mock-server.com links to OpenAPI spec $API_V" \
    "https://${SUBDOMAIN}.mock-server.com/mock_server/clearing_and_resetting.html" \
    "mock-server-openapi/${API_V_RE}[\"#/]"
fi

log_info ""
log_info "== Homebrew (soft — bumped asynchronously by BrewTestBot) =="
homebrew_stable=$(curl -sS --connect-timeout 10 --max-time 30 \
  "https://formulae.brew.sh/api/formula/mockserver.json" 2>/dev/null \
  | jq -r '.versions.stable // empty' 2>/dev/null)
if [[ "$homebrew_stable" == "$V" ]]; then
  log_info "  PASS  Homebrew formula at $V"
else
  log_info "  WARN  Homebrew formula at '${homebrew_stable:-<empty>}' (expected $V) — BrewTestBot bumps within a few hours, check again later [soft check]"
  SOFT_FAILS+=("Homebrew formula")
fi

log_info ""
log_info "== MCP registry (soft — discovery surface, not a release gate) =="
# The official registry lists the server under the DNS-verified namespace.
# Soft: publish is soft_fail and can lag the image becoming visible on Docker Hub.
mcp_listed=$(curl -sS --connect-timeout 10 --max-time 30 \
  "https://registry.modelcontextprotocol.io/v0/servers?search=com.mock-server/mockserver" 2>/dev/null \
  | jq -r '[.servers[]? | select(.name=="com.mock-server/mockserver") | .version] | max // empty' 2>/dev/null)
if [[ "$mcp_listed" == "$V" ]]; then
  log_info "  PASS  MCP registry lists com.mock-server/mockserver @ $V"
else
  log_info "  WARN  MCP registry at '${mcp_listed:-<not found>}' (expected $V) [soft] — publish soft-fails / may lag the Docker Hub image"
  SOFT_FAILS+=("MCP registry")
fi

log_info ""
log_info "== Go Client (soft — pkg.go.dev indexing may lag) =="
check_http_soft "Go client module on pkg.go.dev" \
  "https://pkg.go.dev/github.com/mock-server/mockserver-monorepo/mockserver-client-go/v7@v${V}"

log_info ""
log_info "== .NET Client (soft — NuGet indexing may lag) =="
check_http_soft "MockServerClient $V on NuGet" \
  "https://api.nuget.org/v3-flatcontainer/mockserverclient/${V}/mockserverclient.${V}.nupkg"

log_info ""
log_info "== Rust Client (soft — crates.io indexing may lag) =="
check_http_soft "mockserver-client $V on crates.io" \
  "https://crates.io/api/v1/crates/mockserver-client/${V}" "200"

log_info ""
log_info "== PHP Client (soft — Packagist webhook may lag) =="
# `jq -e` exits non-zero when the version key is absent (the normal case while
# the Packagist webhook lags). Under `set -euo pipefail` that non-zero in a
# command substitution aborts the WHOLE verify run mid-PHP-check (it did, in
# release build #50 — the step exited 1 right after this header). `|| true`
# keeps it soft like every other check here, which use `|| echo`/`jq -r //empty`.
php_check=$(curl -sS --connect-timeout 10 --max-time 30 \
  "https://packagist.org/packages/mock-server/mockserver-client.json" 2>/dev/null \
  | jq -e ".package.versions[\"${V}\"]" 2>/dev/null || true)
if [[ -n "$php_check" && "$php_check" != "null" ]]; then
  log_info "  PASS  PHP client $V on Packagist"
else
  log_info "  WARN  PHP client $V not (yet) on Packagist [soft — webhook may be pending]"
  SOFT_FAILS+=("PHP client (Packagist)")
fi

log_info ""
log_info "== @mockserver/testcontainers (npm, soft) =="
check_http_soft "@mockserver/testcontainers@$V on npm" \
  "https://registry.npmjs.org/@mockserver/testcontainers/$V"

log_info ""
log_info "== testcontainers-mockserver (PyPI, soft) =="
check_http_soft "testcontainers-mockserver $V on PyPI" \
  "https://pypi.org/pypi/testcontainers-mockserver/$V/json" "200"

log_info ""
log_info "== MockServer.Testcontainers (NuGet, soft) =="
# Package id is MockServer.Testcontainers (not Testcontainers.MockServer — that
# prefix is NuGet-reserved); the flat-container path is the lowercased id.
check_http_soft "MockServer.Testcontainers $V on NuGet" \
  "https://api.nuget.org/v3-flatcontainer/mockserver.testcontainers/${V}/mockserver.testcontainers.${V}.nupkg"

log_info ""
log_info "== testcontainers-go (soft — pkg.go.dev indexing may lag) =="
check_http_soft "testcontainers-go module on pkg.go.dev" \
  "https://pkg.go.dev/github.com/mock-server/mockserver-monorepo/mockserver-testcontainers/go@v${V}"

log_info ""
log_info "== testcontainers-mockserver (crates.io, soft) =="
check_http_soft "testcontainers-mockserver $V on crates.io" \
  "https://crates.io/api/v1/crates/testcontainers-mockserver/${V}" "200"

log_info ""
log_info "== VS Code extension (soft — Marketplace indexing may lag) =="
check_http_soft "mockserver VS Code extension" \
  "https://marketplace.visualstudio.com/items?itemName=mockserver.mockserver"

log_info ""
log_info "== JetBrains plugin (soft — Marketplace indexing may lag) =="
check_http_soft "mockserver JetBrains plugin" \
  "https://plugins.jetbrains.com/plugin/com.mock-server.mockserver"

log_info ""
log_info "== Summary =="
if [[ ${#HARD_FAILS[@]} -eq 0 ]]; then
  log_info "  All hard checks passed for $V"
  if [[ ${#SOFT_FAILS[@]} -gt 0 ]]; then
    log_info "  Soft check(s) not (yet) green: ${SOFT_FAILS[*]}"
  fi
else
  log_error "  ${#HARD_FAILS[@]} hard check(s) failed: ${HARD_FAILS[*]}"
  if [[ ${#SOFT_FAILS[@]} -gt 0 ]]; then
    log_error "  ${#SOFT_FAILS[@]} soft check(s) also not green: ${SOFT_FAILS[*]}"
  fi
  exit 1
fi

log_info "Post-release verification complete"
