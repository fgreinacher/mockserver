/*
 * mockserver
 * http://mock-server.com
 *
 * Copyright (c) 2014 James Bloom
 * Licensed under the Apache License, Version 2.0
 */

'use strict';

/*
 * Runs the Node client integration tests against the MockServer jar built from
 * THIS monorepo checkout, instead of the published `mockserver-node` version.
 *
 * The published `mockserver-node` devDependency downloads a fixed MockServer
 * release from Maven Central, so the integration tests would otherwise run
 * against stale server behaviour (e.g. an older OpenAPI request schema) rather
 * than the code currently under development. This script locates the freshly
 * built `mockserver-netty-*-jar-with-dependencies.jar`, starts it, runs the
 * test files, and shuts it down again.
 *
 * Build the server jar first:
 *   (cd ../mockserver && ./mvnw -q -pl mockserver-netty -am package -DskipTests)
 * or: npm run build:server
 */

const { spawn } = require('child_process');
const http = require('http');
const fs = require('fs');
const path = require('path');

const PORT = parseInt(process.env.MOCKSERVER_PORT, 10) || 1080;
const TARGET_DIR = path.resolve(__dirname, '..', '..', 'mockserver', 'mockserver-netty', 'target');
// Discovery lives in test/discover_test_files.js so this script, package.json
// and the CI step cannot drift apart -- see the comment there.
const { discoverTestFiles } = require('./discover_test_files.js');

const TEST_FILES = discoverTestFiles();
if (TEST_FILES.length === 0) {
    console.error('ERROR: no *_test.js files discovered under test/no_proxy or test/with_proxy');
    process.exit(1);
}
console.log('Discovered ' + TEST_FILES.length + ' test file(s)');

function findServerJar() {
    let files;
    try {
        files = fs.readdirSync(TARGET_DIR);
    } catch (err) {
        return null;
    }
    const jars = files
        .filter((f) => /^mockserver-netty-.*-jar-with-dependencies\.jar$/.test(f))
        .map((f) => path.join(TARGET_DIR, f));
    if (jars.length === 0) {
        return null;
    }
    // newest build wins, so a rebuilt jar is always preferred
    jars.sort((a, b) => fs.statSync(b).mtimeMs - fs.statSync(a).mtimeMs);
    return jars[0];
}

const jar = findServerJar();
if (!jar) {
    console.error('ERROR: no locally-built MockServer jar found in ' + TARGET_DIR);
    console.error('Build it first:  npm run build:server');
    console.error('            (or) (cd ../mockserver && ./mvnw -q -pl mockserver-netty -am package -DskipTests)');
    process.exit(1);
}
console.log('Using locally-built MockServer jar: ' + jar);

// Mirror the CORS options the published `mockserver-node` harness used so the
// browser/CORS-dependent tests behave identically.
const jvmOptions = [
    '-Dmockserver.enableCORSForAllResponses=true',
    '-Dmockserver.corsAllowMethods=CONNECT, DELETE, GET, HEAD, OPTIONS, POST, PUT, PATCH, TRACE',
    '-Dmockserver.corsAllowHeaders=Allow, Content-Encoding, Content-Length, Content-Type, ETag, Expires, Last-Modified, Location, Server, Vary, Authorization',
    '-Dmockserver.corsAllowCredentials=true',
    '-Dmockserver.corsMaxAgeInSeconds=300'
];

const server = spawn('java', [...jvmOptions, '-jar', jar, '-serverPort', String(PORT)], {
    stdio: ['ignore', process.env.VERBOSE ? 'inherit' : 'ignore', 'inherit']
});

let stopped = false;
function stopServer() {
    if (stopped) {
        return;
    }
    stopped = true;
    if (server.pid && !server.killed) {
        try {
            server.kill('SIGTERM');
        } catch (err) {
            /* already gone */
        }
    }
}

process.on('exit', stopServer);
process.on('SIGINT', () => { stopServer(); process.exit(130); });
process.on('SIGTERM', () => { stopServer(); process.exit(143); });

server.on('error', (err) => {
    console.error('Failed to start MockServer (is `java` on the PATH?): ' + err.message);
    process.exit(1);
});
server.on('exit', (code, signal) => {
    if (!stopped) {
        console.error('MockServer exited before the tests finished (code=' + code + ', signal=' + signal + ')');
        process.exit(1);
    }
});

function waitForServer(retriesRemaining, onReady) {
    const req = http.request(
        { host: '127.0.0.1', port: PORT, path: '/mockserver/status', method: 'PUT' },
        (res) => {
            res.resume();
            onReady();
        }
    );
    req.on('error', () => {
        if (retriesRemaining <= 0) {
            console.error('MockServer did not become ready on port ' + PORT + ' within the timeout');
            stopServer();
            process.exit(1);
        }
        setTimeout(() => waitForServer(retriesRemaining - 1, onReady), 200);
    });
    req.end();
}

// up to ~60s (300 * 200ms) to allow for a cold JVM start
waitForServer(300, () => {
    console.log('MockServer is ready on port ' + PORT + ' — running tests');
    // Delegated to run_node_tests.js, which fails on a `not ok` TAP line as well
    // as on a non-zero exit -- node reports "# fail 0" and exit 0 when a suite
    // throws during construction, so the exit code alone is not a gate.
    const tests = spawn(
        process.execPath,
        [path.resolve(__dirname, 'run_node_tests.js'), ...TEST_FILES],
        { stdio: 'inherit', cwd: path.resolve(__dirname, '..') }
    );
    tests.on('exit', (code) => {
        stopServer();
        process.exit(code === null ? 1 : code);
    });
});
