/*
 * mockserver
 * http://mock-server.com
 *
 * Copyright (c) 2014 James Bloom
 * Licensed under the Apache License, Version 2.0
 */

'use strict';

module.exports = function (grunt) {

    grunt.initConfig({
        exec: {
            stop_existing_mockservers: '../scripts/stop_MockServer.sh',
            typecheck: 'npx tsc',
            node_test: 'node --test --test-force-exit --test-concurrency=1 test/no_proxy/mcp_mock_builder_test.js test/no_proxy/mock_server_node_client_test.js test/with_proxy/proxy_client_node_test.js',
            node_test_local: 'node test/run_with_local_server.js'
        },
        jshint: {
            options: {
                jshintrc: '.jshintrc'
            },
            // These globs previously pointed at 'js/**/*.js', a directory that does
            // not exist in this package, so the task linted exactly one file --
            // Gruntfile.js itself -- and reported "1 file lint free". No client
            // source and no test was ever linted, making every "jshint clean"
            // claim from this client vacuous. They now name the real sources.
            //
            // test/browser is deliberately excluded: those are Playwright specs
            // carrying `// @ts-check` and are covered by `npm run test:browser`
            // plus tsc. jshint cannot parse them (it reports an unrecoverable
            // syntax error part-way through) so including them would be noise,
            // not coverage. The exclusion is recorded here rather than left
            // implicit, which is the difference from the situation above.
            //
            // Fallout from enabling this for the first time, measured against the
            // 48 files these globs select (pristine sources, before any fix):
            //   327 findings with the .jshintrc as it stood
            //    17 findings with `esversion: 11` alone   <- the real fallout
            // The 310-finding difference was ES-version parse artefacts: the config
            // set no `esversion`, so jshint read an ES2017+ codebase as ES5. Of the
            // residual 17, five were genuine defects (two undeclared globals, a
            // missing semicolon, a `==`, and a late-defined function -- all fixed),
            // eleven were multi-line ternaries (see `laxbreak` in .jshintrc) and one
            // was the legacy `unescape` global (declared there).
            //
            // Derive that number as the COMPLEMENT -- what survives the fix -- not by
            // counting the version-message category. jshint phrases those findings
            // three different ways ("is available in ES6", "is only available in
            // ES6", "is only available in ES8"), and version-only parsing also
            // induces unrelated knock-on errors, so grepping for any one form
            // undercounts. The complement needs no such enumeration to be correct.
            user_defaults: [
                'Gruntfile.js',
                '*.js',
                'test/**/*.js',
                'examples/**/*.js',
                '!test/browser/**/*.js',
                '!**/node_modules/**/*.js'
            ]
        },
        start_mockserver: {
            options: {
                serverPort: parseInt(process.env.MOCKSERVER_PORT, 10) || 1080,
                jvmOptions: [
                    '-Dmockserver.enableCORSForAllResponses=true',
                    '-Dmockserver.corsAllowMethods="CONNECT, DELETE, GET, HEAD, OPTIONS, POST, PUT, PATCH, TRACE"',
                    '-Dmockserver.corsAllowHeaders="Allow, Content-Encoding, Content-Length, Content-Type, ETag, Expires, Last-Modified, Location, Server, Vary, Authorization"',
                    '-Dmockserver.corsAllowCredentials=true -Dmockserver.corsMaxAgeInSeconds=300'
                ],
                mockServerVersion: process.env.MOCKSERVER_VERSION || require('./package.json').version,
                verbose: false
            }
        },
        stop_mockserver: {
            options: {
                serverPort: parseInt(process.env.MOCKSERVER_PORT, 10) || 1080
            }
        },
    });

    grunt.loadNpmTasks('grunt-exec');
    grunt.loadNpmTasks('mockserver-node');
    grunt.loadNpmTasks('grunt-contrib-jshint');
    grunt.registerTask('ts', ['exec:typecheck']);
    // Default: test against the MockServer jar built from this checkout (current code).
    grunt.registerTask('test_node', ['ts', 'exec:node_test_local']);
    grunt.registerTask('test_node_external', ['exec:node_test']);
    grunt.registerTask('test', ['exec:node_test_local']);
    // Legacy: test against the published `mockserver-node` download (a fixed MockServer release).
    grunt.registerTask('test_node_download', ['ts', 'start_mockserver', 'exec:node_test', 'stop_mockserver']);

    grunt.registerTask('default', ['exec:stop_existing_mockservers', 'jshint', 'test_node']);
    grunt.registerTask('headless', ['exec:stop_existing_mockservers', 'jshint', 'test_node']);
};
