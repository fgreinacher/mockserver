/*
 * mockserver
 * http://mock-server.com
 *
 * Copyright (c) 2014 James Bloom
 * Licensed under the Apache License, Version 2.0
 */

(function () {
    "use strict";

    module.exports = {
        mockServerClient: require('./mockServerClient').mockServerClient,
        MockMode: require('./mockServerClient').MockMode,
        llm: require('./llm'),
        mcpMock: require('./mcpMockBuilder').mcpMock,
        a2aMock: require('./a2aMockBuilder').a2aMock
    };
})();