# Release Component: testcontainers-dotnet

This snippet defines the `components/testcontainers-dotnet.sh` body for the release pipeline.
The orchestrator wires this into `scripts/release/components/` and `.buildkite/release-pipeline.yml`.

## Component Script (`scripts/release/components/testcontainers-dotnet.sh`)

```bash
#!/usr/bin/env bash
set -euo pipefail

COMPONENT_DIR="mockserver-testcontainers/dotnet"
VERSION="${RELEASE_VERSION:?RELEASE_VERSION must be set}"

# Update version in Directory.Build.props only. The default image tag is derived
# at runtime from the assembly informational version (which is set from
# MockServerVersion), so no source-constant edit is required.
sed -i "s|<MockServerVersion>.*</MockServerVersion>|<MockServerVersion>${VERSION}</MockServerVersion>|" \
  "${COMPONENT_DIR}/Directory.Build.props"

# Build, test, pack, push
cd "${COMPONENT_DIR}"
dotnet restore
dotnet build -c Release --no-restore
dotnet test -c Release --no-build --filter "Category!=Integration"
dotnet pack src/Testcontainers.MockServer/Testcontainers.MockServer.csproj \
  -c Release --no-build -o ./artifacts

NUGET_API_KEY=$(aws secretsmanager get-secret-value \
  --secret-id mockserver-release/nuget \
  --query SecretString --output text)

dotnet nuget push ./artifacts/Testcontainers.MockServer.${VERSION}.nupkg \
  --source https://api.nuget.org/v3/index.json \
  -k "${NUGET_API_KEY}"
```

## Liveness Check (`scripts/release/components/verify.sh` entry)

```bash
# testcontainers-dotnet: verify NuGet package is indexed
curl -sf "https://api.nuget.org/v3-flatcontainer/testcontainers.mockserver/${RELEASE_VERSION}/testcontainers.mockserver.${RELEASE_VERSION}.nupkg" -o /dev/null
```
