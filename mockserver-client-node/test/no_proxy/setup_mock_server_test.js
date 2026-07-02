'use strict';

// Behavioural test for the framework-agnostic setupMockServer() helper.
// Stubs the `mockserver-node` launcher via the require cache and stands a tiny
// local HTTP server in for MockServer, so it runs without downloading or
// starting a real MockServer jar. It asserts that:
//   * setupMockServer() invokes start_mockserver with the given port,
//   * the returned client talks to that port (a reset() issues PUT /mockserver/reset),
//   * stop() invokes stop_mockserver and is idempotent,
//   * the helper is re-exported from the package index.

var { describe, it } = require('node:test');
var assert = require('node:assert/strict');
var http = require('http');

// ---- stub `mockserver-node` before requiring the helper ----
var calls = { start: [], stop: [] };
var stubPath = require.resolve('mockserver-node');
require.cache[stubPath] = {
    id: stubPath,
    filename: stubPath,
    loaded: true,
    exports: {
        start_mockserver: function (options) {
            calls.start.push(options);
            return Promise.resolve();
        },
        stop_mockserver: function (options) {
            calls.stop.push(options);
            return Promise.resolve();
        }
    }
};

var { setupMockServer } = require('../../setupMockServer');
var index = require('../../');

function startStubServer() {
    return new Promise(function (resolve) {
        var hits = [];
        var server = http.createServer(function (req, res) {
            hits.push({ method: req.method, url: req.url });
            res.writeHead(200, { 'Content-Type': 'application/json' });
            res.end('[]');
        });
        server.listen(0, '127.0.0.1', function () {
            resolve({ server: server, port: server.address().port, hits: hits });
        });
    });
}

describe('setupMockServer', function () {
    it('is re-exported from the package index', function () {
        assert.equal(typeof index.setupMockServer, 'function');
    });

    it('starts, yields a working client, and stops', async function () {
        var stub = await startStubServer();
        try {
            var handle = await setupMockServer({ serverPort: stub.port, host: '127.0.0.1' });

            // start_mockserver was invoked with the requested port
            assert.equal(calls.start.length, 1);
            assert.equal(calls.start[0].serverPort, stub.port);

            assert.equal(handle.serverPort, stub.port);
            assert.equal(handle.host, '127.0.0.1');
            assert.equal(typeof handle.stop, 'function');

            // the returned client talks to the stubbed server
            await handle.client.reset();
            var resetHit = stub.hits.find(function (h) {
                return h.method === 'PUT' && h.url === '/mockserver/reset';
            });
            assert.ok(resetHit, 'client.reset() should PUT /mockserver/reset on the started port');

            // stop() delegates to the launcher and is idempotent
            await handle.stop();
            await handle.stop();
            assert.equal(calls.stop.length, 1);
            assert.equal(calls.stop[0].serverPort, stub.port);
        } finally {
            stub.server.close();
        }
    });
});
