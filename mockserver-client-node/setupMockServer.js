/*
 * mockserver
 * http://mock-server.com
 *
 * Copyright (c) 2014 James Bloom
 * Licensed under the Apache License, Version 2.0
 */

'use strict';

/*
 * Framework-agnostic setup/teardown helper for jest, vitest, mocha, node:test,
 * or plain scripts. Starts a real MockServer via the `mockserver-node` launcher
 * and returns a ready `mockServerClient` plus a `stop()` teardown function.
 *
 * `mockserver-node` is required lazily (only when setupMockServer() is called),
 * so merely importing this module - or the client - never pulls it in. Add it to
 * your project's devDependencies to use this helper:
 *
 *     npm install --save-dev mockserver-node
 *
 * jest / vitest example:
 *
 *     const { setupMockServer } = require('mockserver-client/setupMockServer');
 *     let mockServer;
 *     beforeAll(async () => { mockServer = await setupMockServer({ serverPort: 1080 }); });
 *     afterAll(async () => { await mockServer.stop(); });
 *     test('responds', async () => {
 *         await mockServer.client.mockAnyResponse({
 *             httpRequest: { path: '/hello' },
 *             httpResponse: { body: 'world' }
 *         });
 *     });
 *
 * As a vitest/jest globalSetup module, export the returned teardown:
 *
 *     // global-setup.js
 *     const { setupMockServer } = require('mockserver-client/setupMockServer');
 *     module.exports = async () => {
 *         const mockServer = await setupMockServer({ serverPort: 1080 });
 *         return async () => { await mockServer.stop(); };
 *     };
 */

const { mockServerClient } = require('./mockServerClient');

/**
 * Start MockServer and return a ready client plus a teardown function.
 *
 * @param {object} [options]
 * @param {number} [options.serverPort=1080] port to start MockServer on
 * @param {string} [options.host='localhost'] host the client connects to
 * @param {boolean} [options.verbose=false] verbose launcher logging
 * @param {string}  [options.contextPath] optional client context path
 * @param {boolean} [options.tls] connect the client over TLS
 * @param {string}  [options.caCertPemFilePath] CA cert for the TLS client
 * Any other properties are passed straight through to `mockserver-node`'s
 * `start_mockserver` (e.g. `initializationJsonPath`, `jvmOptions`, `trace`,
 * `mockServerVersion`, `proxyRemotePort`).
 * @returns {Promise<{client: object, host: string, serverPort: number, stop: () => Promise<void>}>}
 */
async function setupMockServer(options) {
    const opts = options || {};
    const serverPort = opts.serverPort || 1080;
    const host = opts.host || 'localhost';
    const verbose = opts.verbose || false;

    // Lazy require so importing this module in a browser bundle (or without
    // mockserver-node installed) never fails until the helper is actually used.
    let mockserverNode;
    try {
        mockserverNode = require('mockserver-node');
    } catch (err) {
        throw new Error(
            'setupMockServer requires the "mockserver-node" package. ' +
            'Install it with: npm install --save-dev mockserver-node'
        );
    }

    // Build the start options, passing through any extra launcher settings while
    // making sure serverPort/verbose take precedence.
    const startOptions = Object.assign({}, opts, { serverPort: serverPort, verbose: verbose });
    delete startOptions.host;
    delete startOptions.contextPath;
    delete startOptions.tls;
    delete startOptions.caCertPemFilePath;

    await mockserverNode.start_mockserver(startOptions);

    const client = mockServerClient(host, serverPort, opts.contextPath, opts.tls, opts.caCertPemFilePath);

    let stopped = false;
    const stop = async function () {
        if (stopped) {
            return;
        }
        stopped = true;
        await mockserverNode.stop_mockserver({ serverPort: serverPort, verbose: verbose });
    };

    return { client: client, host: host, serverPort: serverPort, stop: stop };
}

module.exports = { setupMockServer: setupMockServer };
