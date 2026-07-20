#!/usr/bin/env bash
#
# Regenerate mockServer.d.ts (the Node client's server-model type declarations).
#
# SOURCE OF TRUTH — READ THIS BEFORE RUNNING
# ------------------------------------------
# This script used to generate from a REMOTE SwaggerHub schema pinned to
# `mock-server-openapi/5.15.x` — three major versions behind the server it is
# supposed to describe, fetched over the network, and not diffed by any CI step.
# Nothing regenerated it, so `mockServer.d.ts` drifted into being hand-maintained
# while this script quietly still claimed to produce it. Running it would have
# silently reverted the types to a 5.15-era contract.
#
# It now generates from the IN-REPO spec, so the input is versioned alongside the
# code and reviewable in the same diff.
#
# IMPORTANT: the in-repo OpenAPI spec is currently INCOMPLETE relative to the
# server's own authoritative expectation schema
# (mockserver-core/.../model/schema/expectation.json). It does not yet declare
# every expectation action the server accepts, so a straight regeneration TODAY
# would DROP actions from mockServer.d.ts and break the type-level fidelity gate
# in test/roundtrip_fidelity_types.ts (measured: 98 tsc errors).
#
# The guard below therefore refuses to overwrite the committed file when the
# regenerated output would lose actions. When the spec is completed, the guard
# passes and this script becomes the normal way to refresh the types.
# The same gap is pinned as a ratchet by test/no_proxy/generated_types_drift_test.js.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLIENT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$CLIENT_DIR/.." && pwd)"

SPEC="$REPO_ROOT/mockserver/mockserver-core/src/main/resources/org/mockserver/openapi/mock-server-openapi-embedded-model.yaml"
SCHEMA="$REPO_ROOT/mockserver/mockserver-core/src/main/resources/org/mockserver/model/schema/expectation.json"
TARGET="$CLIENT_DIR/mockServer.d.ts"

[ -f "$SPEC" ]   || { echo "ERROR: OpenAPI spec not found at $SPEC" >&2; exit 1; }
[ -f "$SCHEMA" ] || { echo "ERROR: expectation schema not found at $SCHEMA" >&2; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "Generating from in-repo spec: ${SPEC#"$REPO_ROOT"/}"
npx --yes swagger-typescript-api@13 generate \
    -n mockServer.d.ts \
    -p "$SPEC" \
    -o "$WORK" \
    -r true \
    --no-client \
    --extract-request-params \
    --extract-request-body

# ---- completeness guard -------------------------------------------------------
# Compare the actions the regenerated Expectation type declares against the actions
# the server's authoritative schema declares. Refuse to overwrite on any loss.
node - "$WORK/mockServer.d.ts" "$SCHEMA" "$CLIENT_DIR/test/lib/action_keys.js" <<'NODE'
var fs = require('fs');
var generated = fs.readFileSync(process.argv[2], 'utf8');

// Same action/non-action split the test gates use — required, not duplicated, so this guard
// and test/no_proxy/*_test.js cannot disagree about what counts as an action.
var serverActions = require(process.argv[4]).serverActionKeys(process.argv[3]);

var block = /export type Expectation = \{([\s\S]*?)^\};/m.exec(generated);
if (!block) {
    console.error('ERROR: could not locate "export type Expectation" in the generated output.');
    process.exit(1);
}
var declared = new Set((block[1].match(/^\s{2}(\w+)\??:/gm) || [])
    .map(function (l) { return /^\s{2}(\w+)/.exec(l)[1]; }));

var lost = serverActions.filter(function (a) { return !declared.has(a); }).sort();

if (lost.length) {
    console.error('');
    console.error('REFUSING TO OVERWRITE mockServer.d.ts.');
    console.error('');
    console.error('Regenerating from the in-repo OpenAPI spec would DROP ' + lost.length +
                  ' expectation action(s) that the server actually accepts:');
    lost.forEach(function (a) { console.error('  - ' + a); });
    console.error('');
    console.error('The spec, not the generator, is what is behind. Add these actions to');
    console.error('  mockserver/mockserver-core/src/main/resources/org/mockserver/openapi/');
    console.error('    mock-server-openapi-embedded-model.yaml');
    console.error('(and its published twin jekyll-www.mock-server.com/mockserver-openapi.yaml),');
    console.error('then re-run this script.');
    console.error('');
    process.exit(1);
}
console.log('Completeness guard passed: all ' + serverActions.length +
            ' server actions are present in the regenerated type.');
NODE

LICENCE="/*
 * mockserver
 * http://mock-server.com
 *
 * Copyright (c) 2014 James Bloom
 * Licensed under the Apache License, Version 2.0
 */
"

echo "${LICENCE}" | cat - "$WORK/mockServer.d.ts" > "$TARGET"
echo "Wrote ${TARGET#"$REPO_ROOT"/}"
