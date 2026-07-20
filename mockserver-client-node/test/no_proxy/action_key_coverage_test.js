'use strict';

/*
 * Guards the Node client's expectation action-key handling.
 *
 * `addDefaultExpectationHeaders` must know every action key the server recognises. When a key is
 * missing the client injects an empty `httpResponse` alongside the real action; the server then
 * counts two actions with no primary and rejects the expectation with "exactly one must be marked
 * as primary" — i.e. that action becomes uncreatable from Node. `grpcBidiResponse`,
 * `httpForwardValidateAction` and `httpForwardWithFallback` were all missing this way.
 *
 * Both tests here are OBSERVABLE — neither reaches into the client's internals:
 *   1. the action-key list is derived from the server's own expectation.json schema, so a
 *      newly-added server action fails here until the Node client is taught about it;
 *   2. the request the client actually PUTs is captured off the wire by a real HTTP listener and
 *      asserted to carry exactly one action.
 *
 * The server-side consequence of a second action (rejection with "exactly one must be marked as
 * primary") is pinned separately, against the real parser, by
 * mockserver-core ExpectationActionCountTest.
 *
 * Needs no MockServer instance — test 2 stands up a throwaway HTTP listener.
 */

var { describe, it, before, after } = require('node:test');
var assert = require('node:assert/strict');
var fs = require('fs');
var path = require('path');
var http = require('http');

var { NON_HTTP_RESPONSE_ACTION_KEYS, mockServerClient } = require('../../mockServerClient');

// test/no_proxy -> test -> mockserver-client-node -> <repo root>
var SCHEMA_PATH = path.resolve(
  __dirname, '..', '..', '..',
  'mockserver', 'mockserver-core', 'src', 'main', 'resources',
  'org', 'mockserver', 'model', 'schema', 'expectation.json'
);

// Everything in expectation.json that is NOT an action: matchers, scenario/state plumbing,
// lifecycle metadata. Listed explicitly (rather than pattern-matched) so that a new server key
// lands in neither bucket by accident and forces a deliberate decision here.
var NON_ACTION_KEYS = new Set([
  'id', 'priority', 'percentage', 'chaos', 'rateLimit',
  'httpRequest', 'httpResponse',
  'beforeActions', 'afterActions', 'capture',
  'namespace', 'scenarioName', 'scenarioState', 'newScenarioState',
  'httpResponses', 'responseMode', 'responseWeights', 'switchAfter',
  'crossProtocolScenarios',
  'times', 'timeToLive', 'steps', 'timestamp'
]);

function schemaActionKeys() {
  var schema = JSON.parse(fs.readFileSync(SCHEMA_PATH, 'utf8'));
  return Object.keys(schema.properties)
    .filter(function (k) { return !NON_ACTION_KEYS.has(k); })
    .sort();
}

describe('expectation action key coverage', function () {
  it('knows every non-httpResponse action key declared by the server schema', function () {
    var expected = schemaActionKeys();
    var clientKeys = NON_HTTP_RESPONSE_ACTION_KEYS.slice().sort();

    var missing = expected.filter(function (k) { return clientKeys.indexOf(k) === -1; });
    var extra = clientKeys.filter(function (k) { return expected.indexOf(k) === -1; });

    assert.deepEqual(
      missing, [],
      'server action keys the Node client does not know about (expectations using them will be ' +
      'rejected with "exactly one must be marked as primary"): ' + missing.join(', ')
    );
    assert.deepEqual(
      extra, [],
      'Node client lists action keys the server schema does not declare: ' + extra.join(', ')
    );
  });
});

describe('expectation submitted over the wire', function () {
  var server;
  var port;
  var captured;

  before(function () {
    return new Promise(function (resolve) {
      server = http.createServer(function (req, res) {
        var chunks = [];
        req.on('data', function (c) { chunks.push(c); });
        req.on('end', function () {
          captured = {
            method: req.method,
            url: req.url,
            body: JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}')
          };
          res.writeHead(201, { 'content-type': 'application/json' });
          res.end('[]');
        });
      });
      server.listen(0, '127.0.0.1', function () {
        port = server.address().port;
        resolve();
      });
    });
  });

  after(function () {
    return new Promise(function (resolve) { server.close(resolve); });
  });

  // One case per action key the SERVER declares — deliberately not per key the client lists, or a
  // client that has forgotten a key would simply not generate a case for it and stay green.
  // Whatever the action, the body that leaves the client must contain that action and nothing else
  // from the action family. Before the fix, an unlisted key meant `httpResponse: {}` rode along and
  // the server saw two actions with no primary.
  var ACTION_KEYS_ON_WIRE = schemaActionKeys().concat(['httpResponse']);

  schemaActionKeys().forEach(function (actionKey) {
    it('sends exactly one action for ' + actionKey, async function () {
      captured = null;
      var client = mockServerClient('127.0.0.1', port);
      // Default headers MUST be configured, or the early return in addDefaultExpectationHeaders
      // short-circuits before the action-key list is ever consulted and this case cannot observe a
      // missing key. Verified by reverting the fix: without this line every case still passes.
      client.setDefaultHeaders([{ name: 'X-Default-Response', values: ['yes'] }],
                               [{ name: 'X-Default-Request', values: ['yes'] }]);

      var expectation = { httpRequest: { path: '/some/path' } };
      expectation[actionKey] = { some: 'action' };

      await client.mockAnyResponse(expectation);

      assert.ok(captured, 'client sent no request');
      assert.equal(captured.url, '/mockserver/expectation');

      var actionsOnTheWire = Object.keys(captured.body).filter(function (k) {
        return ACTION_KEYS_ON_WIRE.indexOf(k) !== -1;
      });

      assert.deepEqual(
        actionsOnTheWire, [actionKey],
        'the request body must carry exactly the one action that was set; the server counts each ' +
        'action key present and rejects an expectation with more than one and no primary'
      );
    });
  });

  it('still applies default response headers to a plain httpResponse expectation', async function () {
    captured = null;
    var client = mockServerClient('127.0.0.1', port);
    client.setDefaultHeaders([{ name: 'X-Default', values: ['yes'] }], []);

    await client.mockAnyResponse({
      httpRequest: { path: '/some/path' },
      httpResponse: { statusCode: 200 }
    });

    assert.ok(captured, 'client sent no request');
    assert.deepEqual(
      captured.body.httpResponse.headers, [{ name: 'X-Default', values: ['yes'] }],
      'the early-return added for the empty-httpResponse fix must not stop default headers being applied'
    );
  });
});
