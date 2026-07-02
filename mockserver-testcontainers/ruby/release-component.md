# Release Component: testcontainers-mockserver (Ruby)

## `scripts/release/components/tc-ruby.sh`

Publishes the gem to RubyGems, mirroring `tc-python.sh` (PyPI) and `rubygems.sh`
(the MockServer Ruby client). The gem version is bumped in
`lib/testcontainers/mockserver/version.rb`; the default image tag is derived at
runtime from the `mockserver-client` gem version, so no source constant needs a
`sed` bump for the image.

```bash
#!/usr/bin/env bash
set -euo pipefail

COMPONENT_DIR="mockserver-testcontainers/ruby"
VERSION="${RELEASE_VERSION:?RELEASE_VERSION must be set}"

# Bump the gem version constant
sed -i.bak "s/VERSION = \".*\"/VERSION = \"${VERSION}\"/" \
  "${COMPONENT_DIR}/lib/testcontainers/mockserver/version.rb"
rm -f "${COMPONENT_DIR}/lib/testcontainers/mockserver/version.rb.bak"

# Build
(cd "${COMPONENT_DIR}" && gem build testcontainers-mockserver.gemspec)

# Publish
RUBYGEMS_API_KEY=$(aws secretsmanager get-secret-value \
  --profile mockserver-build \
  --secret-id mockserver-build/rubygems \
  --query SecretString --output text)

GEM_HOST_API_KEY="${RUBYGEMS_API_KEY}" \
  gem push "${COMPONENT_DIR}/testcontainers-mockserver-${VERSION}.gem"
```

## Liveness check for `scripts/release/components/verify.sh`

```bash
# testcontainers-mockserver (RubyGems)
curl -sf "https://rubygems.org/api/v1/versions/testcontainers-mockserver.json" \
  | ruby -rjson -e "exit(JSON.parse(STDIN.read).any? { |v| v['number'] == ENV['RELEASE_VERSION'] } ? 0 : 1)"
```

## Registration

Add `tc-ruby` to the `COMPONENTS` list in `scripts/release/release.sh`, a
verify entry in `scripts/release/components/verify.sh`, and a publish step in
`.buildkite/release-pipeline.yml` (mirroring the `tc-python` step). These
pipeline/orchestration files are control-plane changes and require gated review.
