/*
 * mockserver
 * http://mock-server.com
 *
 * Copyright (c) 2014 James Bloom
 * Licensed under the Apache License, Version 2.0
 */

'use strict';

/*
 * Single source of truth for which files make up the node:test suite.
 *
 * Test files are DISCOVERED, not hand-listed. Three divergent hand-maintained
 * lists (run_with_local_server.js, package.json's test:external/test:coverage,
 * and the CI step) each missed a different subset, so test files added later
 * silently never ran anywhere -- sre_slo_chaos_test.js covered a real
 * verification bug while running in neither `npm test` nor CI. Discovery keeps
 * every caller in lockstep by construction, which is why this lives in one
 * module rather than being reimplemented per caller.
 */

const fs = require('fs');
const path = require('path');

const TEST_DIRECTORIES = ['test/no_proxy', 'test/with_proxy'];

function discoverTestFiles() {
    return TEST_DIRECTORIES.flatMap((dir) => {
        const abs = path.resolve(__dirname, '..', dir);
        let entries;
        try {
            entries = fs.readdirSync(abs);
        } catch (err) {
            return [];
        }
        return entries
            .filter((f) => f.endsWith('_test.js'))
            .sort()
            .map((f) => dir + '/' + f);
    });
}

module.exports = { discoverTestFiles, TEST_DIRECTORIES };
