# Release Component: testcontainers-mockserver (Rust / crates.io)

## Component script body (`scripts/release/components/testcontainers-rust.sh`)

```bash
#!/usr/bin/env bash
set -euo pipefail

RELEASE_VERSION="${RELEASE_VERSION:?}"
CRATE_DIR="mockserver-testcontainers/rust"

# Update the version in Cargo.toml only. MOCKSERVER_VERSION is defined as
# env!("CARGO_PKG_VERSION"), so it follows the Cargo.toml bump automatically —
# no source-constant edit is required.
sed -i "s/^version = .*/version = \"${RELEASE_VERSION}\"/" "${CRATE_DIR}/Cargo.toml"

# Retrieve token
CARGO_TOKEN=$(aws secretsmanager get-secret-value \
  --secret-id mockserver-release/crates \
  --query SecretString --output text \
  --region eu-west-2 | jq -r .CARGO_TOKEN)

# Publish
cd "${CRATE_DIR}"
cargo publish --token "${CARGO_TOKEN}"
```

## Liveness check (`scripts/release/components/verify.sh` entry)

```bash
# testcontainers-mockserver (crates.io)
curl -sf "https://crates.io/api/v1/crates/testcontainers-mockserver/${RELEASE_VERSION}" \
  | jq -e '.version.num == "'"${RELEASE_VERSION}"'"'
```
