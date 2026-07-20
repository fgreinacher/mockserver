#!/usr/bin/env node
/*
 * mockserver
 * http://mock-server.com
 *
 * Copyright (c) 2014 James Bloom
 * Licensed under the Apache License, Version 2.0
 */

'use strict';

/*
 * Runs the node:test suite and decides pass/fail on BOTH the exit code AND the
 * TAP stream, because neither alone is sufficient.
 *
 * `node --test` reports success when a suite throws while it is being
 * CONSTRUCTED -- for example a throw inside a `describe()` callback, or a
 * missing fixture that the `describe()` body dereferences. Measured on
 * node v22.21.1, a file containing
 *
 *     describe('suite', () => { throw new Error('boom'); it('x', () => {}); });
 *
 * produces:
 *
 *     not ok 1 - suite
 *     # tests 0 / # pass 0 / # fail 0
 *     exit code 0
 *
 * so a `# fail`-only check AND an exit-code-only check both call it green while
 * the entire suite never ran. (A throw at module IMPORT time is handled
 * correctly by node -- that one does exit non-zero -- so the exposure is
 * specifically suite construction.) Every one of this package's test files uses
 * `describe()`, so every one of them can fail this way.
 *
 * The reporter is pinned to `tap` rather than left to default, because the
 * default depends on whether stdout is a TTY: a local run and a CI run would
 * otherwise be parsed differently, which is exactly the sort of
 * environment-dependent gate this wrapper exists to remove.
 */

const { spawn } = require('child_process');
const path = require('path');
const { discoverTestFiles } = require('./discover_test_files.js');

const PACKAGE_ROOT = path.resolve(__dirname, '..');

// an explicit file list (argv) wins, so a developer can run a single file
const explicitFiles = process.argv.slice(2);
const testFiles = explicitFiles.length > 0 ? explicitFiles : discoverTestFiles();

if (testFiles.length === 0) {
    console.error('ERROR: no *_test.js files discovered under test/no_proxy or test/with_proxy');
    process.exit(1);
}
if (explicitFiles.length === 0) {
    console.log('Discovered ' + testFiles.length + ' test file(s)');
}

const child = spawn(
    process.execPath,
    ['--test', '--test-reporter=tap', '--test-force-exit', '--test-concurrency=1', ...testFiles],
    { cwd: PACKAGE_ROOT, stdio: ['inherit', 'pipe', 'inherit'] }
);

// A subtest failure is indented, a top-level one is not, so match on the
// trimmed line. TAP diagnostics are '#'-prefixed and passing lines start with
// 'ok', so neither can collide with this.
const NOT_OK = /^not ok\b/;
const notOkLines = [];
let pending = '';

function scan(chunk) {
    pending += chunk;
    const lines = pending.split('\n');
    pending = lines.pop();
    for (const line of lines) {
        if (NOT_OK.test(line.trim())) {
            notOkLines.push(line.trim());
        }
    }
}

child.stdout.on('data', (data) => {
    process.stdout.write(data);
    scan(data.toString());
});

child.on('error', (err) => {
    console.error('ERROR: failed to start the node:test run: ' + err.message);
    process.exit(1);
});

child.on('close', (code, signal) => {
    if (pending.trim() && NOT_OK.test(pending.trim())) {
        notOkLines.push(pending.trim());
    }

    const exitCode = code === null ? 1 : code;

    if (notOkLines.length > 0) {
        console.error('');
        console.error('ERROR: the node:test run reported ' + notOkLines.length + ' failing TAP assertion(s):');
        for (const line of notOkLines) {
            console.error('  ' + line);
        }
        if (exitCode === 0) {
            console.error('');
            console.error('The run exited 0 despite the failure above. This is the suite-construction');
            console.error('case described at the top of test/run_node_tests.js: node reports');
            console.error('"# fail 0" and exit 0 when a describe() body throws, so the exit code');
            console.error('alone would have called this run green.');
        }
        process.exit(1);
    }

    if (exitCode !== 0) {
        console.error('');
        console.error('ERROR: the node:test run exited ' + exitCode +
            (signal ? ' (signal ' + signal + ')' : '') + ' with no failing TAP assertion.');
        process.exit(exitCode);
    }

    process.exit(0);
});
