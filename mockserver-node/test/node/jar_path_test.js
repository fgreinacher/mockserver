/*
 * mockserver
 * http://mock-server.com
 *
 * Copyright (c) 2014 James Bloom
 * Licensed under the Apache License, Version 2.0
 */

'use strict';

var test = require('node:test');
var assert = require('node:assert');
var os = require('os');
var path = require('path');
var mockserver = require(__dirname + '/../..');

// The jarPath option / MOCKSERVER_JAR_PATH env var let a caller launch a
// pre-provisioned jar (air-gapped/corporate use, or a jar built from the tree in
// CI) instead of downloading a release. The critical safety property is that a
// configured-but-missing jar fails LOUDLY rather than silently downloading a
// published release - otherwise CI would go green against a stale released jar
// whenever the build that should have produced the local jar failed.

test('start_mockserver rejects loudly when MOCKSERVER_JAR_PATH points at a missing file', async function () {
    var previous = process.env.MOCKSERVER_JAR_PATH;
    var missing = path.join(os.tmpdir(), 'mockserver-does-not-exist-' + process.pid + '-' + Date.now() + '.jar');
    process.env.MOCKSERVER_JAR_PATH = missing;
    try {
        await assert.rejects(
            mockserver.start_mockserver({serverPort: 1097}),
            function (error) {
                assert.match(error.message, /MOCKSERVER_JAR_PATH/, 'error names the source of the configured path');
                assert.match(error.message, /does not exist/, 'error says the jar does not exist');
                assert.match(error.message, /refusing to fall back to downloading a release/, 'error states it did not fall back to a download');
                return true;
            }
        );
    } finally {
        if (previous === undefined) {
            delete process.env.MOCKSERVER_JAR_PATH;
        } else {
            process.env.MOCKSERVER_JAR_PATH = previous;
        }
    }
});

test('start_mockserver rejects loudly when the jarPath option points at a missing file', async function () {
    var missing = path.join(os.tmpdir(), 'mockserver-does-not-exist-' + process.pid + '-' + Date.now() + '.jar');
    await assert.rejects(
        mockserver.start_mockserver({serverPort: 1098, jarPath: missing}),
        function (error) {
            assert.match(error.message, /jarPath option/, 'error names the jarPath option as the source');
            assert.match(error.message, /does not exist/, 'error says the jar does not exist');
            assert.match(error.message, /refusing to fall back to downloading a release/, 'error states it did not fall back to a download');
            return true;
        }
    );
});

test('start_mockserver rejects loudly when the configured jar path is a directory, not a file', async function () {
    await assert.rejects(
        mockserver.start_mockserver({serverPort: 1096, jarPath: os.tmpdir()}),
        function (error) {
            assert.match(error.message, /is not a regular file/, 'error says the path is not a regular file');
            return true;
        }
    );
});
