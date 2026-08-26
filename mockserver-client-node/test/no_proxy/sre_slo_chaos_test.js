'use strict';

/*
 * Unit tests for the two SRE control-plane client helpers verifySLO(...) and
 * startChaosExperiment(...) (no running server). The HTTP transport module
 * (sendRequest.js) is mocked so the tests assert the exact method + path +
 * request body the client PUTs, and the resolve/reject semantics of the
 * verdict-carrying verifySLO endpoint.
 *
 *   PUT /mockserver/verifySLO      — 200 PASS/INCONCLUSIVE resolves the verdict,
 *                                    406 FAIL rejects with the verdict body,
 *                                    400 disabled/invalid rejects with guidance.
 *   PUT /mockserver/chaosExperiment — resolves the started status.
 */

var { describe, it, beforeEach, afterEach } = require('node:test');
var assert = require('node:assert/strict');

var sendRequestModule = require('../../sendRequest');
var mockServerClient = require('../../').mockServerClient;

process.on('unhandledRejection', function () {});

var captured;

// A resolving transport stub: captures the call and resolves {statusCode, body}.
function resolveStub(responseBody) {
    return function () {
        return function (host, port, path, jsonBody) {
            captured.push({
                host: host, port: port, path: path,
                body: (typeof jsonBody === 'string' || jsonBody === undefined)
                    ? jsonBody
                    : JSON.parse(JSON.stringify(jsonBody))
            });
            return Promise.resolve({ statusCode: 200, body: responseBody });
        };
    };
}

// A rejecting transport stub: mirrors sendRequest.js, which rejects a >= 400
// response with the raw response body string.
function rejectStub(responseBody) {
    return function () {
        return function (host, port, path, jsonBody) {
            captured.push({
                host: host, port: port, path: path,
                body: (typeof jsonBody === 'string' || jsonBody === undefined)
                    ? jsonBody
                    : JSON.parse(JSON.stringify(jsonBody))
            });
            return Promise.reject(responseBody);
        };
    };
}

var originalSend;

beforeEach(function () {
    captured = [];
    originalSend = sendRequestModule.sendRequest;
});

afterEach(function () {
    sendRequestModule.sendRequest = originalSend;
});

function clientWith(stub) {
    sendRequestModule.sendRequest = stub;
    return mockServerClient('localhost', 1080);
}

// =========================================================================
// wiring
// =========================================================================

describe('SRE client helpers wiring', function () {
    it('exposes verifySLO and startChaosExperiment on a client instance', function () {
        var client = mockServerClient('localhost', 1080);
        assert.equal(typeof client.verifySLO, 'function');
        assert.equal(typeof client.startChaosExperiment, 'function');
    });
});

// =========================================================================
// verifySLO
// =========================================================================

describe('client.verifySLO', function () {
    var criteria = {
        name: 'checkout-slo',
        window: { type: 'LOOKBACK', lookbackMillis: 60000 },
        minimumSampleCount: 10,
        upstreamHosts: ['api.example.com'],
        objectives: [
            { sli: 'LATENCY_P95', comparator: 'LESS_THAN', threshold: 250, scope: 'FORWARD' },
            { sli: 'ERROR_RATE', comparator: 'LESS_THAN_OR_EQUAL', threshold: 0.01 }
        ]
    };

    it('PUTs the criteria to /mockserver/verifySLO and resolves a PASS verdict', async function () {
        var verdict = { name: 'checkout-slo', result: 'PASS', sampleCount: 42, objectiveResults: [] };
        var verdictReturned = await clientWith(resolveStub(JSON.stringify(verdict))).verifySLO(criteria);

        assert.equal(captured.length, 1);
        assert.equal(captured[0].path, '/mockserver/verifySLO');
        assert.deepEqual(captured[0].body, criteria);
        assert.deepEqual(verdictReturned, verdict);
    });

    it('resolves an INCONCLUSIVE verdict on 200', async function () {
        var verdict = { name: 'checkout-slo', result: 'INCONCLUSIVE', sampleCount: 1 };
        var verdictReturned = await clientWith(resolveStub(JSON.stringify(verdict))).verifySLO(criteria);
        assert.equal(verdictReturned.result, 'INCONCLUSIVE');
    });

    it('rejects with the verdict body on a FAIL (406) response', async function () {
        var failBody = JSON.stringify({ name: 'checkout-slo', result: 'FAIL', objectiveResults: [{ sli: 'LATENCY_P95', result: 'FAIL' }] });
        var rejected = false;
        try {
            await clientWith(rejectStub(failBody)).verifySLO(criteria);
        } catch (err) {
            rejected = true;
            assert.ok(String(err).indexOf('SLO verdict FAIL') !== -1);
            assert.ok(String(err).indexOf('LATENCY_P95') !== -1);
        }
        assert.ok(rejected, 'expected verifySLO to reject on FAIL');
    });

    it('rejects with enablement guidance on a disabled (403) response', async function () {
        // the real server body — the guidance lives in the server's own error message, which the
        // client surfaces verbatim rather than guessing from a status it cannot see on this path
        var rejected = false;
        try {
            await clientWith(rejectStub('{"error":"SLO tracking not enabled (set sloTrackingEnabled=true)"}')).verifySLO(criteria);
        } catch (err) {
            rejected = true;
            assert.ok(String(err).indexOf('sloTrackingEnabled=true') !== -1);
        }
        assert.ok(rejected, 'expected verifySLO to reject when disabled');
    });

    it('rejects naming the criteria on a malformed (400) response', async function () {
        var rejected = false;
        try {
            await clientWith(rejectStub('{"error":"objectives must be an array"}')).verifySLO(criteria);
        } catch (err) {
            rejected = true;
            assert.ok(String(err).indexOf('objectives must be an array') !== -1);
        }
        assert.ok(rejected, 'expected verifySLO to reject on malformed criteria');
    });
});

// =========================================================================
// startChaosExperiment
// =========================================================================

describe('client.startChaosExperiment', function () {
    var experiment = {
        name: 'rolling-brownout',
        loop: false,
        stages: [
            {
                durationMillis: 30000,
                profiles: {
                    'api.example.com': { errorStatus: 503, errorProbability: 0.25 }
                }
            },
            {
                durationMillis: 30000,
                profiles: {
                    'api.example.com': { latency: { value: 500, timeUnit: 'MILLISECONDS' } }
                }
            }
        ]
    };

    it('PUTs the experiment to /mockserver/chaosExperiment and resolves the started status', async function () {
        var status = { status: 'started', name: 'rolling-brownout' };
        var statusReturned = await clientWith(resolveStub(JSON.stringify(status))).startChaosExperiment(experiment);

        assert.equal(captured.length, 1);
        assert.equal(captured[0].path, '/mockserver/chaosExperiment');
        assert.deepEqual(captured[0].body, experiment);
        assert.deepEqual(statusReturned, status);
    });

    it('rejects on an error response', async function () {
        var rejected = false;
        try {
            await clientWith(rejectStub('chaos experiment invalid')).startChaosExperiment(experiment);
        } catch (err) {
            rejected = true;
            assert.ok(String(err).indexOf('chaos experiment invalid') !== -1);
        }
        assert.ok(rejected, 'expected startChaosExperiment to reject on error');
    });
});
