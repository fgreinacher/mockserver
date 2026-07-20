#!/usr/bin/env bash
# .NET client integration tests against a live MockServer built from HEAD.
#
# IntegrationTests.cs calls Skip.If(_client == null) when MOCKSERVER_URL is
# unset. dotnet-unit-test.sh never set it, so every integration test skipped
# on every CI build while the step reported green.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../with-mockserver.sh
source "$SCRIPT_DIR/../with-mockserver.sh"

# NOT `exec`: exec replaces this shell and would discard the EXIT trap that
# with-mockserver.sh registered, orphaning the server container and network.
"$SCRIPT_DIR/../run-in-docker.sh" \
  -i mcr.microsoft.com/dotnet/sdk:10.0 \
  -w /build/mockserver-client-dotnet \
  --network "$MOCKSERVER_NETWORK" \
  -e "MOCKSERVER_URL=$MOCKSERVER_URL" \
  -e "MOCKSERVER_REQUIRE_SERVER=true" \
  -- bash -c "dotnet test test/MockServer.Client.IntegrationTests --logger 'trx;LogFileName=integration.trx' --results-directory test-reports"
