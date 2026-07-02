'use strict';

/*
 * Unit tests for the two mock-drift control-plane client helpers
 * retrieveDrift() and clearDrift() (no running server). The HTTP transport
 * module (sendRequest.js) is mocked so the tests assert the exact method +
 * path the client uses and that the drift report body is parsed.
 *
 *   GET /mockserver/drift        — resolves the parsed { count, drifts } report.
 *   PUT /mockserver/drift/clear  — resolves once the drift is cleared.
 */

var { describe, it, beforeEach, afterEach } = require('node:test');
var assert = require('node:assert/strict');

var sendRequestModule = require('../../sendRequest');
var mockServerClient = require('../../').mockServerClient;

process.on('unhandledRejection', function () {});

var captured;

// A resolving PUT transport stub: captures the call and resolves {statusCode, body}.
function putStub(responseBody) {
    return function () {
        return function (host, port, path, jsonBody) {
            captured.push({ method: 'PUT', host: host, port: port, path: path, body: jsonBody });
            return Promise.resolve({ statusCode: 200, body: responseBody });
        };
    };
}

// A resolving GET transport stub: captures the call and resolves {statusCode, body}.
function getStub(responseBody) {
    return function () {
        return function (host, port, path) {
            captured.push({ method: 'GET', host: host, port: port, path: path });
            return Promise.resolve({ statusCode: 200, body: responseBody });
        };
    };
}

var originalSend;
var originalGet;

beforeEach(function () {
    captured = [];
    originalSend = sendRequestModule.sendRequest;
    originalGet = sendRequestModule.sendGetRequest;
});

afterEach(function () {
    sendRequestModule.sendRequest = originalSend;
    sendRequestModule.sendGetRequest = originalGet;
});

function clientWith(sendStub, getStubFn) {
    sendRequestModule.sendRequest = sendStub;
    sendRequestModule.sendGetRequest = getStubFn;
    return mockServerClient('localhost', 1080);
}

// =========================================================================
// wiring
// =========================================================================

describe('drift client helpers wiring', function () {
    it('exposes retrieveDrift and clearDrift on a client instance', function () {
        var client = mockServerClient('localhost', 1080);
        assert.equal(typeof client.retrieveDrift, 'function');
        assert.equal(typeof client.clearDrift, 'function');
    });
});

// =========================================================================
// retrieveDrift
// =========================================================================

describe('client.retrieveDrift', function () {
    it('GETs /mockserver/drift and resolves the parsed report', async function () {
        var report = { count: 1, drifts: [{ path: '/foo', driftType: 'STATUS_CODE' }] };
        var client = clientWith(putStub('{}'), getStub(JSON.stringify(report)));

        var returned = await client.retrieveDrift();

        assert.equal(captured.length, 1);
        assert.equal(captured[0].method, 'GET');
        assert.equal(captured[0].path, '/mockserver/drift');
        assert.deepEqual(returned, report);
    });

    it('resolves an empty object when the body is empty', async function () {
        var client = clientWith(putStub('{}'), getStub(''));
        var returned = await client.retrieveDrift();
        assert.deepEqual(returned, {});
    });
});

// =========================================================================
// clearDrift
// =========================================================================

describe('client.clearDrift', function () {
    it('PUTs /mockserver/drift/clear', async function () {
        var client = clientWith(putStub('{"status":"cleared"}'), getStub('{}'));

        await client.clearDrift();

        assert.equal(captured.length, 1);
        assert.equal(captured[0].method, 'PUT');
        assert.equal(captured[0].path, '/mockserver/drift/clear');
    });
});
