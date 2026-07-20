/*
 * mockserver
 * http://mock-server.com
 *
 * Copyright (c) 2014 James Bloom
 * Licensed under the Apache License, Version 2.0
 */
(function () {
    "use strict";

    if (module && require) {
        var WebSocketClient = require('websocket').client;
        var Q = require('q');
        var fs = require('fs');

        var defer = function () {
            var promise = (global.protractor && global.protractor.promise.USE_PROMISE_MANAGER !== false)
                ? global.protractor.promise
                : Q;
            var deferred = promise.defer();

            if (deferred.fulfill && !deferred.resolve) {
                deferred.resolve = deferred.fulfill;
            }
            return deferred;
        };

        var downloadCACert = function (tls, caCertPath, callback) {
            // https://raw.githubusercontent.com/mock-server/mockserver-monorepo/master/mockserver/mockserver-core/src/main/resources/org/mockserver/socket/CertificateAuthorityCertificate.pem

            var dest = "CertificateAuthorityCertificate.pem";
            if (!fs.existsSync('./' + dest)) {
                var options = {
                    protocol: 'https:',
                    method: 'GET',
                    host: "raw.githubusercontent.com",
                    path: "/mock-server/mockserver-monorepo/master/mockserver/mockserver-core/src/main/resources/org/mockserver/socket/CertificateAuthorityCertificate.pem",
                    port: 443,
                };
                var req = require('https').request(options);

                req.once('error', function (error) {
                    console.error('Fetching ' + JSON.stringify(options, null, 2) + ' failed with error ' + error);
                });

                req.once('response', function (res) {
                    if (res.statusCode < 200 || res.statusCode >= 300) {
                        console.error('Fetching ' + JSON.stringify(options, null, 2) + ' failed with HTTP status code ' + res.statusCode);
                    } else {
                        var writeStream = fs.createWriteStream(dest);
                        res.pipe(writeStream);

                        writeStream.on('error', function (error) {
                            console.error('Saving ' + dest + ' failed with error ' + error);
                        });
                        writeStream.on('close', function () {
                            console.log('Saved ' + dest + ' from ' + JSON.stringify(options, null, 2));
                            callback(tls ? [fs.readFileSync(caCertPath || "./" + dest, {encoding: 'utf-8'})] : []);
                        });
                    }
                });

                req.end();
            } else {
                callback(tls ? [fs.readFileSync(caCertPath || "./" + dest, {encoding: 'utf-8'})] : []);
            }
        };

        var MAX_RECONNECT_ATTEMPTS = 3;

        /**
         * Extract the breakpoint id and correlation id from a headers map.
         * Headers may use canonical or lowercase names and values may be
         * arrays or plain strings.
         *
         * @param {Object} headers  the headers map
         * @return {{breakpointId: string|null, correlationId: string|null}}
         */
        var extractBreakpointHeaders = function (headers) {
            var breakpointId = null;
            var correlationId = null;
            if (headers) {
                for (var hk in headers) {
                    if (headers.hasOwnProperty(hk)) {
                        if (hk === "X-MockServer-BreakpointId" || hk === "x-mockserver-breakpointid") {
                            breakpointId = Array.isArray(headers[hk]) ? headers[hk][0] : headers[hk];
                        }
                        if (hk === "WebSocketCorrelationId" || hk === "websocketcorrelationid") {
                            correlationId = Array.isArray(headers[hk]) ? headers[hk][0] : headers[hk];
                        }
                    }
                }
            }
            return { breakpointId: breakpointId, correlationId: correlationId };
        };

        /**
         * Pure function that routes a WebSocket breakpoint message to the
         * appropriate handler and produces the reply envelope.
         *
         * The function mirrors the dispatch that was previously inline inside
         * the WebSocket onmessage handler. Extracting it enables unit testing
         * without a live WebSocket connection.
         *
         * @param {Object} payload  the parsed message: { type: string, value: string (JSON) }
         * @param {Object} handlers {
         *   breakpointRequestHandlers:      { [breakpointId]: function(request) => request|response|null },
         *   breakpointResponseHandlers:     { [breakpointId]: function(request, response) => response|null },
         *   breakpointStreamFrameHandlers:  { [breakpointId]: function(pausedFrame) => {action, ...}|null },
         *   requestHandler:                 function(request) => envelope|null (legacy callback),
         *   requestAndResponseHandler:      function(requestAndResponse) => envelope|null (legacy callback)
         * }
         * @return {Object|null}  { type: string, value: string } reply envelope, or null
         *                        when the message is a client-id registration or has no handler.
         */
        var routeBreakpointMessage = function (payload, handlers) {
            handlers = handlers || {};
            var breakpointRequestHandlers = handlers.breakpointRequestHandlers || {};
            var breakpointResponseHandlers = handlers.breakpointResponseHandlers || {};
            var breakpointStreamFrameHandlers = handlers.breakpointStreamFrameHandlers || {};
            var requestHandler = handlers.requestHandler || null;
            var requestAndResponseHandler = handlers.requestAndResponseHandler || null;

            if (payload.type === "org.mockserver.model.HttpRequest") {
                var request = JSON.parse(payload.value);
                var reqHeaders = extractBreakpointHeaders(request.headers);
                var breakpointId = reqHeaders.breakpointId;
                var correlationId = reqHeaders.correlationId;

                var bpReqHandler = breakpointId ? breakpointRequestHandlers[breakpointId] : null;
                if (bpReqHandler) {
                    try {
                        var bpResult = bpReqHandler(request);
                        if (bpResult === null || bpResult === undefined) {
                            bpResult = request; // auto-continue
                        }
                        if (correlationId) {
                            if (!bpResult.headers) { bpResult.headers = {}; }
                            bpResult.headers["WebSocketCorrelationId"] = Array.isArray(correlationId) ? correlationId : [correlationId];
                        }
                        var bpResultType = bpResult.statusCode !== undefined
                            ? "org.mockserver.model.HttpResponse"
                            : "org.mockserver.model.HttpRequest";
                        return {
                            type: bpResultType,
                            value: JSON.stringify(bpResult)
                        };
                    } catch (e) {
                        // auto-continue on error
                        if (!request.headers) { request.headers = {}; }
                        if (correlationId) {
                            request.headers["WebSocketCorrelationId"] = Array.isArray(correlationId) ? correlationId : [correlationId];
                        }
                        return {
                            type: "org.mockserver.model.HttpRequest",
                            value: JSON.stringify(request)
                        };
                    }
                } else if (requestHandler) {
                    return requestHandler(request);
                }
                // breakpoint message with no matching handler — auto-continue with the
                // original request so the server-side exchange is not left to time out
                // (consistent with the stream-frame phase and the other language clients)
                if (correlationId) {
                    if (!request.headers) { request.headers = {}; }
                    request.headers["WebSocketCorrelationId"] = Array.isArray(correlationId) ? correlationId : [correlationId];
                    return {
                        type: "org.mockserver.model.HttpRequest",
                        value: JSON.stringify(request)
                    };
                }
                return null;
            } else if (payload.type === "org.mockserver.model.HttpRequestAndHttpResponse") {
                var requestAndResponse = JSON.parse(payload.value);
                var respHeaders = extractBreakpointHeaders(
                    requestAndResponse.httpRequest ? requestAndResponse.httpRequest.headers : null
                );
                var bpId2 = respHeaders.breakpointId;
                var corrId2 = respHeaders.correlationId;

                var bpRespHandler = bpId2 ? breakpointResponseHandlers[bpId2] : null;
                if (bpRespHandler) {
                    try {
                        var bpResp = bpRespHandler(requestAndResponse.httpRequest, requestAndResponse.httpResponse);
                        if (bpResp === null || bpResp === undefined) {
                            bpResp = requestAndResponse.httpResponse; // auto-continue
                        }
                        if (corrId2) {
                            if (!bpResp.headers) { bpResp.headers = {}; }
                            bpResp.headers["WebSocketCorrelationId"] = Array.isArray(corrId2) ? corrId2 : [corrId2];
                        }
                        return {
                            type: "org.mockserver.model.HttpResponse",
                            value: JSON.stringify(bpResp)
                        };
                    } catch (e) {
                        // auto-continue on error
                        var origResp = requestAndResponse.httpResponse || {};
                        if (corrId2) {
                            if (!origResp.headers) { origResp.headers = {}; }
                            origResp.headers["WebSocketCorrelationId"] = Array.isArray(corrId2) ? corrId2 : [corrId2];
                        }
                        return {
                            type: "org.mockserver.model.HttpResponse",
                            value: JSON.stringify(origResp)
                        };
                    }
                } else if (requestAndResponseHandler) {
                    return requestAndResponseHandler(requestAndResponse);
                }
                // breakpoint message with no matching handler — auto-continue with the
                // original response (consistent with the other phases and language clients)
                if (corrId2) {
                    var origResp2 = requestAndResponse.httpResponse || {};
                    if (!origResp2.headers) { origResp2.headers = {}; }
                    origResp2.headers["WebSocketCorrelationId"] = Array.isArray(corrId2) ? corrId2 : [corrId2];
                    return {
                        type: "org.mockserver.model.HttpResponse",
                        value: JSON.stringify(origResp2)
                    };
                }
                return null;
            } else if (payload.type === "org.mockserver.serialization.model.PausedStreamFrameDTO") {
                var pausedFrame = JSON.parse(payload.value);
                var sfBpId = pausedFrame.breakpointId;
                var sfHandler = sfBpId ? breakpointStreamFrameHandlers[sfBpId] : null;
                var decision;
                if (sfHandler) {
                    try {
                        decision = sfHandler(pausedFrame);
                        if (decision === null || decision === undefined) {
                            decision = { correlationId: pausedFrame.correlationId, action: "CONTINUE" };
                        } else {
                            decision.correlationId = pausedFrame.correlationId; // ensure echoed
                        }
                    } catch (e) {
                        decision = { correlationId: pausedFrame.correlationId, action: "CONTINUE" };
                    }
                } else {
                    // auto-continue for unknown breakpoint id
                    decision = { correlationId: pausedFrame.correlationId, action: "CONTINUE" };
                }
                return {
                    type: "org.mockserver.serialization.model.StreamFrameDecisionDTO",
                    value: JSON.stringify(decision)
                };
            }
            // WebSocketClientIdDTO or unknown types — not routed
            return null;
        };

        var webSocketClient = function (tls, caCertPath) {
            return function (host, port, contextPath) {
                var deferred = defer();
                downloadCACert(tls, caCertPath, function (ca) {

                    var clientId;
                    var clientIdHandler;
                    var requestHandler;
                    var requestAndResponseHandler;
                    // Per-breakpoint-id handlers for matcher-driven breakpoints
                    var breakpointRequestHandlers = {};
                    var breakpointResponseHandlers = {};
                    var breakpointStreamFrameHandlers = {};
                    var hasConnectedOnce = false;
                    var reconnectAttempts = 0;
                    var webSocketLocation = (tls ? "wss" : "ws") + "://" + host + ":" + port + contextPath + "/_mockserver_callback_websocket";

                    var client = new WebSocketClient({
                        maxReceivedFrameSize: 64 * 1024 * 1024,   // 64MiB
                        maxReceivedMessageSize: 64 * 1024 * 1024, // 64MiB
                        fragmentOutgoingMessages: false,
                        tlsOptions: {
                            ca: ca,
                            port: port
                        }
                    });

                    var scheduleReconnect = function () {
                        reconnectAttempts += 1;
                        if (reconnectAttempts > MAX_RECONNECT_ATTEMPTS) {
                            console.error('Max reconnect attempts reached, giving up');
                            return;
                        }
                        var delayMs = Math.min(Math.pow(2, reconnectAttempts), 8) * 1000;
                        console.warn('WebSocket disconnected, reconnecting (attempt ' + reconnectAttempts + '/' + MAX_RECONNECT_ATTEMPTS + ') in ' + (delayMs / 1000) + 's');
                        setTimeout(function () {
                            client.connect(webSocketLocation, []);
                        }, delayMs);
                    };

                    client.on('connectFailed', function (error) {
                        if (!hasConnectedOnce) {
                            if (error.code && error.code === "ECONNREFUSED") {
                                deferred.reject("Can't connect to MockServer running on host: \"" + host + "\" and port: \"" + port + "\"");
                            } else {
                                // reject with the error message (not JSON.stringify,
                                // which serialises an Error to "{}" since message/stack
                                // are non-enumerable) to preserve diagnostic information
                                // while keeping the string rejection contract
                                deferred.reject(error.message || String(error));
                            }
                        } else {
                            scheduleReconnect();
                        }
                    });

                    client.on('connect', function (connection) {
                        hasConnectedOnce = true;
                        reconnectAttempts = 0;
                        connection.on('error', function (error) {
                            if (error.code && error.code === "ECONNREFUSED") {
                                deferred.reject("Can't connect to MockServer running on host: \"" + host + "\" and port: \"" + port + "\"");
                            } else {
                                // reject with the error message (not JSON.stringify,
                                // which serialises an Error to "{}" since message/stack
                                // are non-enumerable) to preserve diagnostic information
                                // while keeping the string rejection contract
                                deferred.reject(error.message || String(error));
                            }
                        });
                        connection.on('close', function () {
                            scheduleReconnect();
                        });
                        connection.on('message', function (message) {
                            if (message.type === 'utf8') {
                                var payload = JSON.parse(message.utf8Data);

                                // Handle client-id registration directly (not routed)
                                if (payload.type === "org.mockserver.serialization.model.WebSocketClientIdDTO") {
                                    var registration = JSON.parse(payload.value);
                                    if (registration.clientId) {
                                        clientId = registration.clientId;
                                        if (clientIdHandler) {
                                            clientIdHandler(clientId);
                                        }
                                    }
                                    return;
                                }

                                // Route breakpoint / callback messages via the pure function
                                var reply = routeBreakpointMessage(payload, {
                                    breakpointRequestHandlers: breakpointRequestHandlers,
                                    breakpointResponseHandlers: breakpointResponseHandlers,
                                    breakpointStreamFrameHandlers: breakpointStreamFrameHandlers,
                                    requestHandler: requestHandler,
                                    requestAndResponseHandler: requestAndResponseHandler
                                });
                                if (reply) {
                                    connection.sendUTF(JSON.stringify(reply));
                                }
                            } else {
                                console.log('Incorrect message format: ' + JSON.parse(message));
                            }
                        });
                    });

                    client.connect(webSocketLocation, []);

                    deferred.resolve({
                        requestCallback: function requestCallback(callback) {
                            requestHandler = callback;
                        },
                        requestAndResponseCallback: function requestAndResponseCallback(callback) {
                            requestAndResponseHandler = callback;
                        },
                        clientIdCallback: function clientIdCallback(callback) {
                            clientIdHandler = callback;
                            if (clientId) {
                                clientIdHandler(clientId);
                            }
                        },
                        setBreakpointRequestHandler: function (breakpointId, handler) {
                            if (breakpointId && handler) {
                                breakpointRequestHandlers[breakpointId] = handler;
                            }
                        },
                        setBreakpointResponseHandler: function (breakpointId, handler) {
                            if (breakpointId && handler) {
                                breakpointResponseHandlers[breakpointId] = handler;
                            }
                        },
                        setBreakpointStreamFrameHandler: function (breakpointId, handler) {
                            if (breakpointId && handler) {
                                breakpointStreamFrameHandlers[breakpointId] = handler;
                            }
                        },
                        removeBreakpointHandlers: function (breakpointId) {
                            if (breakpointId) {
                                delete breakpointRequestHandlers[breakpointId];
                                delete breakpointResponseHandlers[breakpointId];
                                delete breakpointStreamFrameHandlers[breakpointId];
                            }
                        },
                        clearBreakpointHandlers: function () {
                            breakpointRequestHandlers = {};
                            breakpointResponseHandlers = {};
                            breakpointStreamFrameHandlers = {};
                        }
                    });
                });
                return deferred.promise;
            };
        };

        module.exports = {
            webSocketClient: webSocketClient,
            routeBreakpointMessage: routeBreakpointMessage,
            extractBreakpointHeaders: extractBreakpointHeaders
        };
    }
})();