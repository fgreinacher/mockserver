'use strict';

var crypto = require('crypto');
var { describe, it, before, beforeEach } = require('node:test');
var assert = require('node:assert/strict');
var mockServer = require('../../');
var mockServerClient = mockServer.mockServerClient;
var http = require('http');

// Narrow suppressor for unhandled Q-promise rejections caused by WebSocket
// reconnect attempts during reset()/teardown.  The MockServer Node client
// uses Q promises internally; when a WebSocket connection is closed (e.g.
// by reset()), the reconnect logic in webSocketClient.js fires Q-deferred
// rejections (ECONNREFUSED, "Max reconnect attempts reached", etc.) that
// are unhandled because no consumer is listening at that point.  node:test
// counts every unhandled rejection as a test failure, so we must suppress
// these benign teardown events.  We only suppress rejections whose string
// representation matches known WebSocket/reconnect noise — anything else
// is logged to stderr so real bugs stay visible.
process.on('unhandledRejection', function (reason) {
    var msg = String(reason || '');
    // Known benign patterns from webSocketClient.js teardown:
    //  - ECONNREFUSED: connection refused during reconnect attempt
    //  - "Can't connect to MockServer": wrapper message from webSocketClient.js
    //  - "Max reconnect attempts reached": logged when retries are exhausted
    //  - WebSocket close/error objects serialised by JSON.stringify
    if (/ECONNREFUSED/.test(msg) ||
        /Can't connect to MockServer/.test(msg) ||
        /Max reconnect attempts/.test(msg) ||
        /WebSocket/.test(msg)) {
        // Benign teardown noise — swallow silently.
        return;
    }
    // Unexpected rejection — log it so real bugs are visible.
    console.error('[unhandledRejection — NOT suppressed]', reason);
});

var mockServerHost = process.env.MOCKSERVER_HOST || "localhost";
var mockServerPort = parseInt(process.env.MOCKSERVER_PORT, 10) || 1080;
// External mode: the server runs in a separate Docker container, so tests
// that depend on the server being able to reach the test client (forward
// callbacks) or on binding additional ports that the client can reach
// (port-bind test) cannot work and are skipped.
var isExternalMode = !!process.env.MOCKSERVER_HOST;

function guid() {
    return crypto.randomUUID();
}

function sendRequest(method, host, port, path, jsonBody, headers) {
    return new Promise(function (resolve, reject) {
        var body = (typeof jsonBody === "string" ? jsonBody : JSON.stringify(jsonBody || ""));
        var options = {
            method: method,
            host: host,
            port: port,
            headers: headers || {},
            path: path
        };
        options.headers.Connection = "keep-alive";

        var callback = function (response) {
            var data = '';

            response.on('data', function (chunk) {
                data += chunk;
            });

            response.on('end', function () {
                if (response.statusCode >= 400 && response.statusCode < 600) {
                    if (response.statusCode === 404) {
                        reject("404 Not Found");
                    } else {
                        reject(data);
                    }
                } else {
                    resolve({
                        statusCode: response.statusCode,
                        headers: response.headers,
                        body: data
                    });
                }
            });
        };

        var req = http.request(options, callback);
        if (options.method === "POST") {
            req.write(body);
        }
        req.end();
    });
}

describe('mock server node client (no proxy)', { concurrency: 1 }, function () {
    var client;
    var uuid = guid();

    beforeEach(async function () {
        client = mockServerClient(mockServerHost, mockServerPort);
        // The narrowed unhandledRejection handler (above) suppresses benign
        // Q-promise rejections from WebSocket teardown during reset, so
        // client.reset() can be used directly.
        await client.reset();
    });

    it('should create full expectation with string body', async function () {
        await client.mockAnyResponse({
            'httpRequest': {
                'method': 'POST',
                'path': '/somePath',
                'queryStringParameters': [
                    { 'name': 'test', 'values': ['true'] }
                ],
                'body': { 'type': "STRING", 'string': 'someBody' }
            },
            'httpResponse': {
                'statusCode': 200,
                'body': JSON.stringify({name: 'value'}),
                'delay': { 'timeUnit': 'MILLISECONDS', 'value': 250 }
            },
            'times': { 'remainingTimes': 1, 'unlimited': false }
        });

        // non matching request
        await assert.rejects(
            sendRequest("GET", mockServerHost, mockServerPort, "/otherPath"),
            function (err) { return err === "404 Not Found"; }
        );

        // matching request
        var response = await sendRequest("POST", mockServerHost, mockServerPort, "/somePath?test=true", "someBody");
        assert.equal(response.statusCode, 200);
        assert.equal(response.body, '{"name":"value"}');

        // matching request, but no times remaining
        await assert.rejects(
            sendRequest("POST", mockServerHost, mockServerPort, "/somePath?test=true", "someBody"),
            function (err) { return err === "404 Not Found"; }
        );
    });

    it('should create full expectation with string body over tls', async function () {
        var clientOverTls = mockServerClient(mockServerHost, mockServerPort, undefined, true);
        await clientOverTls.mockAnyResponse({
            'httpRequest': {
                'method': 'POST',
                'path': '/somePath',
                'queryStringParameters': [
                    { 'name': 'test', 'values': ['true'] }
                ],
                'body': { 'type': "STRING", 'string': 'someBody' }
            },
            'httpResponse': {
                'statusCode': 200,
                'body': JSON.stringify({name: 'value'}),
                'delay': { 'timeUnit': 'MILLISECONDS', 'value': 250 }
            },
            'times': { 'remainingTimes': 1, 'unlimited': false }
        });

        var response = await sendRequest("POST", mockServerHost, mockServerPort, "/somePath?test=true", "someBody");
        assert.equal(response.statusCode, 200);
        assert.equal(response.body, '{"name":"value"}');
    });

    it('should create expectations from array', async function () {
        await client.mockAnyResponse([
            { 'httpRequest': { 'path': '/somePathOne' }, 'httpResponse': { 'body': JSON.stringify({name: 'one'}) } },
            { 'httpRequest': { 'path': '/somePathTwo' }, 'httpResponse': { 'body': JSON.stringify({name: 'two'}) } },
            { 'httpRequest': { 'path': '/somePathThree' }, 'httpResponse': { 'body': JSON.stringify({name: 'three'}) } }
        ]);

        var r1 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePathOne");
        assert.equal(r1.statusCode, 200);
        assert.equal(r1.body, '{"name":"one"}');

        var r2 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePathTwo", "someBody");
        assert.equal(r2.statusCode, 200);
        assert.equal(r2.body, '{"name":"two"}');

        var r3 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePathThree", "someBody");
        assert.equal(r3.statusCode, 200);
        assert.equal(r3.body, '{"name":"three"}');
    });

    it('should set default headers on expectation array', async function () {
        client.setDefaultHeaders([
            {"name": "x-test-default", "values": ["default-value"]}
        ]);
        await client.mockAnyResponse([
            { 'httpRequest': { 'path': '/somePathOne' }, 'httpResponse': { 'body': JSON.stringify({name: 'one'}) } },
            { 'httpRequest': { 'path': '/somePathTwo' }, 'httpResponse': { 'body': JSON.stringify({name: 'two'}), 'headers': [ {"name": "x-test", "values": ["test-value"]} ] } },
            { 'httpRequest': { 'path': '/somePathThree' }, 'httpResponse': { 'body': JSON.stringify({name: 'three'}) } }
        ]);

        var r1 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePathOne");
        assert.equal(r1.statusCode, 200);
        assert.equal(r1.body, '{"name":"one"}');
        assert.equal(r1.headers["x-test-default"], "default-value");

        var r2 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePathTwo", "someBody");
        assert.equal(r2.statusCode, 200);
        assert.equal(r2.body, '{"name":"two"}');
        assert.equal(r2.headers["x-test-default"], "default-value");
        assert.equal(r2.headers["x-test"], "test-value");

        var r3 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePathThree", "someBody");
        assert.equal(r3.statusCode, 200);
        assert.equal(r3.body, '{"name":"three"}');
        assert.equal(r3.headers["x-test-default"], "default-value");
    });

    it('should clear default headers', async function () {
        await client.mockAnyResponse({
            'httpRequest': { 'path': '/somePathOne' },
            'httpResponse': { 'body': JSON.stringify({name: 'one'}) }
        });

        var r1 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePathOne");
        assert.equal(r1.statusCode, 200);
        assert.equal(r1.body, '{"name":"one"}');

        client.setDefaultHeaders([], []);
        await client.mockAnyResponse({
            'httpRequest': { 'path': '/somePathTwo' },
            'httpResponse': { 'body': JSON.stringify({name: 'one'}) }
        });

        var r2 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePathTwo");
        assert.equal(r2.statusCode, 200);
        assert.equal(r2.body, '{"name":"one"}');
        assert.ok(!(r2.headers["Content-Type"] || r2.headers["content-type"]));
        assert.ok(!(r2.headers["Cache-Control"] || r2.headers["cache-control"]));
    });

    it('should expose server validation failure', async function () {
        try {
            await client.mockAnyResponse({
                'httpRequest': {
                    'paths': '/somePath',
                    'body': { 'type': "STRING", 'vaue': 'someBody' }
                },
                'httpResponse': {}
            });
            assert.fail("should have thrown");
        } catch (error) {
            // Use substring matching — the server's schema validation message
            // varies across versions.
            assert.ok(error.indexOf("incorrect expectation json format for:") !== -1,
                "should contain preamble, got: " + error);
            assert.ok(error.indexOf("\"paths\" : \"/somePath\"") !== -1,
                "should echo the invalid field, got: " + error);
            assert.ok(error.indexOf("$.httpRequest.paths: is not defined in the schema") !== -1,
                "should flag the unknown property, got: " + error);
            assert.ok(error.indexOf("Documentation: https://mock-server.com/mock_server/creating_expectations.html") !== -1,
                "should link to documentation, got: " + error);
        }
    });

    it('should match on method only', async function () {
        await client.mockAnyResponse({
            'httpRequest': { 'method': 'GET' },
            'httpResponse': { 'statusCode': 200, 'body': JSON.stringify({name: 'first_body'}), 'delay': { 'timeUnit': 'MILLISECONDS', 'value': 250 } },
            'times': { 'remainingTimes': 1, 'unlimited': false }
        });
        await client.mockAnyResponse({
            'httpRequest': { 'method': 'POST' },
            'httpResponse': { 'statusCode': 200, 'body': JSON.stringify({name: 'second_body'}), 'delay': { 'timeUnit': 'MILLISECONDS', 'value': 250 } },
            'times': { 'remainingTimes': 1, 'unlimited': false }
        });

        await assert.rejects(sendRequest("PUT", mockServerHost, mockServerPort, "/somePath"), function (err) { return err === "404 Not Found"; });

        var r1 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePath");
        assert.equal(r1.statusCode, 200);
        assert.equal(r1.body, '{"name":"first_body"}');

        var r2 = await sendRequest("POST", mockServerHost, mockServerPort, "/somePath");
        assert.equal(r2.statusCode, 200);
        assert.equal(r2.body, '{"name":"second_body"}');
    });

    it('should match on path only', async function () {
        await client.mockAnyResponse({
            'httpRequest': { 'path': '/firstPath' },
            'httpResponse': { 'statusCode': 200, 'body': JSON.stringify({name: 'first_body'}), 'delay': { 'timeUnit': 'MILLISECONDS', 'value': 250 } },
            'times': { 'remainingTimes': 1, 'unlimited': false }
        });
        await client.mockAnyResponse({
            'httpRequest': { 'path': '/secondPath' },
            'httpResponse': { 'statusCode': 200, 'body': JSON.stringify({name: 'second_body'}), 'delay': { 'timeUnit': 'MILLISECONDS', 'value': 250 } },
            'times': { 'remainingTimes': 1, 'unlimited': false }
        });

        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/otherPath"), function (err) { return err === "404 Not Found"; });

        var r1 = await sendRequest("GET", mockServerHost, mockServerPort, "/firstPath");
        assert.equal(r1.statusCode, 200);
        assert.equal(r1.body, '{"name":"first_body"}');

        var r2 = await sendRequest("GET", mockServerHost, mockServerPort, "/secondPath");
        assert.equal(r2.statusCode, 200);
        assert.equal(r2.body, '{"name":"second_body"}');
    });

    it('should match on query string parameters only', async function () {
        await client.mockAnyResponse({
            'httpRequest': { 'queryStringParameters': [ { 'name': 'param', 'values': ['first'] } ] },
            'httpResponse': { 'statusCode': 200, 'body': JSON.stringify({name: 'first_body'}), 'delay': { 'timeUnit': 'MILLISECONDS', 'value': 250 } },
            'times': { 'remainingTimes': 1, 'unlimited': false }
        });
        await client.mockAnyResponse({
            'httpRequest': { 'queryStringParameters': [ { 'name': 'param', 'values': ['second'] } ] },
            'httpResponse': { 'statusCode': 200, 'body': JSON.stringify({name: 'second_body'}), 'delay': { 'timeUnit': 'MILLISECONDS', 'value': 250 } },
            'times': { 'remainingTimes': 1, 'unlimited': false }
        });

        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/somePath?param=other"), function (err) { return err === "404 Not Found"; });

        var r1 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePath?param=first");
        assert.equal(r1.statusCode, 200);
        assert.equal(r1.body, '{"name":"first_body"}');

        var r2 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePath?param=second");
        assert.equal(r2.statusCode, 200);
        assert.equal(r2.body, '{"name":"second_body"}');
    });

    it('should match on body only', async function () {
        await client.mockAnyResponse({
            'httpRequest': { 'body': { 'type': "STRING", 'string': 'someBody' } },
            'httpResponse': { 'statusCode': 200, 'body': JSON.stringify({name: 'first_body'}), 'delay': { 'timeUnit': 'MILLISECONDS', 'value': 250 } },
            'times': { 'remainingTimes': 1, 'unlimited': false }
        });
        await client.mockAnyResponse({
            'httpRequest': { 'body': { 'type': "REGEX", 'regex': 'someOtherBody' } },
            'httpResponse': { 'statusCode': 200, 'body': JSON.stringify({name: 'second_body'}), 'delay': { 'timeUnit': 'MILLISECONDS', 'value': 250 } },
            'times': { 'remainingTimes': 1, 'unlimited': false }
        });

        await assert.rejects(sendRequest("POST", mockServerHost, mockServerPort, "/otherPath", "someIncorrectBody"), function (err) { return err === "404 Not Found"; });

        var r1 = await sendRequest("POST", mockServerHost, mockServerPort, "/somePath", "someBody");
        assert.equal(r1.statusCode, 200);
        assert.equal(r1.body, '{"name":"first_body"}');

        var r2 = await sendRequest("POST", mockServerHost, mockServerPort, "/somePath", "someOtherBody");
        assert.equal(r2.statusCode, 200);
        assert.equal(r2.body, '{"name":"second_body"}');
    });

    it('should match on headers only', async function () {
        await client.mockAnyResponse({
            'httpRequest': { 'headers': [ { 'name': 'Allow', 'values': ['first'] } ] },
            'httpResponse': { 'statusCode': 200, 'body': JSON.stringify({name: 'first_body'}), 'delay': { 'timeUnit': 'MILLISECONDS', 'value': 250 } },
            'times': { 'remainingTimes': 1, 'unlimited': false }
        });
        await client.mockAnyResponse({
            'httpRequest': { 'headers': [ { 'name': 'Allow', 'values': ['second'] } ] },
            'httpResponse': { 'statusCode': 200, 'body': JSON.stringify({name: 'second_body'}), 'delay': { 'timeUnit': 'MILLISECONDS', 'value': 250 } },
            'times': { 'remainingTimes': 1, 'unlimited': false }
        });

        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/somePath", "", {'Allow': 'other'}), function (err) { return err === "404 Not Found"; });

        var r1 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePath", "", {'Allow': 'first'});
        assert.equal(r1.statusCode, 200);
        assert.equal(r1.body, '{"name":"first_body"}');

        var r2 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePath", "", {'Allow': 'second'});
        assert.equal(r2.statusCode, 200);
        assert.equal(r2.body, '{"name":"second_body"}');
    });

    it('should support headers object format in matcher', async function () {
        client.setDefaultHeaders([
            {"name": "x-test-default", "values": ["default-value"]}
        ]);
        await client.mockAnyResponse({
            'httpRequest': { 'path': '/somePath' },
            'httpResponse': {
                'statusCode': 200,
                'body': JSON.stringify({name: 'first_body'}),
                'headers': { 'x-test': ['test-value'] },
                'delay': { 'timeUnit': 'MILLISECONDS', 'value': 250 }
            },
            'times': { 'remainingTimes': 1, 'unlimited': false }
        });

        var r = await sendRequest("GET", mockServerHost, mockServerPort, "/somePath");
        assert.equal(r.statusCode, 200);
        assert.equal(r.body, '{"name":"first_body"}');
        assert.equal(r.headers["x-test-default"], "default-value");
        assert.equal(r.headers["x-test"], "test-value");
    });

    it('should support headers object format in default headers', async function () {
        client.setDefaultHeaders({ "x-test-default": ["default-value"] });
        await client.mockAnyResponse({
            'httpRequest': { 'path': '/somePath' },
            'httpResponse': {
                'statusCode': 200,
                'body': JSON.stringify({name: 'first_body'}),
                'headers': [ { 'name': 'x-test', 'values': ['test-value'] } ],
                'delay': { 'timeUnit': 'MILLISECONDS', 'value': 250 }
            },
            'times': { 'remainingTimes': 1, 'unlimited': false }
        });

        var r = await sendRequest("GET", mockServerHost, mockServerPort, "/somePath", "", {'Allow': 'first'});
        assert.equal(r.statusCode, 200);
        assert.equal(r.body, '{"name":"first_body"}');
        assert.equal(r.headers["x-test-default"], "default-value");
        assert.equal(r.headers["x-test"], "test-value");
    });

    it('should support headers object format in default headers and matcher', async function () {
        client.setDefaultHeaders([
            {"name": "x-test-default", "values": ["default-value"]}
        ]);
        await client.mockAnyResponse({
            'httpRequest': { 'path': '/somePath' },
            'httpResponse': {
                'statusCode': 200,
                'body': JSON.stringify({name: 'first_body'}),
                'headers': { 'x-test': ['test-value'] },
                'delay': { 'timeUnit': 'MILLISECONDS', 'value': 250 }
            },
            'times': { 'remainingTimes': 1, 'unlimited': false }
        });

        var r = await sendRequest("GET", mockServerHost, mockServerPort, "/somePath", "", {'Allow': 'first'});
        assert.equal(r.statusCode, 200);
        assert.equal(r.body, '{"name":"first_body"}');
        assert.equal(r.headers["x-test-default"], "default-value");
        assert.equal(r.headers["x-test"], "test-value");
    });

    it('should match on cookies only', async function () {
        await client.mockAnyResponse({
            'httpRequest': { 'cookies': [ { 'name': 'cookie', 'value': 'first' } ] },
            'httpResponse': { 'statusCode': 200, 'body': JSON.stringify({name: 'first_body'}), 'delay': { 'timeUnit': 'MILLISECONDS', 'value': 250 } },
            'times': { 'remainingTimes': 1, 'unlimited': false }
        });
        await client.mockAnyResponse({
            'httpRequest': { 'cookies': [ { 'name': 'cookie', 'value': 'second' } ] },
            'httpResponse': { 'statusCode': 200, 'body': JSON.stringify({name: 'second_body'}), 'delay': { 'timeUnit': 'MILLISECONDS', 'value': 250 } },
            'times': { 'remainingTimes': 1, 'unlimited': false }
        });

        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/somePath", "", {'Cookie': 'cookie=other'}), function (err) { return err === "404 Not Found"; });

        var r1 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePath", "", {'Cookie': 'cookie=first'});
        assert.equal(r1.statusCode, 200);
        assert.equal(r1.body, '{"name":"first_body"}');

        var r2 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePath", "", {'Cookie': 'cookie=second'});
        assert.equal(r2.statusCode, 200);
        assert.equal(r2.body, '{"name":"second_body"}');
    });

    it('should create simple response expectation', async function () {
        await client.mockSimpleResponse('/somePath', {name: 'value'}, 203);

        await assert.rejects(sendRequest("POST", mockServerHost, mockServerPort, "/otherPath"), function (err) { return err === "404 Not Found"; });

        var r = await sendRequest("POST", mockServerHost, mockServerPort, "/somePath?test=true", "someBody");
        assert.equal(r.statusCode, 203);
        assert.equal(r.body, '{"name":"value"}');

        await assert.rejects(sendRequest("POST", mockServerHost, mockServerPort, "/somePath?test=true", "someBody"), function (err) { return err === "404 Not Found"; });
    });

    it('should create expectation with open api request', async function () {
        await client.mockAnyResponse({
            'httpRequest': {
                'specUrlOrPayload': 'https://raw.githubusercontent.com/mock-server/mockserver-monorepo/master/mockserver/mockserver-integration-testing/src/main/resources/org/mockserver/openapi/openapi_petstore_example.json',
                'operationId': 'listPets'
            },
            'httpResponse': { 'statusCode': 200, 'body': "open_api_response" }
        });

        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/otherPath"), function (err) { return err === "404 Not Found"; });

        var r = await sendRequest("GET", mockServerHost, mockServerPort, "/v1/pets?limit=10");
        assert.equal(r.statusCode, 200);
        assert.equal(r.body, 'open_api_response');
    });

    it('should create open api expectation', async function () {
        await client.openAPIExpectation({
            'specUrlOrPayload': 'https://raw.githubusercontent.com/mock-server/mockserver-monorepo/master/mockserver/mockserver-integration-testing/src/main/resources/org/mockserver/openapi/openapi_petstore_example.json',
            'operationsAndResponses': { 'showPetById': '200', 'listPets': '200' }
        });

        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/otherPath"), function (err) { return err === "404 Not Found"; });

        var r = await sendRequest("GET", mockServerHost, mockServerPort, "/v1/pets?limit=10");
        assert.equal(r.statusCode, 200);
        assert.equal(r.body, '[ {\n  "id" : 0,\n  "name" : "some_string_value",\n  "tag" : "some_string_value"\n} ]');
    });

    it('should create expectation with method callback', async function () {
        await client.mockWithCallback({
            'method': 'POST',
            'path': '/somePath',
            'queryStringParameters': [ { 'name': 'test', 'values': ['true'] } ],
            'body': { 'type': "STRING", 'string': 'someBody' }
        }, function (request) {
            if (request.method === 'POST' && request.path === '/somePath') {
                return { 'statusCode': 200, 'body': JSON.stringify({name: 'value'}) };
            } else {
                return { 'statusCode': 406 };
            }
        }, 1, 10, { timeUnit: "HOURS", timeToLive: 1 }, "some_id");

        await assert.rejects(
            sendRequest("POST", mockServerHost, mockServerPort, "/someOtherPath?test=true", "", {'Vary': uuid}),
            function (err) { return err === "404 Not Found"; }
        );

        var r = await sendRequest("POST", mockServerHost, mockServerPort, "/somePath?test=true", "someBody", {'Vary': uuid});
        assert.equal(r.statusCode, 200);
        assert.equal(r.body, '{"name":"value"}');

        await assert.rejects(
            sendRequest("POST", mockServerHost, mockServerPort, "/somePath?test=true", "someBody", {'Vary': uuid}),
            function (err) { return err === "404 Not Found"; }
        );
    });

    it('should create expectation with method callback over tls', async function () {
        var clientOverTls = mockServerClient(mockServerHost, mockServerPort, undefined, true);
        await clientOverTls.mockWithCallback({
            'method': 'POST',
            'path': '/somePath',
            'queryStringParameters': [ { 'name': 'test', 'values': ['true'] } ],
            'body': { 'type': "STRING", 'string': 'someBody' }
        }, function (request) {
            if (request.method === 'POST' && request.path === '/somePath') {
                return { 'statusCode': 200, 'body': JSON.stringify({name: 'value'}) };
            } else {
                return { 'statusCode': 406 };
            }
        });

        await assert.rejects(
            sendRequest("POST", mockServerHost, mockServerPort, "/someOtherPath?test=true", "", {'Vary': uuid}),
            function (err) { return err === "404 Not Found"; }
        );

        var r = await sendRequest("POST", mockServerHost, mockServerPort, "/somePath?test=true", "someBody", {'Vary': uuid});
        assert.equal(r.statusCode, 200);
        assert.equal(r.body, '{"name":"value"}');

        await assert.rejects(
            sendRequest("POST", mockServerHost, mockServerPort, "/somePath?test=true", "someBody", {'Vary': uuid}),
            function (err) { return err === "404 Not Found"; }
        );
    });

    it('should create multiple parallel expectations with method callback', async function () {
        await client.mockWithCallback({
            'method': 'GET', 'path': '/one'
        }, function (request) {
            if (request.method === 'GET' && request.path === '/one') {
                return { 'statusCode': 201, 'body': 'one' };
            } else {
                return { 'statusCode': 406 };
            }
        }, { remainingTimes: 2, unlimited: false });

        await client.mockWithCallback({
            'method': 'GET', 'path': '/two'
        }, function (request) {
            if (request.method === 'GET' && request.path === '/two') {
                return { 'statusCode': 202, 'body': 'two' };
            } else {
                return { 'statusCode': 406 };
            }
        });

        var r1 = await sendRequest("GET", mockServerHost, mockServerPort, "/one", "", {'Vary': uuid});
        assert.equal(r1.statusCode, 201);
        assert.equal(r1.body, 'one');

        var r2 = await sendRequest("GET", mockServerHost, mockServerPort, "/two", "someBody", {'Vary': uuid});
        assert.equal(r2.statusCode, 202);
        assert.equal(r2.body, 'two');

        var r3 = await sendRequest("GET", mockServerHost, mockServerPort, "/one", "", {'Vary': uuid});
        assert.equal(r3.statusCode, 201);
        assert.equal(r3.body, 'one');
    });

    it('should create expectation with method callback with numeric times', async function () {
        await client.mockWithCallback({
            'method': 'GET', 'path': '/one'
        }, function (request) {
            if (request.method === 'GET' && request.path === '/one') {
                return { 'statusCode': 201, 'body': 'one' };
            } else {
                return { 'statusCode': 406 };
            }
        }, 2);

        var r1 = await sendRequest("GET", mockServerHost, mockServerPort, "/one", "", {'Vary': uuid});
        assert.equal(r1.statusCode, 201);
        assert.equal(r1.body, 'one');

        var r2 = await sendRequest("GET", mockServerHost, mockServerPort, "/one", "", {'Vary': uuid});
        assert.equal(r2.statusCode, 201);
        assert.equal(r2.body, 'one');

        await assert.rejects(
            sendRequest("GET", mockServerHost, mockServerPort, "/one", "", {'Vary': uuid}),
            function (err) { return err === "404 Not Found"; }
        );
    });

    it('should create expectation with forward method callback', { skip: isExternalMode ? 'forward callbacks cannot reach the host from a Docker container' : false }, async function () {
        await client.mockWithForwardCallback({
            'method': 'GET', 'path': '/somePath'
        }, function (request) {
            return { 'method': request.method, 'path': request.path, 'headers': request.headers };
        }, 1, 10, { timeUnit: "HOURS", timeToLive: 1 }, "some_id");

        var r = await sendRequest("GET", mockServerHost, mockServerPort, "/somePath", "", {'Vary': uuid});
        assert.ok(r.statusCode);

        await assert.rejects(
            sendRequest("GET", mockServerHost, mockServerPort, "/somePath", "", {'Vary': uuid}),
            function (err) { return err === "404 Not Found"; }
        );
    });

    it('should create expectation with forward and response method callback', { skip: isExternalMode ? 'forward-and-response callbacks cannot reach the host from a Docker container' : false }, async function () {
        await client.mockWithForwardAndResponseCallback({
            'method': 'GET', 'path': '/somePath'
        }, function (request) {
            return { 'method': request.method, 'path': request.path, 'headers': request.headers };
        }, function (request, response) {
            return { 'statusCode': 200, 'body': 'overridden' };
        }, 1, 10, { timeUnit: "HOURS", timeToLive: 1 }, "some_id");

        var r = await sendRequest("GET", mockServerHost, mockServerPort, "/somePath", "", {'Vary': uuid});
        assert.equal(r.statusCode, 200);
        assert.equal(r.body, 'overridden');
    });

    it('should update default headers for simple response expectation', async function () {
        client.setDefaultHeaders([
            {"name": "content-type", "values": ["application/json; charset=utf-8"]},
            {"name": "x-test", "values": ["test-value"]}
        ]);
        await client.mockSimpleResponse('/somePath', {name: 'value'}, 203);

        var r = await sendRequest("POST", mockServerHost, mockServerPort, "/somePath?test=true", "someBody");
        assert.equal(r.statusCode, 203);
        assert.equal(r.body, '{"name":"value"}');
        assert.equal(r.headers["content-type"], "application/json; charset=utf-8");
        assert.equal(r.headers["x-test"], "test-value");
    });

    it('should verify exact number of requests have been sent', async function () {
        await client.mockSimpleResponse('/somePath', {name: 'value'}, 203);
        var r = await sendRequest("POST", mockServerHost, mockServerPort, "/somePath", "someBody");
        assert.equal(r.statusCode, 203);

        await client.verify({ 'method': 'POST', 'path': '/somePath', 'body': 'someBody' }, 1, 1);
    });

    it('should verify at least a number of requests have been sent', async function () {
        await client.mockSimpleResponse('/somePath', {name: 'value'}, 203);
        await client.mockSimpleResponse('/somePath', {name: 'value'}, 203);

        var r1 = await sendRequest("POST", mockServerHost, mockServerPort, "/somePath", "someBody");
        assert.equal(r1.statusCode, 203);
        var r2 = await sendRequest("POST", mockServerHost, mockServerPort, "/somePath", "someBody");
        assert.equal(r2.statusCode, 203);

        await client.verify({ 'method': 'POST', 'path': '/somePath', 'body': 'someBody' }, 1);
    });

    it('should fail when no requests have been sent', async function () {
        await client.mockSimpleResponse('/somePath', {name: 'value'}, 203);
        var r = await sendRequest("POST", mockServerHost, mockServerPort, "/somePath", "someBody");
        assert.equal(r.statusCode, 203);

        try {
            await client.verify({ 'path': '/someOtherPath' }, 1);
            assert.fail("should have thrown");
        } catch (message) {
            assert.equal(message, "Request not found at least once");
        }
    });

    it('should fail when not enough exact requests have been sent', async function () {
        await client.mockSimpleResponse('/somePath', {name: 'value'}, 203);
        var r = await sendRequest("POST", mockServerHost, mockServerPort, "/somePath", "someBody");
        assert.equal(r.statusCode, 203);

        try {
            await client.verify({ 'method': 'POST', 'path': '/somePath', 'body': 'someBody' }, 2, 3);
            assert.fail("should have thrown");
        } catch (message) {
            assert.equal(message, "Request not found between 2 and 3 times");
        }
    });

    it('should fail when not enough at least requests have been sent', async function () {
        await client.mockSimpleResponse('/somePath', {name: 'value'}, 203);
        var r = await sendRequest("POST", mockServerHost, mockServerPort, "/somePath", "someBody");
        assert.equal(r.statusCode, 203);

        try {
            await client.verify({ 'method': 'POST', 'path': '/somePath', 'body': 'someBody' }, 2);
            assert.fail("should have thrown");
        } catch (message) {
            assert.equal(message, "Request not found at least 2 times");
        }
    });

    it('should pass when correct sequence of requests have been sent', async function () {
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);
        await client.mockSimpleResponse('/somePathTwo', {name: 'two'}, 202);

        var r1 = await sendRequest("POST", mockServerHost, mockServerPort, "/somePathOne", "someBody");
        assert.equal(r1.statusCode, 201);

        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/notFound"), function (err) { return err === "404 Not Found"; });

        var r2 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePathTwo");
        assert.equal(r2.statusCode, 202);

        await client.verifySequence(
            { 'method': 'POST', 'path': '/somePathOne', 'body': 'someBody' },
            { 'method': 'GET', 'path': '/notFound' },
            { 'method': 'GET', 'path': '/somePathTwo' }
        );
    });

    it('should fail when incorrect sequence (wrong order) of requests have been sent', async function () {
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);
        await client.mockSimpleResponse('/somePathTwo', {name: 'two'}, 202);

        await sendRequest("POST", mockServerHost, mockServerPort, "/somePathOne", "someBody");
        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/notFound"), function (err) { return err === "404 Not Found"; });
        await sendRequest("GET", mockServerHost, mockServerPort, "/somePathTwo");

        try {
            await client.verifySequence(
                { 'body': 'someBody', 'method': 'POST', 'path': '/somePathOne' },
                { 'method': 'GET', 'path': '/somePathTwo' },
                { 'method': 'GET', 'path': '/notFound' }
            );
            assert.fail("should have thrown");
        } catch (message) {
            assert.equal(message, "Request sequence not found");
        }
    });

    it('should fail when incorrect sequence (first request incorrect body) of requests have been sent', async function () {
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);
        await client.mockSimpleResponse('/somePathTwo', {name: 'two'}, 202);

        await sendRequest("POST", mockServerHost, mockServerPort, "/somePathOne", "someBody");
        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/notFound"), function (err) { return err === "404 Not Found"; });
        await sendRequest("GET", mockServerHost, mockServerPort, "/somePathTwo");

        try {
            await client.verifySequence(
                { 'body': 'some_incorrect_body', 'method': 'POST', 'path': '/somePathOne' },
                { 'method': 'GET', 'path': '/notFound' },
                { 'method': 'GET', 'path': '/somePathTwo' }
            );
            assert.fail("should have thrown");
        } catch (message) {
            assert.equal(message, "Request sequence not found");
        }
    });

    it('should clear expectations and logs by path', async function () {
        await client.mockSimpleResponse('/somePathOne', {name: 'value'}, 200);
        await client.mockSimpleResponse('/somePathOne', {name: 'value'}, 200);
        await client.mockSimpleResponse('/somePathTwo', {name: 'value'}, 200);

        var r1 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePathOne");
        assert.equal(r1.statusCode, 200);
        assert.equal(r1.body, '{"name":"value"}');

        await client.clear('/somePathOne');

        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/somePathOne"), function (err) { return err === "404 Not Found"; });

        var r2 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePathTwo");
        assert.equal(r2.statusCode, 200);
        assert.equal(r2.body, '{"name":"value"}');

        await client.clear('/somePathOne');
        var requests = await client.retrieveRecordedRequests({ "path": "/somePathOne" });
        assert.equal(requests.length, 0);

        var requests2 = await client.retrieveRecordedRequests({ "httpRequest": { "path": "/somePathTwo" } });
        assert.equal(requests2.length, 1);
        assert.equal(requests2[0].path, '/somePathTwo');
    });

    it('should clear expectations by request matcher', async function () {
        await client.mockSimpleResponse('/somePathOne', {name: 'value'}, 200);
        await client.mockSimpleResponse('/somePathOne', {name: 'value'}, 200);
        await client.mockSimpleResponse('/somePathTwo', {name: 'value'}, 200);

        var r1 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePathOne");
        assert.equal(r1.statusCode, 200);
        assert.equal(r1.body, '{"name":"value"}');

        await client.clear({ "path": "/somePathOne" });

        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/somePathOne"), function (err) { return err === "404 Not Found"; });

        var r2 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePathTwo");
        assert.equal(r2.statusCode, 200);
        assert.equal(r2.body, '{"name":"value"}');
    });

    it('should clear expectations by expectation matcher', async function () {
        await client.mockSimpleResponse('/somePathOne', {name: 'value'}, 200);
        await client.mockSimpleResponse('/somePathOne', {name: 'value'}, 200);
        await client.mockSimpleResponse('/somePathTwo', {name: 'value'}, 200);

        var r1 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePathOne");
        assert.equal(r1.statusCode, 200);
        assert.equal(r1.body, '{"name":"value"}');

        await client.clear({ "httpRequest": { "path": "/somePathOne" } });

        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/somePathOne"), function (err) { return err === "404 Not Found"; });

        var r2 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePathTwo");
        assert.equal(r2.statusCode, 200);
        assert.equal(r2.body, '{"name":"value"}');
    });

    it('should clear only logs by path', async function () {
        await client.mockSimpleResponse('/somePathOne', {name: 'value'}, 200);
        await client.mockSimpleResponse('/somePathOne', {name: 'value'}, 200);
        await client.mockSimpleResponse('/somePathTwo', {name: 'value'}, 200);

        var r1 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePathOne");
        assert.equal(r1.statusCode, 200);
        assert.equal(r1.body, '{"name":"value"}');

        await client.clear('/somePathOne', 'EXPECTATIONS');

        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/somePathOne"), function (err) { return err === "404 Not Found"; });

        var r2 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePathTwo");
        assert.equal(r2.statusCode, 200);
        assert.equal(r2.body, '{"name":"value"}');

        await client.clear('/somePathOne', 'LOG');
        var requests = await client.retrieveRecordedRequests({ "path": "/somePathOne" });
        assert.equal(requests.length, 0);
    });

    it('should clear only expectation by path', async function () {
        await client.mockSimpleResponse('/somePathOne', {name: 'value'}, 200);
        await client.mockSimpleResponse('/somePathOne', {name: 'value'}, 200);
        await client.mockSimpleResponse('/somePathTwo', {name: 'value'}, 200);

        var r1 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePathOne");
        assert.equal(r1.statusCode, 200);
        assert.equal(r1.body, '{"name":"value"}');

        await client.clear('/somePathOne', 'EXPECTATIONS');

        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/somePathOne"), function (err) { return err === "404 Not Found"; });

        var r2 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePathTwo");
        assert.equal(r2.statusCode, 200);
        assert.equal(r2.body, '{"name":"value"}');

        await client.clear('/somePathOne', 'LOG');
        var requests = await client.retrieveRecordedRequests({ "path": "/somePathOne" });
        assert.equal(requests.length, 0);

        var requests2 = await client.retrieveRecordedRequests({ "httpRequest": { "path": "/somePathTwo" } });
        assert.equal(requests2.length, 1);
        assert.equal(requests2[0].path, '/somePathTwo');
    });

    it('should reset expectations', async function () {
        await client.mockSimpleResponse('/somePathOne', {name: 'value'}, 200);
        await client.mockSimpleResponse('/somePathOne', {name: 'value'}, 200);
        await client.mockSimpleResponse('/somePathTwo', {name: 'value'}, 200);

        var r1 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePathOne");
        assert.equal(r1.statusCode, 200);
        assert.equal(r1.body, '{"name":"value"}');

        await client.reset();

        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/somePathOne"), function (err) { return err === "404 Not Found"; });
        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/somePathTwo"), function (err) { return err === "404 Not Found"; });
    });

    it('should retrieve some expectations using object matcher', async function () {
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);
        await client.mockSimpleResponse('/somePathTwo', {name: 'two'}, 202);

        var expectations = await client.retrieveActiveExpectations({ "httpRequest": { "path": "/somePathOne" } });
        assert.equal(expectations.length, 2);
        assert.equal(expectations[0].httpRequest.path, '/somePathOne');
        assert.equal(expectations[0].httpResponse.body, '{"name":"one"}');
        assert.equal(expectations[0].httpResponse.statusCode, 201);
        assert.equal(expectations[1].httpRequest.path, '/somePathOne');
        assert.equal(expectations[1].httpResponse.body, '{"name":"one"}');
        assert.equal(expectations[1].httpResponse.statusCode, 201);
    });

    it('should retrieve some expectations using path', async function () {
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);
        await client.mockSimpleResponse('/somePathTwo', {name: 'two'}, 202);

        var expectations = await client.retrieveActiveExpectations("/somePathOne");
        assert.equal(expectations.length, 2);
        assert.equal(expectations[0].httpRequest.path, '/somePathOne');
        assert.equal(expectations[0].httpResponse.body, '{"name":"one"}');
        assert.equal(expectations[0].httpResponse.statusCode, 201);
        assert.equal(expectations[1].httpRequest.path, '/somePathOne');
        assert.equal(expectations[1].httpResponse.body, '{"name":"one"}');
        assert.equal(expectations[1].httpResponse.statusCode, 201);
    });

    it('should retrieve active expectations as generated code', async function () {
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);

        var code = await client.retrieveExpectationsAsCode('java', '/somePathOne');
        assert.equal(typeof code, 'string');
        assert.ok(code.length > 0, 'expected non-empty generated code');
        assert.ok(code.indexOf('/somePathOne') !== -1, 'generated code should reference the path');
    });

    it('should retrieve recorded expectations as generated code', async function () {
        // No proxied traffic has been recorded, so the generated code is empty,
        // but the call must succeed and return a string for the requested format.
        var code = await client.retrieveRecordedExpectationsAsCode('python');
        assert.equal(typeof code, 'string');
    });

    it('should retrieve all expectations using object matcher', async function () {
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);
        await client.mockSimpleResponse('/somePathTwo', {name: 'two'}, 202);

        var expectations = await client.retrieveActiveExpectations({ "httpRequest": { "path": "/somePath.*" } });
        assert.equal(expectations.length, 3);
        assert.equal(expectations[0].httpRequest.path, '/somePathOne');
        assert.equal(expectations[1].httpRequest.path, '/somePathOne');
        assert.equal(expectations[2].httpRequest.path, '/somePathTwo');
    });

    it('should retrieve all expectations using null matcher', async function () {
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);
        await client.mockSimpleResponse('/somePathTwo', {name: 'two'}, 202);

        var expectations = await client.retrieveActiveExpectations();
        assert.equal(expectations.length, 3);
    });

    it('should setup an SSE response expectation using respondWithSse', async function () {
        await client.respondWithSse('/sse', {
            statusCode: 200,
            events: [
                { event: 'message', data: 'first' },
                { event: 'message', data: 'second' }
            ],
            closeConnection: true
        });

        var expectations = await client.retrieveActiveExpectations('/sse');
        assert.equal(expectations.length, 1);
        assert.equal(expectations[0].httpRequest.path, '/sse');
        assert.ok(expectations[0].httpSseResponse, 'httpSseResponse should be present');
        assert.equal(expectations[0].httpSseResponse.events.length, 2);
        assert.equal(expectations[0].httpSseResponse.events[0].data, 'first');
        assert.equal(expectations[0].httpSseResponse.closeConnection, true);
        assert.ok(!expectations[0].httpResponse, 'no httpResponse action should be set');
    });

    it('should setup a WebSocket response expectation using respondWithWebSocket', async function () {
        await client.respondWithWebSocket('/ws', {
            messages: [
                { text: 'hello' },
                { text: 'world' }
            ],
            closeConnection: false
        });

        var expectations = await client.retrieveActiveExpectations('/ws');
        assert.equal(expectations.length, 1);
        assert.equal(expectations[0].httpRequest.path, '/ws');
        assert.ok(expectations[0].httpWebSocketResponse, 'httpWebSocketResponse should be present');
        assert.equal(expectations[0].httpWebSocketResponse.messages.length, 2);
        assert.equal(expectations[0].httpWebSocketResponse.messages[0].text, 'hello');
        assert.ok(!expectations[0].httpResponse, 'no httpResponse action should be set');
    });

    it('should setup a DNS response expectation using respondWithDns', async function () {
        await client.respondWithDns('/dns', {
            answerRecords: [
                { name: 'example.com', type: 'A', ttl: 300, value: '127.0.0.1' }
            ],
            responseCode: 'NOERROR'
        });

        var expectations = await client.retrieveActiveExpectations('/dns');
        assert.equal(expectations.length, 1);
        assert.equal(expectations[0].httpRequest.path, '/dns');
        assert.ok(expectations[0].dnsResponse, 'dnsResponse should be present');
        assert.equal(expectations[0].dnsResponse.answerRecords.length, 1);
        assert.equal(expectations[0].dnsResponse.answerRecords[0].value, '127.0.0.1');
        assert.equal(expectations[0].dnsResponse.responseCode, 'NOERROR');
        assert.ok(!expectations[0].httpResponse, 'no httpResponse action should be set');
    });

    it('should setup a binary response expectation using respondWithBinary', async function () {
        var base64Data = Buffer.from('hello binary').toString('base64');
        await client.respondWithBinary('/binary', {
            binaryData: base64Data
        });

        var expectations = await client.retrieveActiveExpectations('/binary');
        assert.equal(expectations.length, 1);
        assert.equal(expectations[0].httpRequest.path, '/binary');
        assert.ok(expectations[0].binaryResponse, 'binaryResponse should be present');
        assert.equal(expectations[0].binaryResponse.binaryData, base64Data);
        assert.ok(!expectations[0].httpResponse, 'no httpResponse action should be set');
    });

    it('should setup a gRPC stream response expectation using respondWithGrpcStream', async function () {
        await client.respondWithGrpcStream('/my.Service/StreamItems', {
            statusName: 'OK',
            messages: [
                { json: '{"value":"first"}' },
                { json: '{"value":"second"}' }
            ],
            closeConnection: true
        });

        var expectations = await client.retrieveActiveExpectations('/my.Service/StreamItems');
        assert.equal(expectations.length, 1);
        assert.equal(expectations[0].httpRequest.path, '/my.Service/StreamItems');
        assert.ok(expectations[0].grpcStreamResponse, 'grpcStreamResponse should be present');
        assert.equal(expectations[0].grpcStreamResponse.messages.length, 2);
        assert.equal(expectations[0].grpcStreamResponse.statusName, 'OK');
        assert.ok(!expectations[0].httpResponse, 'no httpResponse action should be set');
    });

    it('should honour times on respondWithSse', async function () {
        await client.respondWithSse('/sse-times', {
            events: [ { event: 'message', data: 'x' } ]
        }, 3);

        var expectations = await client.retrieveActiveExpectations('/sse-times');
        assert.equal(expectations.length, 1);
        assert.equal(expectations[0].times.remainingTimes, 3);
        assert.ok(!expectations[0].times.unlimited, 'expectation should not be unlimited');
    });

    it('should retrieve some requests using object matcher', async function () {
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);
        await client.mockSimpleResponse('/somePathTwo', {name: 'two'}, 202);

        await sendRequest("POST", mockServerHost, mockServerPort, "/somePathOne", "someBody");
        await sendRequest("GET", mockServerHost, mockServerPort, "/somePathOne");
        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/notFound"), function (err) { return err === "404 Not Found"; });
        await sendRequest("GET", mockServerHost, mockServerPort, "/somePathTwo");

        var requests = await client.retrieveRecordedRequests({ "httpRequest": { "path": "/somePathOne" } });
        assert.equal(requests.length, 2);
        assert.equal(requests[0].path, '/somePathOne');
        assert.equal(requests[0].method, 'POST');
        assert.equal(requests[0].body, 'someBody');
        assert.equal(requests[1].path, '/somePathOne');
        assert.equal(requests[1].method, 'GET');
    });

    it('should retrieve some requests using path', async function () {
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);
        await client.mockSimpleResponse('/somePathTwo', {name: 'two'}, 202);

        await sendRequest("POST", mockServerHost, mockServerPort, "/somePathOne", "someBody");
        await sendRequest("GET", mockServerHost, mockServerPort, "/somePathOne");
        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/notFound"), function (err) { return err === "404 Not Found"; });
        await sendRequest("GET", mockServerHost, mockServerPort, "/somePathTwo");

        var requests = await client.retrieveRecordedRequests("/somePathOne");
        assert.equal(requests.length, 2);
        assert.equal(requests[0].path, '/somePathOne');
        assert.equal(requests[0].method, 'POST');
        assert.equal(requests[0].body, 'someBody');
        assert.equal(requests[1].path, '/somePathOne');
        assert.equal(requests[1].method, 'GET');
    });

    it('should retrieve all requests using object matcher', async function () {
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);
        await client.mockSimpleResponse('/somePathTwo', {name: 'two'}, 202);

        await sendRequest("POST", mockServerHost, mockServerPort, "/somePathOne", "someBody");
        await sendRequest("GET", mockServerHost, mockServerPort, "/somePathOne");
        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/notFound"), function (err) { return err === "404 Not Found"; });
        await sendRequest("GET", mockServerHost, mockServerPort, "/somePathTwo");

        var requests = await client.retrieveRecordedRequests({ "httpRequest": { "path": "/.*" } });
        assert.equal(requests.length, 4);
        assert.equal(requests[0].path, '/somePathOne');
        assert.equal(requests[0].method, 'POST');
        assert.equal(requests[0].body, 'someBody');
        assert.equal(requests[1].path, '/somePathOne');
        assert.equal(requests[1].method, 'GET');
        assert.equal(requests[2].path, '/notFound');
        assert.equal(requests[2].method, 'GET');
        assert.equal(requests[3].path, '/somePathTwo');
        assert.equal(requests[3].method, 'GET');
    });

    it('should retrieve all requests using path', async function () {
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);
        await client.mockSimpleResponse('/somePathTwo', {name: 'two'}, 202);

        await sendRequest("POST", mockServerHost, mockServerPort, "/somePathOne", "someBody");
        await sendRequest("GET", mockServerHost, mockServerPort, "/somePathOne");
        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/notFound"), function (err) { return err === "404 Not Found"; });
        await sendRequest("GET", mockServerHost, mockServerPort, "/somePathTwo");

        var requests = await client.retrieveRecordedRequests("/.*");
        assert.equal(requests.length, 4);
        assert.equal(requests[0].path, '/somePathOne');
        assert.equal(requests[0].method, 'POST');
        assert.equal(requests[0].body, 'someBody');
        assert.equal(requests[1].path, '/somePathOne');
        assert.equal(requests[1].method, 'GET');
        assert.equal(requests[2].path, '/notFound');
        assert.equal(requests[2].method, 'GET');
        assert.equal(requests[3].path, '/somePathTwo');
        assert.equal(requests[3].method, 'GET');
    });

    it('should retrieve some requests and responses using object matcher', async function () {
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);
        await client.mockSimpleResponse('/somePathTwo', {name: 'two'}, 202);

        await sendRequest("POST", mockServerHost, mockServerPort, "/somePathOne", "someBody");
        await sendRequest("GET", mockServerHost, mockServerPort, "/somePathOne");
        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/notFound"), function (err) { return err === "404 Not Found"; });
        await sendRequest("GET", mockServerHost, mockServerPort, "/somePathTwo");

        var responses = await client.retrieveRecordedRequestsAndResponses({ "httpRequest": { "path": "/somePathOne" } });
        assert.equal(responses.length, 2);
        assert.equal(responses[0].httpRequest.path, '/somePathOne');
        assert.equal(responses[0].httpRequest.method, 'POST');
        assert.equal(responses[0].httpRequest.body, 'someBody');
        assert.equal(responses[0].httpResponse.statusCode, 201);
        assert.equal(responses[0].httpResponse.body, '{"name":"one"}');
        assert.equal(responses[1].httpRequest.path, '/somePathOne');
        assert.equal(responses[1].httpRequest.method, 'GET');
        assert.equal(responses[1].httpResponse.statusCode, 201);
        assert.equal(responses[1].httpResponse.body, '{"name":"one"}');
    });

    it('should retrieve some requests and responses using path', async function () {
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);
        await client.mockSimpleResponse('/somePathTwo', {name: 'two'}, 202);

        await sendRequest("POST", mockServerHost, mockServerPort, "/somePathOne", "someBody");
        await sendRequest("GET", mockServerHost, mockServerPort, "/somePathOne");
        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/notFound"), function (err) { return err === "404 Not Found"; });
        await sendRequest("GET", mockServerHost, mockServerPort, "/somePathTwo");

        var responses = await client.retrieveRecordedRequestsAndResponses("/somePathOne");
        assert.equal(responses.length, 2);
        assert.equal(responses[0].httpRequest.path, '/somePathOne');
        assert.equal(responses[0].httpRequest.method, 'POST');
        assert.equal(responses[0].httpRequest.body, 'someBody');
        assert.equal(responses[0].httpResponse.statusCode, 201);
        assert.equal(responses[0].httpResponse.body, '{"name":"one"}');
        assert.equal(responses[1].httpRequest.path, '/somePathOne');
        assert.equal(responses[1].httpRequest.method, 'GET');
        assert.equal(responses[1].httpResponse.statusCode, 201);
        assert.equal(responses[1].httpResponse.body, '{"name":"one"}');
    });

    it('should retrieve some logs using object matcher', async function () {
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);

        await sendRequest("POST", mockServerHost, mockServerPort, "/somePathOne", "someBody");
        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/notFound"), function (err) { return err === "404 Not Found"; });

        var logMessages = await client.retrieveLogMessages({ "httpRequest": { "path": "/somePathOne" } });
        assert.equal(logMessages.length, 6);
        assert.ok(logMessages[0].indexOf('resetting all expectations and request logs') !== -1, logMessages[0]);
        assert.ok(logMessages[1].indexOf("creating expectation:\n") !== -1, logMessages[1]);
        assert.ok(logMessages[2].indexOf("received request:\n\n  {\n    \"method\" : \"POST\",\n    \"path\" : \"/somePathOne\",\n") !== -1, logMessages[2]);
        assert.ok(logMessages[3].indexOf("request:\n\n  {\n    \"method\" : \"POST\",\n    \"path\" : \"/somePathOne\",\n") !== -1, logMessages[3]);
    });

    it('should retrieve some logs using path', async function () {
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);

        await sendRequest("POST", mockServerHost, mockServerPort, "/somePathOne", "someBody");
        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/notFound"), function (err) { return err === "404 Not Found"; });

        var logMessages = await client.retrieveLogMessages("/somePathOne");
        assert.equal(logMessages.length, 6);
        assert.ok(logMessages[0].indexOf('resetting all expectations and request logs') !== -1, logMessages[0]);
        assert.ok(logMessages[1].indexOf("creating expectation:\n") !== -1, logMessages[1]);
        assert.ok(logMessages[2].indexOf("received request:\n\n  {\n    \"method\" : \"POST\",\n    \"path\" : \"/somePathOne\",\n") !== -1, logMessages[2]);
        assert.ok(logMessages[3].indexOf("request:\n\n  {\n    \"method\" : \"POST\",\n    \"path\" : \"/somePathOne\",\n") !== -1, logMessages[3]);
    });

    it('should bind to additional port', { skip: isExternalMode ? 'newly bound port is not published from a Docker container' : false }, async function () {
        var response = await client.bind([mockServerPort + 1]);
        assert.equal(response.statusCode, 200);
        assert.ok(response.body.indexOf("\"artifactId\" : \"mockserver-core\"") !== -1,
            "bind response should contain artifactId, got: " + response.body);
        assert.ok(response.body.indexOf("\"ports\" : [ " + (mockServerPort + 1) + " ]") !== -1,
            "bind response should contain new port, got: " + response.body);

        var status = await sendRequest("PUT", mockServerHost, mockServerPort + 1, "/status");
        assert.equal(status.statusCode, 200);
        assert.ok(status.body.indexOf("\"artifactId\" : \"mockserver-core\"") !== -1,
            "status response should contain artifactId, got: " + status.body);
        assert.ok(status.body.indexOf("\"ports\" : [ " + mockServerPort + ", " + (mockServerPort + 1) + " ]") !== -1,
            "status response should contain both ports, got: " + status.body);
    });

    // ========================================================================
    // GAP-CLOSING TESTS: clock, service-chaos, by-id, verifyZeroInteractions,
    // retrieveLogMessages (already above), retrieveRecordedExpectations
    // ========================================================================

    it('should freeze and query clock', async function () {
        await client.freezeClock("2025-01-15T09:30:00Z");
        var status = await client.clockStatus();
        assert.equal(status.frozen, true);
        assert.ok(status.currentInstant, "clockStatus should include currentInstant");
        assert.ok(typeof status.currentEpochMillis === 'number', "clockStatus should include currentEpochMillis");
    });

    it('should advance frozen clock', async function () {
        await client.freezeClock("2025-01-15T09:30:00Z");
        var before = await client.clockStatus();
        await client.advanceClock(60000);
        var after = await client.clockStatus();
        assert.equal(after.frozen, true);
        assert.ok(after.currentEpochMillis >= before.currentEpochMillis + 60000,
            "clock should have advanced by at least 60000ms");
    });

    it('should reset clock to real time', async function () {
        await client.freezeClock("2025-01-15T09:30:00Z");
        await client.resetClock();
        var status = await client.clockStatus();
        assert.equal(status.frozen, false);
    });

    it('should freeze clock without explicit instant', async function () {
        await client.freezeClock();
        var status = await client.clockStatus();
        assert.equal(status.frozen, true);
    });

    it('should set and query service chaos', async function () {
        var chaosProfile = {
            errorStatus: 503,
            errorProbability: 0.5,
            dropConnectionProbability: 0.0
        };
        await client.setServiceChaos("payments.svc", chaosProfile);
        var status = await client.serviceChaosStatus();
        assert.ok(status.services, "serviceChaosStatus should include services");
        // The host key may be normalized by the server (lowercased, etc.)
        var hosts = Object.keys(status.services);
        assert.ok(hosts.length >= 1, "should have at least one chaos registration");
    });

    it('should remove service chaos for a specific host', async function () {
        await client.setServiceChaos("cache.svc", { errorStatus: 500, errorProbability: 1.0 });
        await client.removeServiceChaos("cache.svc");
        var status = await client.serviceChaosStatus();
        var hosts = Object.keys(status.services || {});
        var hasCacheSvc = hosts.some(function (h) { return h.toLowerCase().indexOf("cache.svc") !== -1; });
        assert.equal(hasCacheSvc, false, "cache.svc chaos should have been removed");
    });

    it('should clear all service chaos', async function () {
        await client.setServiceChaos("a.svc", { errorStatus: 500, errorProbability: 1.0 });
        await client.setServiceChaos("b.svc", { errorStatus: 503, errorProbability: 0.5 });
        await client.clearServiceChaos();
        var status = await client.serviceChaosStatus();
        var hosts = Object.keys(status.services || {});
        assert.equal(hosts.length, 0, "all service chaos should have been cleared");
    });

    // ========================================================================
    // Load scenario registry management
    //   PUT    /mockserver/loadScenario        (register; allowed when off)
    //   GET    /mockserver/loadScenario        (list all registered)
    //   GET    /mockserver/loadScenario/{name} (one; 404 if absent)
    //   DELETE /mockserver/loadScenario/{name} (remove one)
    //   DELETE /mockserver/loadScenario        (clear all)
    //   PUT    /mockserver/loadScenario/start  (start; 403 unless enabled)
    //   PUT    /mockserver/loadScenario/stop   (stop running)
    // The integration server is started WITHOUT loadGenerationEnabled, so
    // REGISTRATION is allowed (exercises the registry CRUD paths) while STARTING
    // is forbidden - which deterministically exercises the client's start path
    // and its 403 rejection message.
    // ========================================================================

    var loadScenarioDefinition = {
        name: "node-client-load-test",
        templateType: "VELOCITY",
        labels: { team: "node-client" },
        maxRequests: 10,
        startDelayMillis: 50,
        profile: {
            stages: [
                { type: "VU", vus: 2, durationMillis: 1000 },
                { type: "PAUSE", durationMillis: 200 },
                { type: "RATE", rate: 5, durationMillis: 1000 }
            ]
        },
        steps: [
            {
                name: "ping",
                thinkTime: { timeUnit: "MILLISECONDS", value: 5 },
                request: {
                    method: "GET",
                    path: "/load/ping",
                    headers: { "X-Load": ["1"] }
                }
            }
        ]
    };

    it('should register a load scenario even when load generation is disabled', async function () {
        await client.clearLoadScenarios();
        var registration = await client.loadScenario(loadScenarioDefinition);
        assert.ok(registration, "loadScenario should resolve a registration object");
        assert.equal(registration.name, loadScenarioDefinition.name, "registration should echo the scenario name");
        assert.ok(typeof registration.state === "string", "registration should carry a string 'state'");
    });

    it('should list registered load scenarios', async function () {
        await client.clearLoadScenarios();
        await client.loadScenario(loadScenarioDefinition);
        var list = await client.loadScenarios();
        assert.ok(list && Array.isArray(list.scenarios), "loadScenarios should resolve {scenarios: [...]}");
        var names = list.scenarios.map(function (s) { return s.name; });
        assert.ok(names.indexOf(loadScenarioDefinition.name) !== -1, "the registered scenario should be listed");
    });

    it('should get a single registered load scenario by name', async function () {
        await client.loadScenario(loadScenarioDefinition);
        var entry = await client.getLoadScenario(loadScenarioDefinition.name);
        assert.ok(entry, "getLoadScenario should resolve an entry");
        assert.equal(entry.name, loadScenarioDefinition.name, "entry should carry the requested name");
    });

    it('should reject getLoadScenario with 404 for an unknown name', async function () {
        var rejected = false;
        try {
            await client.getLoadScenario("node-client-no-such-scenario");
        } catch (err) {
            rejected = true;
            var message = (typeof err === "string") ? err : String(err);
            assert.ok(message.indexOf("404") !== -1, "expected a 404 but got: " + message);
        }
        assert.equal(rejected, true, "getLoadScenario should reject for an unknown scenario");
    });

    it('should reject startLoadScenarios with a clear message when load generation is disabled', async function () {
        await client.loadScenario(loadScenarioDefinition);
        var rejected = false;
        try {
            await client.startLoadScenarios(loadScenarioDefinition.name);
        } catch (err) {
            rejected = true;
            var message = (typeof err === "string") ? err : String(err);
            assert.ok(
                message.toLowerCase().indexOf("load generation is not enabled") !== -1,
                "expected a clear 'load generation is not enabled' message but got: " + message
            );
        }
        assert.equal(rejected, true, "startLoadScenarios should reject when loadGenerationEnabled=false");
    });

    it('should accept an array of names for startLoadScenarios', async function () {
        await client.loadScenario(loadScenarioDefinition);
        var rejected = false;
        try {
            await client.startLoadScenarios([loadScenarioDefinition.name]);
        } catch (err) {
            rejected = true;
            var message = (typeof err === "string") ? err : String(err);
            assert.ok(
                message.toLowerCase().indexOf("load generation is not enabled") !== -1,
                "expected a clear 'load generation is not enabled' message but got: " + message
            );
        }
        assert.equal(rejected, true, "startLoadScenarios(array) should reject when loadGenerationEnabled=false");
    });

    it('should stop all running load scenarios with an empty body', async function () {
        var result = await client.stopLoadScenarios();
        assert.ok(result, "stopLoadScenarios should resolve a result");
        assert.equal(result.status, "stopped", "stopLoadScenarios should report status 'stopped'");
    });

    it('should delete a single registered load scenario by name', async function () {
        await client.clearLoadScenarios();
        await client.loadScenario(loadScenarioDefinition);
        var result = await client.deleteLoadScenario(loadScenarioDefinition.name);
        assert.ok(result, "deleteLoadScenario should resolve a response");
        assert.equal(result.statusCode, 200, "deleteLoadScenario should return HTTP 200");
        var list = await client.loadScenarios();
        var names = list.scenarios.map(function (s) { return s.name; });
        assert.ok(names.indexOf(loadScenarioDefinition.name) === -1, "the deleted scenario should no longer be listed");
    });

    it('should clear all registered load scenarios', async function () {
        await client.loadScenario(loadScenarioDefinition);
        var result = await client.clearLoadScenarios();
        assert.ok(result, "clearLoadScenarios should resolve a response");
        assert.equal(result.statusCode, 200, "clearLoadScenarios should return HTTP 200");
        var list = await client.loadScenarios();
        assert.equal(list.scenarios.length, 0, "registry should be empty after clearLoadScenarios");
    });

    it('should reject runLoadScenario (register then start) when load generation is disabled', async function () {
        var rejected = false;
        try {
            await client.runLoadScenario(loadScenarioDefinition);
        } catch (err) {
            rejected = true;
            var message = (typeof err === "string") ? err : String(err);
            assert.ok(
                message.toLowerCase().indexOf("load generation is not enabled") !== -1,
                "expected a clear 'load generation is not enabled' message but got: " + message
            );
        }
        assert.equal(rejected, true, "runLoadScenario should reject when loadGenerationEnabled=false");
        // even though start failed, registration should have succeeded
        var entry = await client.getLoadScenario(loadScenarioDefinition.name);
        assert.equal(entry.name, loadScenarioDefinition.name, "runLoadScenario should still have registered the scenario");
    });

    // A scenario exercising the load-injection features added alongside the
    // thresholds/verdict/pacing/feeder/captures/weighted/shape model. The
    // server accepts and round-trips these fields on registration (no run
    // required), which deterministically exercises that the client serializes
    // them correctly even with loadGenerationEnabled=false.
    var richLoadScenarioDefinition = {
        name: "node-client-load-test-rich",
        templateType: "MUSTACHE",
        stepSelection: "WEIGHTED",
        abortOnFail: true,
        abortGraceMillis: 2000,
        thresholds: [
            { metric: "LATENCY_P95", comparator: "LESS_THAN", threshold: 500 },
            { metric: "ERROR_RATE", comparator: "LESS_THAN_OR_EQUAL", threshold: 0.01 }
        ],
        pacing: { mode: "CONSTANT_THROUGHPUT", value: 2 },
        feeder: {
            rows: [ { user: "alice" }, { user: "bob" } ],
            strategy: "CIRCULAR"
        },
        profile: {
            shape: {
                type: "RAMP_HOLD",
                metric: "VU",
                target: 3,
                rampMillis: 500,
                holdMillis: 1000
            }
        },
        steps: [
            {
                name: "login",
                weight: 7,
                request: { method: "POST", path: "/load/login", body: "{\"u\":\"{{iteration.data.user}}\"}" },
                captures: [
                    { name: "token", source: "BODY_JSONPATH", expression: "$.token", defaultValue: "anon" }
                ]
            },
            {
                name: "browse",
                weight: 3,
                request: {
                    method: "GET",
                    path: "/load/browse",
                    headers: { "Authorization": ["Bearer {{iteration.captured.token}}"] }
                }
            }
        ]
    };

    it('should register a load scenario carrying thresholds, pacing, feeder, captures, weighted steps and a shape', async function () {
        await client.clearLoadScenarios();
        var registration = await client.loadScenario(richLoadScenarioDefinition);
        assert.ok(registration, "loadScenario should resolve a registration object");
        assert.equal(registration.name, richLoadScenarioDefinition.name, "registration should echo the scenario name");

        // round-trip the persisted definition and assert the new fields survived
        var entry = await client.getLoadScenario(richLoadScenarioDefinition.name);
        var def = entry.definition || entry;
        assert.equal(def.stepSelection, "WEIGHTED", "stepSelection should round-trip");
        assert.equal(def.abortOnFail, true, "abortOnFail should round-trip");
        assert.equal(def.abortGraceMillis, 2000, "abortGraceMillis should round-trip");
        assert.ok(Array.isArray(def.thresholds) && def.thresholds.length === 2, "thresholds should round-trip");
        assert.equal(def.thresholds[0].metric, "LATENCY_P95", "threshold metric should round-trip");
        assert.equal(def.thresholds[0].comparator, "LESS_THAN", "threshold comparator should round-trip");
        assert.ok(def.pacing && def.pacing.mode === "CONSTANT_THROUGHPUT", "pacing should round-trip");
        assert.ok(def.feeder && Array.isArray(def.feeder.rows) && def.feeder.rows.length === 2, "feeder rows should round-trip");
        assert.ok(def.profile && def.profile.shape && def.profile.shape.type === "RAMP_HOLD", "profile shape should round-trip");
        assert.equal(def.steps[0].weight, 7, "step weight should round-trip");
        assert.ok(Array.isArray(def.steps[0].captures) && def.steps[0].captures[0].name === "token", "step captures should round-trip");
        await client.clearLoadScenarios();
    });

    it('should reject getLoadScenarioReport with 404 for a scenario that never ran', async function () {
        var rejected = false;
        try {
            await client.getLoadScenarioReport("node-client-no-such-report");
        } catch (err) {
            rejected = true;
            var message = (typeof err === "string") ? err : String(err);
            assert.ok(message.indexOf("404") !== -1, "expected a 404 but got: " + message);
        }
        assert.equal(rejected, true, "getLoadScenarioReport should reject for a scenario that never ran");
    });

    it('should request the JUnit report form when format="junit" is passed', async function () {
        // The scenario has never run, so the server answers 404 either way; what
        // this asserts is that the client builds the request without throwing and
        // surfaces the server response (i.e. the ?format= path is wired up). A
        // distinct scenario name keeps it independent of other report tests.
        var rejected = false;
        try {
            await client.getLoadScenarioReport("node-client-no-such-report-junit", "junit");
        } catch (err) {
            rejected = true;
            var message = (typeof err === "string") ? err : String(err);
            assert.ok(message.indexOf("404") !== -1, "expected a 404 but got: " + message);
        }
        assert.equal(rejected, true, "getLoadScenarioReport(name, 'junit') should reject for a scenario that never ran");
    });

    it('should generate and register a load scenario from an OpenAPI spec', async function () {
        await client.clearLoadScenarios();
        var spec = JSON.stringify({
            openapi: "3.0.0",
            info: { title: "node-client-load-openapi", version: "1.0.0" },
            servers: [ { url: "http://generated.svc:8080" } ],
            paths: {
                "/widgets": {
                    get: { responses: { "200": { description: "ok" } } }
                }
            }
        });
        var result = await client.generateLoadScenarioFromOpenAPI({
            name: "node-client-load-from-openapi",
            specUrlOrPayload: spec
        });
        assert.ok(result, "generateLoadScenarioFromOpenAPI should resolve a result");
        assert.equal(result.status, "loaded", "result status should be 'loaded'");
        assert.equal(result.name, "node-client-load-from-openapi", "result should echo the scenario name");
        assert.ok(result.scenario, "result should carry the generated scenario");
        assert.ok(Array.isArray(result.scenario.steps) && result.scenario.steps.length >= 1, "generated scenario should have at least one step");

        // it was actually registered in the LOADED state
        var entry = await client.getLoadScenario("node-client-load-from-openapi");
        assert.equal(entry.name, "node-client-load-from-openapi", "generated scenario should be registered");
        await client.clearLoadScenarios();
    });

    it('should apply an explicit target when generating from an OpenAPI spec', async function () {
        await client.clearLoadScenarios();
        var spec = JSON.stringify({
            openapi: "3.0.0",
            info: { title: "node-client-load-openapi-target", version: "1.0.0" },
            paths: {
                "/things": { get: { responses: { "200": { description: "ok" } } } }
            }
        });
        var result = await client.generateLoadScenarioFromOpenAPI({
            name: "node-client-load-from-openapi-target",
            specUrlOrPayload: spec,
            target: { host: "target.svc", port: 9090, scheme: "https" }
        });
        assert.equal(result.status, "loaded", "result status should be 'loaded'");
        assert.ok(result.scenario.steps.length >= 1, "generated scenario should have a step");
        await client.clearLoadScenarios();
    });

    it('should reject generateLoadScenarioFromRecording when there is no recorded traffic', async function () {
        // With no recorded proxy traffic the server returns 400; this exercises
        // that the client issues the PUT and surfaces the error (the success
        // path with recorded traffic requires proxy mode which is out of scope
        // for this no-proxy suite).
        var rejected = false;
        try {
            await client.generateLoadScenarioFromRecording({
                name: "node-client-load-from-recording",
                mode: "TEMPLATIZED"
            });
        } catch (err) {
            rejected = true;
            assert.ok(err, "generateLoadScenarioFromRecording should reject with an error body");
        }
        assert.equal(rejected, true, "generateLoadScenarioFromRecording should reject when there is no recorded traffic");
    });

    // ========================================================================
    // gRPC descriptor management
    //   PUT /mockserver/grpc/descriptors  (raw FileDescriptorSet bytes)
    //   PUT /mockserver/grpc/services     (list registered services)
    //   PUT /mockserver/grpc/clear        (clear descriptors)
    // ========================================================================

    // A real compiled gRPC FileDescriptorSet (greeting.dsc) describing the
    // com.example.grpc.GreetingService service with four methods covering all
    // streaming combinations. Held as base64 so the test is self-contained.
    var greetingDescriptorSetBase64 =
        "CvgDCg5ncmVldGluZy5wcm90bxIQY29tLmV4YW1wbGUuZ3JwYyIiCgxIZWxsb1JlcXVlc3QSEgoEbmFt" +
        "ZRgBIAEoCVIEbmFtZSIrCg1IZWxsb1Jlc3BvbnNlEhoKCGdyZWV0aW5nGAEgASgJUghncmVldGluZzLW" +
        "AgoPR3JlZXRpbmdTZXJ2aWNlEksKCEdyZWV0aW5nEh4uY29tLmV4YW1wbGUuZ3JwYy5IZWxsb1JlcXVl" +
        "c3QaHy5jb20uZXhhbXBsZS5ncnBjLkhlbGxvUmVzcG9uc2USUgoNTGlzdEdyZWV0aW5ncxIeLmNvbS5l" +
        "eGFtcGxlLmdycGMuSGVsbG9SZXF1ZXN0Gh8uY29tLmV4YW1wbGUuZ3JwYy5IZWxsb1Jlc3BvbnNlMAES" +
        "VQoQQ29sbGVjdEdyZWV0aW5ncxIeLmNvbS5leGFtcGxlLmdycGMuSGVsbG9SZXF1ZXN0Gh8uY29tLmV4" +
        "YW1wbGUuZ3JwYy5IZWxsb1Jlc3BvbnNlKAESSwoEQ2hhdBIeLmNvbS5leGFtcGxlLmdycGMuSGVsbG9S" +
        "ZXF1ZXN0Gh8uY29tLmV4YW1wbGUuZ3JwYy5IZWxsb1Jlc3BvbnNlKAEwAUIiChBjb20uZXhhbXBsZS5n" +
        "cnBjQg5HcmVldGluZ1Byb3Rvc2IGcHJvdG8z";

    it('should upload gRPC descriptor and list registered services', async function () {
        var descriptorSet = Buffer.from(greetingDescriptorSetBase64, "base64");

        var uploadResponse = await client.uploadGrpcDescriptor(descriptorSet);
        assert.equal(uploadResponse.statusCode, 201,
            "uploadGrpcDescriptor should return 201 Created");

        var services = await client.retrieveGrpcServices();
        assert.ok(Array.isArray(services), "retrieveGrpcServices should return an array");

        var greeting = services.find(function (s) {
            return s.name === "com.example.grpc.GreetingService";
        });
        assert.ok(greeting, "GreetingService should be registered");

        var methodNames = greeting.methods.map(function (m) { return m.name; });
        assert.ok(methodNames.indexOf("Greeting") !== -1, "Greeting method should be present");
        assert.ok(methodNames.indexOf("Chat") !== -1, "Chat method should be present");

        var chat = greeting.methods.find(function (m) { return m.name === "Chat"; });
        assert.equal(chat.clientStreaming, true, "Chat should be client streaming");
        assert.equal(chat.serverStreaming, true, "Chat should be server streaming");
    });

    it('should clear gRPC descriptors', async function () {
        var descriptorSet = Buffer.from(greetingDescriptorSetBase64, "base64");
        await client.uploadGrpcDescriptor(descriptorSet);

        var before = await client.retrieveGrpcServices();
        assert.ok(before.length >= 1, "at least one service should be registered before clear");

        await client.clearGrpcDescriptors();

        var after = await client.retrieveGrpcServices();
        assert.equal(after.length, 0, "all gRPC services should have been cleared");
    });

    it('should reject uploadGrpcDescriptor without bytes', function () {
        assert.throws(function () { client.uploadGrpcDescriptor(); },
            /requires the descriptor set bytes/);
    });

    it('should reject an invalid gRPC descriptor set', async function () {
        await assert.rejects(
            client.uploadGrpcDescriptor(Buffer.from("not a valid descriptor set")),
            function (err) {
                return typeof err === "string" && /gRPC descriptor/i.test(err);
            }
        );
    });

    it('should verify by expectation id', async function () {
        // Create expectation with known id
        await client.mockAnyResponse({
            'id': 'test-verify-by-id',
            'httpRequest': { 'path': '/byIdPath' },
            'httpResponse': { 'statusCode': 200, 'body': 'byId' },
            'times': { 'unlimited': true }
        });

        await sendRequest("GET", mockServerHost, mockServerPort, "/byIdPath");

        await client.verifyById({ 'id': 'test-verify-by-id' }, 1, 1);
    });

    it('should fail verify by expectation id when not matched', async function () {
        await client.mockAnyResponse({
            'id': 'test-verify-by-id-fail',
            'httpRequest': { 'path': '/byIdPathFail' },
            'httpResponse': { 'statusCode': 200, 'body': 'byId' },
            'times': { 'unlimited': true }
        });

        // do NOT send request
        try {
            await client.verifyById({ 'id': 'test-verify-by-id-fail' }, 1);
            assert.fail("should have thrown");
        } catch (message) {
            assert.ok(typeof message === 'string', "error should be a string");
        }
    });

    it('should verify sequence by expectation ids', async function () {
        await client.mockAnyResponse({
            'id': 'seq-a',
            'httpRequest': { 'path': '/seqA' },
            'httpResponse': { 'statusCode': 200, 'body': 'a' },
            'times': { 'unlimited': true }
        });
        await client.mockAnyResponse({
            'id': 'seq-b',
            'httpRequest': { 'path': '/seqB' },
            'httpResponse': { 'statusCode': 200, 'body': 'b' },
            'times': { 'unlimited': true }
        });

        await sendRequest("GET", mockServerHost, mockServerPort, "/seqA");
        await sendRequest("GET", mockServerHost, mockServerPort, "/seqB");

        await client.verifySequenceById({ 'id': 'seq-a' }, { 'id': 'seq-b' });
    });

    it('should clear by expectation id', async function () {
        await client.mockAnyResponse({
            'id': 'clear-by-id-test',
            'httpRequest': { 'path': '/clearById' },
            'httpResponse': { 'statusCode': 200, 'body': 'cleared' },
            'times': { 'unlimited': true }
        });

        var r = await sendRequest("GET", mockServerHost, mockServerPort, "/clearById");
        assert.equal(r.statusCode, 200);

        await client.clearById('clear-by-id-test');

        await assert.rejects(sendRequest("GET", mockServerHost, mockServerPort, "/clearById"), function (err) { return err === "404 Not Found"; });
    });

    it('should verify zero interactions', async function () {
        // After reset (from beforeEach), no requests should have been recorded
        await client.verifyZeroInteractions();
    });

    it('should fail verify zero interactions when requests exist', async function () {
        await client.mockSimpleResponse('/somePath', {name: 'value'}, 200);
        await sendRequest("GET", mockServerHost, mockServerPort, "/somePath");

        try {
            await client.verifyZeroInteractions();
            assert.fail("should have thrown");
        } catch (message) {
            assert.ok(typeof message === 'string', "error should be a string");
        }
    });

    // ---- response verification tests ----

    it('should verify response by status code', async function () {
        await client.mockSimpleResponse('/somePath', {name: 'value'}, 203);
        var r = await sendRequest("POST", mockServerHost, mockServerPort, "/somePath", "someBody");
        assert.equal(r.statusCode, 203);

        await client.verifyResponse({ 'statusCode': 203 }, 1, 1);
    });

    it('should fail verifyResponse when no matching response exists', async function () {
        await client.mockSimpleResponse('/somePath', {name: 'value'}, 200);
        await sendRequest("GET", mockServerHost, mockServerPort, "/somePath");

        try {
            await client.verifyResponse({ 'statusCode': 404 }, 1);
            assert.fail("should have thrown");
        } catch (message) {
            assert.ok(typeof message === 'string', "error should be a string");
        }
    });

    it('should verify request and response pair', async function () {
        await client.mockSimpleResponse('/somePath', {name: 'value'}, 203);
        var r = await sendRequest("POST", mockServerHost, mockServerPort, "/somePath", "someBody");
        assert.equal(r.statusCode, 203);

        await client.verifyRequestAndResponse(
            { 'method': 'POST', 'path': '/somePath', 'body': 'someBody' },
            { 'statusCode': 203 },
            1, 1
        );
    });

    it('should fail verifyRequestAndResponse when request matches but response does not', async function () {
        await client.mockSimpleResponse('/somePath', {name: 'value'}, 200);
        await sendRequest("POST", mockServerHost, mockServerPort, "/somePath", "someBody");

        try {
            await client.verifyRequestAndResponse(
                { 'method': 'POST', 'path': '/somePath', 'body': 'someBody' },
                { 'statusCode': 404 },
                1
            );
            assert.fail("should have thrown");
        } catch (message) {
            assert.ok(typeof message === 'string', "error should be a string");
        }
    });

    it('should verify sequence with responses', async function () {
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);
        await client.mockSimpleResponse('/somePathTwo', {name: 'two'}, 202);

        var r1 = await sendRequest("POST", mockServerHost, mockServerPort, "/somePathOne", "someBody");
        assert.equal(r1.statusCode, 201);

        var r2 = await sendRequest("GET", mockServerHost, mockServerPort, "/somePathTwo");
        assert.equal(r2.statusCode, 202);

        await client.verifySequenceWithResponses([
            { request: { 'method': 'POST', 'path': '/somePathOne', 'body': 'someBody' }, response: { 'statusCode': 201 } },
            { request: { 'method': 'GET', 'path': '/somePathTwo' }, response: { 'statusCode': 202 } }
        ]);
    });

    it('should fail verifySequenceWithResponses when response does not match', async function () {
        await client.mockSimpleResponse('/somePathOne', {name: 'one'}, 201);
        await client.mockSimpleResponse('/somePathTwo', {name: 'two'}, 202);

        await sendRequest("POST", mockServerHost, mockServerPort, "/somePathOne", "someBody");
        await sendRequest("GET", mockServerHost, mockServerPort, "/somePathTwo");

        try {
            await client.verifySequenceWithResponses([
                { request: { 'method': 'POST', 'path': '/somePathOne' }, response: { 'statusCode': 201 } },
                { request: { 'method': 'GET', 'path': '/somePathTwo' }, response: { 'statusCode': 404 } }
            ]);
            assert.fail("should have thrown");
        } catch (message) {
            assert.ok(typeof message === 'string', "error should be a string");
        }
    });

    it('should retrieve recorded expectations', async function () {
        // retrieveRecordedExpectations returns expectations recorded by the
        // proxy. Since we are not proxying, this should return an empty array
        // (but the endpoint should work without error).
        var expectations = await client.retrieveRecordedExpectations("/.*");
        assert.ok(Array.isArray(expectations), "should return an array");
    });

    // ========================================================================
    // PARITY TESTS: control-plane helpers ported from the Java client —
    // metrics, configuration, pact, file store, import, operating mode, WSDL.
    // ========================================================================

    // --- Metrics -----------------------------------------------------------
    it('should retrieve the JSON metrics snapshot', async function () {
        var metrics = await client.retrieveMetrics();
        // metricsEnabled defaults to false -> {} ; either way it is an object.
        assert.ok(metrics && typeof metrics === 'object', "retrieveMetrics should resolve an object");
        assert.ok(!Array.isArray(metrics), "metrics snapshot is a map, not an array");
    });

    it('should scrape Prometheus metrics or 404 when disabled', async function () {
        // The integration server is started without metricsEnabled, so the
        // scrape endpoint replies 404 (rejected by the transport). When enabled
        // it resolves the exposition text. Accept either deterministically.
        try {
            var text = await client.scrapeMetrics();
            assert.ok(typeof text === 'string', "scrapeMetrics should resolve a string when enabled");
        } catch (err) {
            assert.equal(err, "404 Not Found", "scrapeMetrics should 404 when metrics disabled");
        }
    });

    // --- Configuration -----------------------------------------------------
    it('should retrieve and update configuration', async function () {
        var config = await client.retrieveConfiguration();
        assert.ok(config && typeof config === 'object', "retrieveConfiguration should resolve an object");
        // Partial update: flip a single boolean and confirm it round-trips.
        var updated = await client.updateConfiguration({ logLevel: "WARN" });
        assert.ok(updated && typeof updated === 'object', "updateConfiguration should resolve the updated config");
        assert.equal(updated.logLevel, "WARN", "updateConfiguration should apply logLevel");
    });

    // --- File store --------------------------------------------------------
    it('should store, list, retrieve and delete a text file', async function () {
        var name = "node-parity-" + guid() + ".txt";
        var stored = await client.storeFile(name, "hello world");
        assert.equal(stored.name, name);
        assert.equal(stored.size, Buffer.byteLength("hello world"));

        var names = await client.listFiles();
        assert.ok(Array.isArray(names), "listFiles should resolve an array");
        assert.ok(names.indexOf(name) !== -1, "listFiles should include the stored file");

        var retrieved = await client.retrieveFile(name);
        assert.equal(retrieved, "hello world", "retrieveFile should resolve the raw content directly");

        await client.deleteFile(name);
        // A missing file REJECTS (matches the other clients) so callers catch it
        // rather than mistaking a 404 object for success. The client returns a
        // custom thenable, so await it inside a function for assert.rejects.
        await assert.rejects(
            async function () { await client.retrieveFile(name); },
            function (err) {
                return err instanceof Error && err.message.indexOf("file not found") !== -1;
            },
            "retrieveFile should reject with a not-found Error for an unknown file"
        );
    });

    it('should store a binary file (base64 on the wire)', async function () {
        var name = "node-parity-bin-" + guid();
        var bytes = Buffer.from([0x00, 0x01, 0x02, 0xff]);
        var stored = await client.storeBinaryFile(name, bytes);
        assert.equal(stored.name, name);
        assert.equal(stored.size, bytes.length, "binary file size should be the decoded byte length");
    });

    // --- Operating mode ----------------------------------------------------
    it('should set and retrieve the operating mode', async function () {
        var spy = await client.setMode(mockServer.MockMode.SPY);
        assert.equal(spy.mode, "SPY");
        assert.equal(spy.proxyUnmatchedRequests, true, "SPY should enable proxy-on-no-match");

        var current = await client.retrieveMode();
        assert.equal(current.mode, "SPY");

        // Reset to the default so later tests are unaffected.
        var simulate = await client.setMode("simulate");
        assert.equal(simulate.mode, "SIMULATE");
        assert.equal(simulate.proxyUnmatchedRequests, false, "SIMULATE should disable proxy-on-no-match");
    });

    // --- Import (HAR) ------------------------------------------------------
    it('should import a HAR document as expectations', async function () {
        var har = {
            log: {
                version: "1.2",
                entries: [
                    {
                        request: { method: "GET", url: "http://localhost/har-imported", headers: [], queryString: [] },
                        response: {
                            status: 200,
                            headers: [{ name: "Content-Type", value: "application/json" }],
                            content: { mimeType: "application/json", text: "{\"har\":true}" }
                        }
                    }
                ]
            }
        };
        var expectations = await client.importHar(har);
        assert.ok(Array.isArray(expectations), "importHar should resolve an array of expectations");
        assert.ok(expectations.length >= 1, "importHar should create at least one expectation");
    });

    // --- Pact import / export / verify ------------------------------------
    it('should export, import and verify a Pact contract', async function () {
        await client.mockSimpleResponse('/pact-endpoint', { ok: true }, 200);

        // Export the active expectations as a Pact contract.
        var contract = await client.pactExport("node-consumer", "node-provider");
        assert.ok(contract && typeof contract === 'object', "pactExport should resolve a contract object");
        assert.ok(contract.interactions, "exported contract should contain interactions");

        // Re-import it (round-trip) and verify against the active expectations.
        var imported = await client.pactImport(contract);
        assert.ok(Array.isArray(imported), "pactImport should resolve an array of expectations");

        var report = await client.pactVerify(contract);
        assert.equal(typeof report.verified, "boolean", "pactVerify report should carry a `verified` boolean");
        assert.equal(report.verified, true, "round-tripped contract should verify as passed (202)");
    });

    it('should resolve pactVerify with verified=false on a failing contract (406)', async function () {
        // A contract whose interaction has no matching expectation -> 406 FAIL.
        // pactVerify must RESOLVE (not throw) with verified:false.
        var failingContract = {
            consumer: { name: "node-consumer" },
            provider: { name: "node-provider" },
            interactions: [
                {
                    description: "unmatched interaction",
                    request: { method: "GET", path: "/no-such-pact-path" },
                    response: { status: 200 }
                }
            ],
            metadata: { pactSpecification: { version: "3.0.0" } }
        };
        var report = await client.pactVerify(failingContract);
        assert.equal(typeof report.verified, "boolean");
        assert.equal(report.verified, false, "pactVerify should resolve verified:false on 406, not throw");
    });

    // --- WSDL --------------------------------------------------------------
    it('should generate expectations from a WSDL document', async function () {
        var wsdl =
            '<?xml version="1.0" encoding="UTF-8"?>' +
            '<wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"' +
            ' xmlns:soap="http://schemas.xmlsoap.org/wsdl/soap/"' +
            ' xmlns:tns="http://example.com/stock" xmlns:xsd="http://www.w3.org/2001/XMLSchema"' +
            ' targetNamespace="http://example.com/stock">' +
            '  <wsdl:types>' +
            '    <xsd:schema targetNamespace="http://example.com/stock">' +
            '      <xsd:element name="GetPriceRequest"><xsd:complexType><xsd:sequence>' +
            '        <xsd:element name="symbol" type="xsd:string"/>' +
            '      </xsd:sequence></xsd:complexType></xsd:element>' +
            '      <xsd:element name="GetPriceResponse"><xsd:complexType><xsd:sequence>' +
            '        <xsd:element name="price" type="xsd:double"/>' +
            '      </xsd:sequence></xsd:complexType></xsd:element>' +
            '    </xsd:schema>' +
            '  </wsdl:types>' +
            '  <wsdl:message name="GetPriceInput"><wsdl:part name="body" element="tns:GetPriceRequest"/></wsdl:message>' +
            '  <wsdl:message name="GetPriceOutput"><wsdl:part name="body" element="tns:GetPriceResponse"/></wsdl:message>' +
            '  <wsdl:portType name="StockPortType">' +
            '    <wsdl:operation name="GetPrice">' +
            '      <wsdl:input message="tns:GetPriceInput"/>' +
            '      <wsdl:output message="tns:GetPriceOutput"/>' +
            '    </wsdl:operation>' +
            '  </wsdl:portType>' +
            '  <wsdl:binding name="StockBinding" type="tns:StockPortType">' +
            '    <soap:binding style="document" transport="http://schemas.xmlsoap.org/soap/http"/>' +
            '    <wsdl:operation name="GetPrice">' +
            '      <soap:operation soapAction="http://example.com/stock/GetPrice"/>' +
            '      <wsdl:input><soap:body use="literal"/></wsdl:input>' +
            '      <wsdl:output><soap:body use="literal"/></wsdl:output>' +
            '    </wsdl:operation>' +
            '  </wsdl:binding>' +
            '  <wsdl:service name="StockService">' +
            '    <wsdl:port name="StockPort" binding="tns:StockBinding">' +
            '      <soap:address location="http://example.com/stock"/>' +
            '    </wsdl:port>' +
            '  </wsdl:service>' +
            '</wsdl:definitions>';
        var expectations = await client.wsdlExpectation(wsdl);
        assert.ok(Array.isArray(expectations), "wsdlExpectation should resolve an array");
        assert.ok(expectations.length >= 1, "wsdlExpectation should generate at least one expectation");
    });
});
