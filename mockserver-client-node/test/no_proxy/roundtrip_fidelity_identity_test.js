'use strict';

/*
 * Runtime JSON-identity sanity check for the cross-language client fidelity
 * harness (manifest key "node").
 *
 * The Node client is a thin passthrough with NO runtime expectation model: it
 * serialises whatever object it is handed straight to JSON and posts it to the
 * server, applying no transform. There is therefore no runtime round-trip that
 * could drop a field (unlike the typed clients) — the only runtime invariant
 * worth asserting is that every shared fixture is *stable* JSON, i.e. that
 * `JSON.parse(JSON.stringify(fixture))` deep-equals the parsed fixture.
 *
 * This guards the fixtures themselves (no NaN/Infinity, no accidental
 * non-JSON-representable values, no key-order-dependent parsing) so the other
 * language harnesses can rely on them. The TYPE-level fidelity gate lives in
 * test/roundtrip_fidelity_types.ts and is checked by `npx tsc`.
 *
 * Needs no running server.
 */

var { describe, it } = require('node:test');
var assert = require('node:assert/strict');
var fs = require('fs');
var path = require('path');

// test/no_proxy -> test -> mockserver-client-node -> <repo root>
var FIXTURES_DIR = path.resolve(__dirname, '..', '..', '..', 'test-fixtures', 'expectations');

function listFixtures() {
    return fs
        .readdirSync(FIXTURES_DIR)
        .filter(function (f) {
            return /\.json$/.test(f) && f !== 'known-gaps.json';
        })
        .sort();
}

describe('expectation fixture JSON-identity', function () {
    var fixtures = listFixtures();

    it('finds the shared expectation fixtures', function () {
        assert.ok(fixtures.length > 0, 'expected at least one fixture in ' + FIXTURES_DIR);
    });

    fixtures.forEach(function (fileName) {
        it(fileName + ' round-trips through JSON unchanged', function () {
            var raw = fs.readFileSync(path.join(FIXTURES_DIR, fileName), 'utf8');
            var parsed = JSON.parse(raw);
            var roundTripped = JSON.parse(JSON.stringify(parsed));
            assert.deepEqual(roundTripped, parsed);
        });
    });
});
