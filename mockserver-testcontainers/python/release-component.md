# Release Component: testcontainers-mockserver (Python)

## `scripts/release/components/testcontainers-python.sh`

```bash
#!/usr/bin/env bash
set -euo pipefail

COMPONENT_DIR="mockserver-testcontainers/python"
VERSION="${RELEASE_VERSION:?RELEASE_VERSION must be set}"

# Update version in pyproject.toml only. __version__ is read from the installed
# package metadata, and the default image tag is derived at runtime from the
# installed mockserver-client version — neither needs a source-constant bump.
sed -i.bak "s/^version = \".*\"/version = \"${VERSION}\"/" "${COMPONENT_DIR}/pyproject.toml"
rm -f "${COMPONENT_DIR}"/**/*.bak "${COMPONENT_DIR}"/*.bak

# Build
(cd "${COMPONENT_DIR}" && python -m build)

# Publish
PYPI_TOKEN=$(aws secretsmanager get-secret-value \
  --profile mockserver-build \
  --secret-id mockserver-build/pypi \
  --query SecretString --output text)

twine upload "${COMPONENT_DIR}/dist/*" --username __token__ --password "${PYPI_TOKEN}"
```

## Liveness check for `scripts/release/components/verify.sh`

```bash
# testcontainers-mockserver (PyPI)
pip install --no-cache-dir "testcontainers-mockserver==${RELEASE_VERSION}" \
  && python -c "from testcontainers_mockserver import MockServerContainer; assert MockServerContainer().image.endswith('mockserver-${RELEASE_VERSION}')"
```
