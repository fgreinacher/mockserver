package org.mockserver.dashboard;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import io.netty.channel.*;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.mockserver.collections.CircularHashMap;
import org.mockserver.dashboard.model.DashboardLogEntryDTO;
import org.mockserver.dashboard.model.DashboardLogEntryDTOGroup;
import org.mockserver.dashboard.serializers.*;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.MockServerEventLog;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mappers.FullHttpRequestToMockServerHttpRequest;
import org.mockserver.mappers.Http2StreamIds;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.RequestMatchers;
import org.mockserver.mock.listeners.MockServerLogListener;
import org.mockserver.mock.listeners.MockServerMatcherListener;
import org.mockserver.mock.listeners.MockServerMatcherNotifier;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.OpenAPIDefinition;
import org.mockserver.model.RequestDefinition;
import org.mockserver.serialization.HttpRequestSerializer;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.socket.tls.SniHandler;
import org.mockserver.serialization.model.ExpectationDTO;
import org.slf4j.event.Level;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static com.google.common.net.HttpHeaders.HOST;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.exception.ExceptionHandling.connectionClosedException;
import static org.mockserver.exception.ExceptionHandling.isSslOrDecoderFault;
import static org.mockserver.exception.ExceptionHandling.sniDescription;
import static org.mockserver.log.model.LogEntry.LogMessageType.*;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.netty.unification.PortUnificationHandler.isHttp2Enabled;

/**
 * @author jamesdbloom
 */
@ChannelHandler.Sharable
public class DashboardWebSocketHandler extends ChannelInboundHandlerAdapter implements MockServerLogListener, MockServerMatcherListener {

    private static final Predicate<DashboardLogEntryDTO> recordedRequestsPredicate = input
        -> input.getType() == RECEIVED_REQUEST;
    private static final Predicate<DashboardLogEntryDTO> proxiedRequestsPredicate = input
        -> input.getType() == FORWARDED_REQUEST;
    private static final AttributeKey<Boolean> CHANNEL_UPGRADED_FOR_UI_WEB_SOCKET = AttributeKey.valueOf("CHANNEL_UPGRADED_FOR_UI_WEB_SOCKET");
    private static final String UPGRADE_CHANNEL_FOR_UI_WEB_SOCKET_URI = "/_mockserver_ui_websocket";
    private static final int UI_UPDATE_ITEM_LIMIT = 100;
    // Eagerly initialised and safely published via static-final so reads from the off-event-loop
    // scheduler threads see a fully constructed mapper without a data race. The mapper is stateless
    // and thread-safe once configured, so a single shared instance is correct.
    private static final ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper(
        new DashboardLogEntryDTOSerializer(),
        new DashboardLogEntryDTOGroupSerializer(),
        new DescriptionSerializer(),
        new ThrowableSerializer()
    );
    // Instance-scoped because the writer's pretty-printing depends on the per-instance prettyPrint
    // flag; written once on the event loop in registerListeners() before any scheduler task that
    // reads it is submitted, so it is safely published to those tasks.
    private ObjectWriter objectWriter;
    private final boolean prettyPrint;
    private final MockServerLogger mockServerLogger;
    private final boolean sslEnabledUpstream;
    private final HttpState httpState;
    private HttpRequestSerializer httpRequestSerializer;
    private WebSocketServerHandshaker handshaker;
    private Map<ChannelOutboundInvoker, HttpRequest> clientRegistry;
    private RequestMatchers requestMatchers;
    private MockServerEventLog mockServerEventLog;
    private ThreadPoolExecutor scheduler;
    private ScheduledExecutorService throttleExecutorService;
    private Semaphore semaphore;

    public DashboardWebSocketHandler(HttpState httpState, boolean sslEnabledUpstream, boolean prettyPrint) {
        this.httpState = httpState;
        this.mockServerLogger = httpState.getMockServerLogger();
        this.sslEnabledUpstream = sslEnabledUpstream;
        this.prettyPrint = prettyPrint;
    }

    // clientRegistry (a non-thread-safe CircularHashMap) is mutated from channelRead / write-future
    // listeners (event loop) and iterated from updated(...) callbacks that run on scheduler threads
    // (MockServerMatcherNotifier / MockServerEventLog notify via Scheduler.submit). All access is
    // serialised on the map instance below to keep it thread-safe.
    @VisibleForTesting
    public synchronized Map<ChannelOutboundInvoker, HttpRequest> getClientRegistry() {
        if (clientRegistry == null) {
            clientRegistry = new CircularHashMap<>(100);
        }
        return clientRegistry;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        try {
            scheduler = new ThreadPoolExecutor(
                1,
                1,
                0L,
                SECONDS,
                new LinkedBlockingQueue<>(1),
                Executors.defaultThreadFactory(),
                new ThreadPoolExecutor.DiscardOldestPolicy()
            );
        } catch (Throwable throwable) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat("exception creating scheduler " + throwable.getMessage())
                    .setThrowable(throwable)
            );
        }
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        if (this.scheduler != null) {
            scheduler.shutdown();
        }
        if (this.throttleExecutorService != null) {
            throttleExecutorService.shutdownNow();
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        boolean release = true;
        try {
            if (msg instanceof FullHttpRequest && ((FullHttpRequest) msg).uri().equals(UPGRADE_CHANNEL_FOR_UI_WEB_SOCKET_URI)) {
                if (isHttp2Enabled(ctx.channel())) {
                    if (mockServerLogger.isEnabledForInstance(Level.TRACE)) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(Level.TRACE)
                                .setMessageFormat("WebSocket upgrade not supported over HTTP/2 for dashboard connection:{}")
                                .setArguments(ctx.channel().localAddress())
                        );
                    }
                    // This branch fires ONLY on HTTP/2, so the 501 must carry the request's stream
                    // id - otherwise it goes out on a phantom server-initiated stream and the
                    // dashboard hangs instead of being told WebSocket upgrade is unsupported.
                    DefaultFullHttpResponse notImplemented = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_IMPLEMENTED, Unpooled.EMPTY_BUFFER);
                    Http2StreamIds.stampFromNettyRequest(notImplemented, (FullHttpRequest) msg);
                    ctx.channel().writeAndFlush(notImplemented);
                } else if (!webSocketUpgradeAuthenticated(ctx, (FullHttpRequest) msg)) {
                    // control-plane auth is configured and this upgrade did not present valid
                    // credentials (or the principal lacks the required role): the rejection
                    // response has already been written, so do NOT upgrade — otherwise the
                    // dashboard would push all captured traffic to an unauthenticated client.
                } else {
                    upgradeChannel(ctx, (FullHttpRequest) msg);
                    ctx.channel().attr(CHANNEL_UPGRADED_FOR_UI_WEB_SOCKET).set(true);
                }
            } else if (ctx.channel().attr(CHANNEL_UPGRADED_FOR_UI_WEB_SOCKET).get() != null &&
                ctx.channel().attr(CHANNEL_UPGRADED_FOR_UI_WEB_SOCKET).get() &&
                msg instanceof WebSocketFrame) {
                handleWebSocketFrame(ctx, (WebSocketFrame) msg);
            } else {
                release = false;
                ctx.fireChannelRead(msg);
            }
        } finally {
            if (release) {
                ReferenceCountUtil.release(msg);
            }
        }
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
    }

    /**
     * Gate the dashboard UI WebSocket upgrade with the SAME control-plane authentication /
     * authorization as {@code /mockserver/configuration} and the dashboard HTTP surface.
     * <p>
     * When no control-plane authentication handler is configured (the default) this returns
     * {@code true} immediately without touching the request, so the open-dashboard behaviour is
     * unchanged. When one IS configured it maps the raw Netty upgrade request (with any mTLS
     * client certificates from the channel) to a MockServer request and asks the shared core gate
     * for a decision; on a non-ALLOWED outcome it writes a raw {@code 401}/{@code 403} handshake
     * response and closes the connection, returning {@code false} so the caller does not upgrade.
     * The upgrade is a read, so a read-only control-plane role is permitted to view the dashboard.
     */
    private boolean webSocketUpgradeAuthenticated(final ChannelHandlerContext ctx, FullHttpRequest httpRequest) {
        if (httpState.getControlPlaneAuthenticationHandler() == null) {
            // No control-plane authentication configured: preserve the default open dashboard.
            return true;
        }
        HttpRequest mockServerRequest = new FullHttpRequestToMockServerHttpRequest(
            httpState.getConfiguration(),
            mockServerLogger,
            sslEnabledUpstream,
            SniHandler.retrieveClientCertificates(mockServerLogger, ctx),
            ctx.channel().localAddress() instanceof java.net.InetSocketAddress
                ? ((java.net.InetSocketAddress) ctx.channel().localAddress()).getPort()
                : null
        ).mapFullHttpRequestToMockServerRequest(
            httpRequest,
            null,
            ctx.channel().localAddress(),
            ctx.channel().remoteAddress(),
            SniHandler.getALPNProtocol(mockServerLogger, ctx)
        );
        HttpState.ControlPlaneAuthDecision decision = httpState.evaluateControlPlaneAuthentication(mockServerRequest);
        if (decision.isAllowed()) {
            return true;
        }
        HttpResponseStatus status = decision.outcome() == HttpState.ControlPlaneAuthOutcome.FORBIDDEN
            ? HttpResponseStatus.FORBIDDEN
            : HttpResponseStatus.UNAUTHORIZED;
        if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(AUTHENTICATION_FAILED)
                    .setLogLevel(Level.INFO)
                    .setMessageFormat("dashboard UI web socket upgrade rejected with status {} - control plane authentication required")
                    .setArguments(status.code())
            );
        }
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.EMPTY_BUFFER);
        response.headers().set(CONTENT_LENGTH, 0);
        ctx.channel().writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        return false;
    }

    private void upgradeChannel(final ChannelHandlerContext ctx, FullHttpRequest httpRequest) {
        String webSocketURL = (sslEnabledUpstream ? "wss" : "ws") + "://" + httpRequest.headers().get(HOST) + UPGRADE_CHANNEL_FOR_UI_WEB_SOCKET_URI;
        if (mockServerLogger.isEnabledForInstance(Level.TRACE)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.TRACE)
                    .setMessageFormat("upgraded dashboard connection to support web sockets on url{}")
                    .setArguments(webSocketURL)
            );
        }
        handshaker = new WebSocketServerHandshakerFactory(
            webSocketURL,
            null,
            true,
            Integer.MAX_VALUE
        ).newHandshaker(httpRequest);
        if (handshaker == null) {
            WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
        } else {
            handshaker.handshake(
                ctx.channel(),
                httpRequest,
                new DefaultHttpHeaders(),
                ctx.channel().newPromise()
            ).addListener((ChannelFutureListener) future -> {
                Map<ChannelOutboundInvoker, HttpRequest> registry = getClientRegistry();
                synchronized (registry) {
                    registry.put(ctx, request());
                }
            });
        }
        registerListeners();
    }

    @VisibleForTesting
    protected DashboardWebSocketHandler registerListeners() {
        if (objectWriter == null) {
            if (prettyPrint) {
                objectWriter = objectMapper.writerWithDefaultPrettyPrinter();
            } else {
                objectWriter = objectMapper.writer();
            }
        }
        if (httpRequestSerializer == null) {
            httpRequestSerializer = new HttpRequestSerializer(mockServerLogger);
        }
        if (semaphore == null) {
            semaphore = new Semaphore(1);
        }
        if (throttleExecutorService == null) {
            throttleExecutorService = Executors.newScheduledThreadPool(1);
        }
        if (scheduler == null) {
            scheduler = new ThreadPoolExecutor(
                1,
                1,
                0L,
                SECONDS,
                new LinkedBlockingQueue<>(10),
                Executors.defaultThreadFactory(),
                new ThreadPoolExecutor.DiscardOldestPolicy()
            );
        }
        throttleExecutorService.scheduleAtFixedRate(() -> {
            if (semaphore.availablePermits() == 0) {
                semaphore.release(1);
            }
        }, 0, 1, SECONDS);
        if (mockServerEventLog == null) {
            mockServerEventLog = httpState.getMockServerLog();
            mockServerEventLog.registerListener(this);
            requestMatchers = httpState.getRequestMatchers();
            requestMatchers.registerListener(this);
            scheduler.submit(() -> {
                try {
                    MILLISECONDS.sleep(100);
                } catch (InterruptedException ignore) {
                }
                // ensure any exception added during initialisation are caught
                updated(mockServerEventLog);
                updated(requestMatchers, null);
            });
        }
        return this;
    }

    private void handleWebSocketFrame(final ChannelHandlerContext ctx, WebSocketFrame frame) {
        if (frame instanceof CloseWebSocketFrame) {
            handshaker.close(ctx.channel(), (CloseWebSocketFrame) frame.retain()).addListener((ChannelFutureListener) future -> {
                Map<ChannelOutboundInvoker, HttpRequest> registry = getClientRegistry();
                synchronized (registry) {
                    registry.remove(ctx);
                }
            });
        } else if (frame instanceof TextWebSocketFrame) {
            try {
                HttpRequest httpRequest = httpRequestSerializer.deserialize(((TextWebSocketFrame) frame).text());
                Map<ChannelOutboundInvoker, HttpRequest> registry = getClientRegistry();
                synchronized (registry) {
                    registry.put(ctx, httpRequest);
                }
                sendUpdate(ctx, httpRequest);
            } catch (IllegalArgumentException iae) {
                sendMessage(ctx, null, ImmutableMap.of("error", iae.getMessage()), 2);
            }
        } else if (frame instanceof PingWebSocketFrame) {
            ctx.write(new PongWebSocketFrame(frame.content().retain()));
        } else {
            throw new UnsupportedOperationException(frame.getClass().getName() + " frame types not supported");
        }
    }

    private void sendMessage(ChannelOutboundInvoker ctx, RequestDefinition httpRequest, ImmutableMap<String, Object> message, int retryCount) {
        if (semaphore.tryAcquire()) {
            scheduler.submit(() -> {
                try {
                    String text = objectWriter.writeValueAsString(message);
                    ctx.writeAndFlush(new TextWebSocketFrame(text));
                } catch (JsonProcessingException jpe) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.ERROR)
                            .setMessageFormat("exception with serialising UI data " + jpe.getMessage())
                            .setThrowable(jpe)
                    );
                }
            });
        } else if (retryCount >= 0) {
            scheduler.submit(() -> {
                try {
                    TimeUnit.MILLISECONDS.sleep(200);
                } catch (InterruptedException ignore) {
                }
                if (httpRequest != null) {
                    sendUpdate(ctx, httpRequest, retryCount - 1);
                } else {
                    sendMessage(ctx, null, message, retryCount - 1);
                }
            });

        }

    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (connectionClosedException(cause)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat("web socket server caught exception")
                    .setThrowable(cause)
            );
        } else if (isSslOrDecoderFault(cause)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("web socket server caught SSL or decoder fault" + sniDescription(ctx.channel()))
                    .setThrowable(cause)
            );
        }
        ctx.close();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (requestMatchers != null) {
            requestMatchers.unregisterListener(this);
        }
        if (mockServerEventLog != null) {
            mockServerEventLog.unregisterListener(this);
        }
        ctx.fireChannelInactive();
    }

    @Override
    public void updated(MockServerEventLog mockServerLog) {
        for (Map.Entry<ChannelOutboundInvoker, HttpRequest> registryEntry : clientRegistrySnapshot()) {
            sendUpdate(registryEntry.getKey(), registryEntry.getValue());
        }
    }

    @Override
    public void updated(RequestMatchers requestMatchers, MockServerMatcherNotifier.Cause cause) {
        for (Map.Entry<ChannelOutboundInvoker, HttpRequest> registryEntry : clientRegistrySnapshot()) {
            sendUpdate(registryEntry.getKey(), registryEntry.getValue());
        }
    }

    // Snapshot the registry under its lock so the (off-event-loop) updated(...) callbacks iterate a
    // stable copy without holding the lock across the heavyweight sendUpdate calls and without racing
    // the event-loop put/remove mutations.
    private List<Map.Entry<ChannelOutboundInvoker, HttpRequest>> clientRegistrySnapshot() {
        Map<ChannelOutboundInvoker, HttpRequest> registry = getClientRegistry();
        synchronized (registry) {
            return new ArrayList<>(registry.entrySet());
        }
    }

    @VisibleForTesting
    void sendUpdate(ChannelOutboundInvoker ctx, RequestDefinition httpRequest) {
        sendUpdate(ctx, httpRequest, 2);
    }

    private void sendUpdate(ChannelOutboundInvoker ctx, RequestDefinition httpRequest, int retryCount) {
        DescriptionProcessor activeExpectationsDescriptionProcessor = new DescriptionProcessor();
        DescriptionProcessor logMessagesDescriptionProcessor = new DescriptionProcessor();
        DescriptionProcessor recordedRequestsDescriptionProcessor = new DescriptionProcessor();
        DescriptionProcessor proxiedRequestsDescriptionProcessor = new DescriptionProcessor();
        Configuration configuration = httpState.getConfiguration();
        Map<String, String> overrides = configuration.logLevelOverrides();
        Level globalLevel = configuration.logLevel();
        mockServerEventLog
            .retrieveLogEntriesInReverseForUI(
                httpRequest,
                logEntry -> !logEntry.isDeleted()
                    && (logEntry.isAlwaysLog() || overrides == null || overrides.isEmpty()
                    || MockServerLogger.isEnabled(logEntry.getLogLevel(), LogEntry.LogMessageTypeCategory.resolveEffectiveLevel(logEntry.getType(), overrides, globalLevel))),
                logEntry -> new DashboardLogEntryDTO(logEntry, configuration),
                reverseLogEventsStream -> {
                    List<ImmutableMap<String, Object>> activeExpectations = requestMatchers
                        .retrieveRequestMatchers(httpRequest)
                        .stream()
                        .limit(UI_UPDATE_ITEM_LIMIT)
                        .map(requestMatcher -> {
                            JsonNode expectationJsonNode = objectMapper.valueToTree(new ExpectationDTO(requestMatcher.getExpectation()));
                            if (requestMatcher.getExpectation().getHttpRequest() instanceof OpenAPIDefinition) {
                                JsonNode httpRequestJsonNode = expectationJsonNode.get("httpRequest");
                                if (httpRequestJsonNode instanceof ObjectNode) {
                                    ((ObjectNode) httpRequestJsonNode).set("requestMatchers", objectMapper.valueToTree(requestMatcher.getHttpRequests()));
                                }
                            }
                            Description description = activeExpectationsDescriptionProcessor.description(requestMatcher.getExpectation().getHttpRequest(), requestMatcher.getExpectation().getId());
                            return ImmutableMap.of(
                                "key", requestMatcher.getExpectation().getId(),
                                "description", description != null ? description : requestMatcher.getExpectation().getId(),
                                "value", expectationJsonNode
                            );
                        })
                        .collect(Collectors.toList());
                    List<Map<String, Object>> proxiedRequests = new LinkedList<>();
                    List<Map<String, Object>> recordedRequests = new LinkedList<>();
                    List<Object> logMessages = new LinkedList<>();
                    Map<String, DashboardLogEntryDTOGroup> logEntryGroups = new HashMap<>();
                    // The dashboard Traffic/Sessions/Cost panels render each
                    // mock-matched request alongside the response that was
                    // returned. The reverse-chronological stream surfaces the
                    // response (EXPECTATION_RESPONSE / NO_MATCH_RESPONSE)
                    // before its corresponding RECEIVED_REQUEST, so we stash
                    // responses by correlationId and look them up when the
                    // matching request is processed.
                    Map<String, Object> responsesByCorrelationId = new HashMap<>();
                    reverseLogEventsStream
                        .forEach(logEntryDTO -> {
                            if (logEntryDTO != null) {
                                if (logMessages.size() < UI_UPDATE_ITEM_LIMIT) {
                                    DashboardLogEntryDTO dashboardLogEntryDTO = logEntryDTO.setDescription(logMessagesDescriptionProcessor.description(logEntryDTO));
                                    if (isNotBlank(logEntryDTO.getCorrelationId()) && logEntryDTO.getType() != TRACE) {
                                        DashboardLogEntryDTOGroup logEntryGroup = logEntryGroups.get(logEntryDTO.getCorrelationId());
                                        if (logEntryGroup == null) {
                                            logEntryGroup = new DashboardLogEntryDTOGroup(logMessagesDescriptionProcessor);
                                            logEntryGroups.put(logEntryDTO.getCorrelationId(), logEntryGroup);
                                            logMessages.add(logEntryGroup);
                                        }
                                        logEntryGroup.getLogEntryDTOS().add(dashboardLogEntryDTO);
                                    } else {
                                        logMessages.add(dashboardLogEntryDTO);
                                    }
                                }
                                if ((logEntryDTO.getType() == EXPECTATION_RESPONSE || logEntryDTO.getType() == NO_MATCH_RESPONSE)
                                    && isNotBlank(logEntryDTO.getCorrelationId())
                                    && logEntryDTO.getHttpResponse() != null) {
                                    responsesByCorrelationId.putIfAbsent(logEntryDTO.getCorrelationId(), logEntryDTO.getHttpResponse());
                                }
                                if (recordedRequestsPredicate.test(logEntryDTO) && recordedRequests.size() < UI_UPDATE_ITEM_LIMIT) {
                                    for (RequestDefinition request : logEntryDTO.getHttpRequests()) {
                                        if (request != null) {
                                            Map<String, Object> value = new LinkedHashMap<>();
                                            value.put("httpRequest", request);
                                            Object response = isNotBlank(logEntryDTO.getCorrelationId())
                                                ? responsesByCorrelationId.get(logEntryDTO.getCorrelationId())
                                                : null;
                                            if (response != null) {
                                                value.put("httpResponse", response);
                                            }
                                            Map<String, Object> entry = new LinkedHashMap<>();
                                            Description description = recordedRequestsDescriptionProcessor.description(request);
                                            if (description != null) {
                                                entry.put("description", description);
                                            }
                                            entry.put("value", value);
                                            entry.put("key", logEntryDTO.getId() + "_request");
                                            recordedRequests.add(entry);
                                        }
                                    }
                                }
                                if (proxiedRequestsPredicate.test(logEntryDTO) && proxiedRequests.size() < UI_UPDATE_ITEM_LIMIT) {
                                    Map<String, Object> value = new LinkedHashMap<>();
                                    if (logEntryDTO.getHttpRequest() != null) {
                                        value.put("httpRequest", logEntryDTO.getHttpRequest());
                                    }
                                    if (logEntryDTO.getHttpResponse() != null) {
                                        value.put("httpResponse", logEntryDTO.getHttpResponse());
                                    }
                                    Map<String, Object> entry = new LinkedHashMap<>();
                                    Description description = proxiedRequestsDescriptionProcessor.description(logEntryDTO.getHttpRequest());
                                    if (description != null) {
                                        entry.put("description", description);
                                    }
                                    entry.put("value", value);
                                    entry.put("key", logEntryDTO.getId() + "_proxied");
                                    if (!value.isEmpty()) {
                                        proxiedRequests.add(entry);
                                    }
                                }
                            }
                        });
                    sendMessage(ctx, httpRequest, ImmutableMap.of(
                        "logMessages", logMessages,
                        "activeExpectations", activeExpectations,
                        "recordedRequests", recordedRequests,
                        "proxiedRequests", proxiedRequests // reverse
                    ), retryCount);
                }
            );
    }

}
