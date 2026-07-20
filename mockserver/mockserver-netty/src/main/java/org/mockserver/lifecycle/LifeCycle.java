package org.mockserver.lifecycle;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import org.mockserver.configuration.Configuration;
import org.mockserver.socket.NettyTransport;
import org.mockserver.log.MockServerEventLog;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.listeners.MockServerMatcherNotifier;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.stop.Stoppable;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.SERVER_CONFIGURATION;
import static org.mockserver.mock.HttpState.setPort;
import static org.slf4j.event.Level.*;

/**
 * @author jamesdbloom
 */
public abstract class LifeCycle implements Stoppable {

    protected final MockServerLogger mockServerLogger;
    protected final EventLoopGroup bossGroup;
    protected final EventLoopGroup workerGroup;
    // Dedicated event-loop group for the outbound forward/proxy (loopback) HTTP client. It is kept
    // DISJOINT from the server worker group so that a pooled keep-alive channel reused inside a
    // synchronous local object-callback (which runs ON a server worker thread and makes a blocking
    // loopback call back to this server) is never pinned to the very worker thread that is blocked
    // in the callback — which would self-deadlock the event loop. This disjoint group is what makes
    // forwardConnectionPoolEnabled safe to default on. Sized by clientNioEventLoopThreadCount.
    //
    // VERIFICATION GUARD: this self-deadlock only surfaces in the FAILSAFE integration phase (e.g.
    // WebsocketCallbackRegistryIntegrationTest, ExtendedNettyMockingIntegrationTest, the proxy
    // integration tests), NOT in a targeted `-Dtest` unit run — this area has regressed TWICE because
    // per-unit verification skipped that phase. Any change to this group, its wiring into the forward
    // HttpActionHandler (MockServer.java getForwardClientEventLoopGroup()), or HttpClientHandler's
    // pool-return gate MUST be verified by running those failsafe integration classes with pooling on
    // (the default), not just targeted unit tests. The surefire-phase guards
    // ForwardClientEventLoopIsolationTest / ForwardConnectionPoolLoopbackCallbackTest (mockserver-netty)
    // and NettyHttpClientConnectionPoolTest (mockserver-core) lock the invariant, but the failsafe
    // phase remains the backstop. See docs/operations/performance-tuning.md.
    //
    // LAZY: this group is created on the FIRST forward/proxy use (via getForwardClientEventLoopGroup()),
    // NOT in the constructor — a pure-mock deployment that never forwards never allocates it (saving
    // clientNioEventLoopThreadCount selectors/threads at startup). volatile for safe double-checked-lock
    // publication; all creation and shutdown of this field happen under forwardClientGroupLock so a
    // first-forward racing a stop() can never leak an un-terminated group (see the accessor and
    // stopAsync()). Read reflectively by name in the ForwardClientEventLoop* guard tests.
    protected volatile EventLoopGroup forwardClientGroup;
    private final Object forwardClientGroupLock = new Object();
    protected final HttpState httpState;
    private final Configuration configuration;
    protected ServerBootstrap serverServerBootstrap;
    private final List<Future<Channel>> serverChannelFutures = new ArrayList<>();
    private final CompletableFuture<String> stopFuture = new CompletableFuture<>();
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    // Number of data-plane HTTP requests currently being processed (incremented when a request
    // starts processing, decremented when its response has been written). Used by stopAsync() to
    // drain in-flight requests before shutting down the event loops (WS7.2 graceful shutdown).
    private final java.util.concurrent.atomic.AtomicInteger requestsInFlight = new java.util.concurrent.atomic.AtomicInteger(0);
    private final Scheduler scheduler;
    // optional OTel exporters — null unless the corresponding config is enabled
    private final org.mockserver.metrics.OtelMetricsExporter otelMetricsExporter;
    private final org.mockserver.telemetry.GenAiSpanExporter genAiSpanExporter;
    // optional Prometheus remote-write push exporter — null unless enabled
    private final org.mockserver.metrics.PrometheusRemoteWriteExporter prometheusRemoteWriteExporter;

    protected LifeCycle(Configuration configuration) {
        this.configuration = configuration != null ? configuration : configuration();
        this.mockServerLogger = new MockServerLogger(this.configuration, MockServerEventLog.class);
        if (this.configuration.logEventListener() != null) {
            MockServerLogger.setGlobalLogEventListener(this.configuration.logEventListener());
        }
        boolean nativeTransport = this.configuration.useNativeTransport();
        this.bossGroup = NettyTransport.newEventLoopGroup(5, new Scheduler.SchedulerThreadFactory(this.getClass().getSimpleName() + "-bossEventLoop"), nativeTransport);
        this.workerGroup = NettyTransport.newEventLoopGroup(this.configuration.nioEventLoopThreadCount(), new Scheduler.SchedulerThreadFactory(this.getClass().getSimpleName() + "-workerEventLoop"), nativeTransport);
        // NOTE: the outbound forward/proxy (loopback) client's event-loop group (forwardClientGroup) is
        // NOT created here — it is created lazily on first forward via getForwardClientEventLoopGroup(),
        // so a pure-mock server never allocates it. It remains disjoint from workerGroup (see field javadoc).
        this.scheduler = new Scheduler(this.configuration, this.mockServerLogger);
        this.httpState = new HttpState(this.configuration, this.mockServerLogger, this.scheduler);
        this.otelMetricsExporter = org.mockserver.metrics.OtelMetricsExporter.startIfEnabled();
        this.genAiSpanExporter = org.mockserver.telemetry.GenAiSpanExporter.startIfEnabled();
        this.prometheusRemoteWriteExporter = org.mockserver.metrics.PrometheusRemoteWriteExporter.startIfEnabled();
        installSemanticMatchingIfEnabled(this.workerGroup);
        installLlmCompletionServiceIfAvailable(this.workerGroup);
        installSemanticDriftIfEnabled(this.workerGroup);
        installPerformanceDriftThreshold();
        installDriftAlertWebhook();
    }

    /**
     * Install the opt-in semantic prompt matcher only when explicitly enabled and
     * a runtime LLM backend resolves. Off by default — the deterministic matcher
     * is never affected unless both conditions hold. Fail-soft.
     */
    private void installSemanticMatchingIfEnabled(EventLoopGroup eventLoopGroup) {
        if (!org.mockserver.configuration.ConfigurationProperties.llmSemanticMatchingEnabled()) {
            return;
        }
        try {
            java.util.Optional<org.mockserver.llm.client.LlmBackend> backend =
                new org.mockserver.llm.client.LlmBackendResolver().resolveDefault();
            if (!backend.isPresent()) {
                return;
            }
            org.mockserver.httpclient.NettyHttpClient httpClient =
                new org.mockserver.httpclient.NettyHttpClient(configuration, mockServerLogger, eventLoopGroup, null, false);
            org.mockserver.llm.client.LlmCompletionService service =
                new org.mockserver.llm.client.LlmCompletionService(new org.mockserver.llm.client.NettyHttpClientLlmTransport(httpClient));
            org.mockserver.llm.semantic.SemanticMatching.install(
                new org.mockserver.llm.semantic.SemanticPromptMatcher(service, backend.get()));
            org.slf4j.LoggerFactory.getLogger(LifeCycle.class)
                .info("semantic prompt matching enabled (backend: {})", backend.get().provider());
        } catch (Exception e) {
            // fail-soft — semantic matching stays off
            org.slf4j.LoggerFactory.getLogger(LifeCycle.class)
                .warn("failed to enable semantic prompt matching ({}); continuing without it", e.getMessage());
        }
    }

    /**
     * Wire a shared {@link org.mockserver.llm.client.LlmCompletionService} into
     * {@link org.mockserver.mock.HttpState} so the {@code /generateExpectation}
     * endpoint can call the configured LLM backend. When no backend is available
     * the endpoint falls back to template-based stubs. Fail-soft.
     */
    private void installLlmCompletionServiceIfAvailable(EventLoopGroup eventLoopGroup) {
        try {
            java.util.Optional<org.mockserver.llm.client.LlmBackend> backend =
                new org.mockserver.llm.client.LlmBackendResolver().resolveDefault();
            if (!backend.isPresent()) {
                return;
            }
            org.mockserver.httpclient.NettyHttpClient httpClient =
                new org.mockserver.httpclient.NettyHttpClient(configuration, mockServerLogger, eventLoopGroup, null, false);
            org.mockserver.llm.client.LlmCompletionService service =
                new org.mockserver.llm.client.LlmCompletionService(new org.mockserver.llm.client.NettyHttpClientLlmTransport(httpClient));
            httpState.setLlmCompletionService(service, backend.get());
            org.slf4j.LoggerFactory.getLogger(LifeCycle.class)
                .info("LLM completion service installed for stub generation (backend: {})", backend.get().provider());
        } catch (Exception e) {
            // fail-soft — stub generation endpoint will use template fallback
            org.slf4j.LoggerFactory.getLogger(LifeCycle.class)
                .warn("failed to install LLM completion service ({}); stub generation will use template fallback", e.getMessage());
        }
    }

    /**
     * When semantic drift analysis is enabled and a runtime LLM backend resolves,
     * create a {@link org.mockserver.mock.drift.SemanticDriftExtension} and install
     * it on the global {@link org.mockserver.mock.drift.DriftAnalyzer}. Fail-soft.
     */
    private void installSemanticDriftIfEnabled(EventLoopGroup eventLoopGroup) {
        if (!configuration.driftSemanticAnalysisEnabled()) {
            return;
        }
        try {
            java.util.Optional<org.mockserver.llm.client.LlmBackend> backend =
                new org.mockserver.llm.client.LlmBackendResolver().resolveDefault();
            if (!backend.isPresent()) {
                org.slf4j.LoggerFactory.getLogger(LifeCycle.class)
                    .info("semantic drift analysis enabled but no LLM backend available; feature disabled");
                return;
            }
            org.mockserver.httpclient.NettyHttpClient httpClient =
                new org.mockserver.httpclient.NettyHttpClient(configuration, mockServerLogger, eventLoopGroup, null, false);
            org.mockserver.llm.client.LlmCompletionService service =
                new org.mockserver.llm.client.LlmCompletionService(new org.mockserver.llm.client.NettyHttpClientLlmTransport(httpClient));
            org.mockserver.mock.drift.SemanticDriftExtension extension =
                new org.mockserver.mock.drift.SemanticDriftExtension(service, backend.get());
            org.mockserver.mock.drift.DriftAnalyzer.getInstance().setSemanticExtension(extension);
            org.slf4j.LoggerFactory.getLogger(LifeCycle.class)
                .info("semantic drift analysis enabled (backend: {})", backend.get().provider());
        } catch (Exception e) {
            // fail-soft — semantic drift analysis stays off
            org.slf4j.LoggerFactory.getLogger(LifeCycle.class)
                .warn("failed to enable semantic drift analysis ({}); continuing without it", e.getMessage());
        }
    }

    /**
     * Apply the configured p95 response time threshold for performance drift detection.
     */
    private void installPerformanceDriftThreshold() {
        long threshold = configuration.driftResponseTimeThresholdMs();
        if (threshold > 0) {
            org.mockserver.mock.drift.DriftAnalyzer.getInstance().setResponseTimeThresholdMs(threshold);
            org.slf4j.LoggerFactory.getLogger(LifeCycle.class)
                .info("performance drift detection enabled (p95 threshold: {} ms)", threshold);
        }
    }

    /**
     * Apply the configured drift-alert webhook. Off by default; only configures the
     * {@link org.mockserver.mock.drift.DriftAlertNotifier} when enabled and the URL is non-blank.
     * Fail-soft: a blank or malformed severity threshold logs a warning and the webhook stays off.
     */
    private void installDriftAlertWebhook() {
        try {
            if (!configuration.driftAlertWebhookEnabled()) {
                return;
            }
            String url = configuration.driftAlertWebhookUrl();
            if (url == null || url.isBlank()) {
                org.slf4j.LoggerFactory.getLogger(LifeCycle.class)
                    .warn("drift alert webhook enabled but no URL configured; feature disabled");
                return;
            }
            org.mockserver.mock.drift.SemanticSeverity threshold;
            try {
                threshold = org.mockserver.mock.drift.SemanticSeverity.valueOf(
                    configuration.driftAlertSeverityThreshold().trim().toUpperCase());
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(LifeCycle.class)
                    .warn("drift alert webhook severity threshold '{}' is invalid; feature disabled",
                        configuration.driftAlertSeverityThreshold());
                return;
            }
            long cooldownMillis = configuration.driftAlertCooldownMillis();
            org.mockserver.mock.drift.DriftAlertNotifier.getInstance().configure(true, url, threshold, cooldownMillis);
            // INFO logs only scheme+host: the configured webhook URL often embeds a secret token
            // (e.g. a Slack incoming-webhook path), so the full URL must never be written at INFO.
            org.slf4j.LoggerFactory.getLogger(LifeCycle.class)
                .info("drift alert webhook enabled (endpoint: {}, severity>={}, cooldown: {} ms)", redactWebhookUrl(url), threshold, cooldownMillis);
            org.slf4j.LoggerFactory.getLogger(LifeCycle.class)
                .debug("drift alert webhook full url: {}", url);
        } catch (Exception e) {
            // fail-soft — drift alert webhook stays off
            org.slf4j.LoggerFactory.getLogger(LifeCycle.class)
                .warn("failed to enable drift alert webhook ({}); continuing without it", e.getMessage());
        }
    }

    /**
     * Reduces a webhook URL to a token-free {@code scheme://host[:port]} form for INFO logging. The
     * path/query are dropped because they commonly carry a secret (e.g. a Slack incoming-webhook
     * token). Falls back to {@code <redacted url>} if the URL cannot be parsed.
     */
    private static String redactWebhookUrl(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            if (uri.getScheme() != null && uri.getHost() != null) {
                return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
            }
        } catch (Exception ignore) {
            // fall through to a fully-redacted placeholder
        }
        return "<redacted url>";
    }

    /**
     * Mark that a data-plane request has started processing. Must be paired with exactly one
     * {@link #requestProcessingComplete()} call when the response has been written.
     */
    public void requestProcessingStarted() {
        requestsInFlight.incrementAndGet();
    }

    /**
     * Mark that a data-plane request has finished (its response has been written or the connection
     * has otherwise completed). Callers must guard against double-invocation; the counter is never
     * allowed to go negative.
     */
    public void requestProcessingComplete() {
        requestsInFlight.updateAndGet(current -> current > 0 ? current - 1 : 0);
    }

    /**
     * @return the number of data-plane requests currently being processed
     */
    public int getRequestsInFlight() {
        return requestsInFlight.get();
    }

    /**
     * Wait up to {@code stopDrainMillis} for in-flight requests to complete. When the timeout is 0
     * draining is disabled and this returns immediately (pre-7.2 behaviour). If the timeout elapses
     * with requests still in flight a warning is logged and shutdown proceeds anyway.
     */
    private void drainInFlightRequests() {
        long drainMillis = org.mockserver.configuration.ConfigurationProperties.stopDrainMillis();
        if (drainMillis <= 0 || requestsInFlight.get() <= 0) {
            return;
        }
        long deadline = System.currentTimeMillis() + drainMillis;
        while (requestsInFlight.get() > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        int remaining = requestsInFlight.get();
        if (remaining > 0 && mockServerLogger != null && mockServerLogger.isEnabledForInstance(WARN)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(SERVER_CONFIGURATION)
                    .setLogLevel(WARN)
                    .setMessageFormat("graceful shutdown drain timeout of " + drainMillis + "ms elapsed with " + remaining + " request(s) still in flight, proceeding with shutdown")
            );
        }
    }

    public CompletableFuture<String> stopAsync() {
        if (!stopFuture.isDone() && stopping.compareAndSet(false, true)) {
            final String message = "stopped for port" + (getLocalPorts().size() == 1 ? ": " + getLocalPorts().get(0) : "s: " + getLocalPorts());
            if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(SERVER_CONFIGURATION)
                        .setLogLevel(INFO)
                        .setMessageFormat(message)
                );
            }
            new Scheduler.SchedulerThreadFactory("Stop").newThread(() -> {
                List<ChannelFuture> collect = serverChannelFutures
                    .stream()
                    .flatMap(channelFuture -> {
                        try {
                            return Stream.of(channelFuture.get());
                        } catch (Throwable throwable) {
                            // best-effort cleanup during shutdown - log and continue
                            if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(DEBUG)) {
                                mockServerLogger.logEvent(
                                    new LogEntry()
                                        .setType(SERVER_CONFIGURATION)
                                        .setLogLevel(DEBUG)
                                        .setMessageFormat("exception while resolving server channel during shutdown - " + throwable.getMessage())
                                        .setThrowable(throwable)
                                );
                            }
                            return Stream.empty();
                        }
                    })
                    // NOTE: disconnect() really does CLOSE a server channel, closing the listening
                    // socket so no new connections are accepted from here on. This is not obvious
                    // and has been raised twice as a suspected bug, so the reasoning is recorded:
                    //
                    //   AbstractChannelHandlerContext.disconnect(promise) begins
                    //       if (!channel().metadata().hasDisconnect()) { return close(promise); }
                    //   and NioServerSocketChannel's metadata is `new ChannelMetadata(false, 16)`,
                    //   i.e. hasDisconnect == false. So the pipeline translates disconnect into
                    //   close before it ever reaches the channel implementation.
                    //
                    // NioServerSocketChannel.doDisconnect() does throw UnsupportedOperationException,
                    // which is what makes this look broken when that class is read on its own -- but
                    // that method is unreachable through the public disconnect() API used here.
                    // Epoll arrives at the same place by a different route (doDisconnect() delegates
                    // to doClose()), so there is NO platform divergence in observable behaviour.
                    //
                    // Reading a method body proves what it does, not that it runs: the deciding
                    // logic is one layer above, in the pipeline.
                    //
                    // StopAcceptingConnectionsIntegrationTest guards this behaviour, and was
                    // validated by a positive control (replacing this with newSucceededFuture(), so
                    // the socket is never closed, correctly turns it red).
                    .map(ChannelOutboundInvoker::disconnect)
                    .collect(Collectors.toList());
                try {
                    for (ChannelFuture channelFuture : collect) {
                        channelFuture.get();
                    }
                } catch (Throwable throwable) {
                    // best-effort cleanup during shutdown - log and continue
                    if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(DEBUG)) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setType(SERVER_CONFIGURATION)
                                .setLogLevel(DEBUG)
                                .setMessageFormat("exception while disconnecting server channel during shutdown - " + throwable.getMessage())
                                .setThrowable(throwable)
                        );
                    }
                }

                // Server channels are now disconnected so no new requests are accepted; wait for any
                // requests still being processed to complete before tearing down the event loops
                // (WS7.2 graceful shutdown connection drain). Bounded by stopDrainMillis.
                drainInFlightRequests();

                httpState.stop();
                scheduler.shutdown();
                if (otelMetricsExporter != null) {
                    otelMetricsExporter.stop();
                }
                if (genAiSpanExporter != null) {
                    genAiSpanExporter.stop();
                }
                if (prometheusRemoteWriteExporter != null) {
                    prometheusRemoteWriteExporter.stop();
                }
                org.mockserver.llm.semantic.SemanticMatching.clear();

                // The forward-client group is created lazily, so it may never have been created (a
                // pure-mock server). Read it under the same lock the accessor creates it under: this is
                // the synchronization point that prevents a first-forward racing this stop from leaking
                // an un-terminated group — if the accessor wins the lock later it sees stopping==true and
                // shuts its own group down immediately.
                final EventLoopGroup forwardGroupToStop;
                synchronized (forwardClientGroupLock) {
                    forwardGroupToStop = forwardClientGroup;
                }

                // Shut down all event loops to terminate all threads.
                bossGroup.shutdownGracefully(5, 5, MILLISECONDS);
                workerGroup.shutdownGracefully(5, 5, MILLISECONDS);
                if (forwardGroupToStop != null) {
                    forwardGroupToStop.shutdownGracefully(5, 5, MILLISECONDS);
                }

                // Wait until all threads are terminated.
                bossGroup.terminationFuture().syncUninterruptibly();
                workerGroup.terminationFuture().syncUninterruptibly();
                if (forwardGroupToStop != null) {
                    forwardGroupToStop.terminationFuture().syncUninterruptibly();
                }

                stopFuture.complete(message);
            }).start();
        }
        return stopFuture;
    }

    public void stop() {
        try {
            // The wait must never be shorter than the graceful-shutdown drain can legitimately take,
            // otherwise stop() returns to the caller before the server has actually shut down. The
            // drain can block up to stopDrainMillis (WS7.2), so allow that plus a buffer for the
            // event-loop teardown that follows. Falls back to a 30s floor when draining is disabled.
            long stopTimeoutMillis = Math.max(30_000L, org.mockserver.configuration.ConfigurationProperties.stopDrainMillis() + 10_000L);
            stopAsync().get(stopTimeoutMillis, MILLISECONDS);
        } catch (Throwable throwable) {
            if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(DEBUG)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(DEBUG)
                        .setMessageFormat("exception while stopping - " + throwable.getMessage())
                        .setArguments(throwable)
                );
            }
        }
    }

    @Override
    public void close() {
        stop();
    }

    protected EventLoopGroup getEventLoopGroup() {
        return workerGroup;
    }

    /**
     * @return the dedicated event-loop group for the outbound forward/proxy (loopback) HTTP client,
     * kept disjoint from the server worker group so a pooled channel reused inside a synchronous
     * local callback is never pinned to a blocked server worker thread (see field javadoc).
     * <p>
     * Created lazily on the first call (double-checked locking on {@link #forwardClientGroupLock}) so a
     * pure-mock server that never forwards never allocates it. The group is always a NEW group sized by
     * {@code clientNioEventLoopThreadCount} — never the {@code workerGroup} — preserving the disjoint-group
     * self-deadlock invariant documented on the field. If the server is already stopping when the first
     * forward arrives, the group is created but immediately shut down so it can never leak past
     * {@link #stopAsync()} (its {@code isShuttingDown()} then makes {@code NettyHttpClient.sendRequest()}
     * no-op cleanly); all creation and the {@code stopAsync()} teardown read/write the field under the
     * same lock, so a first-forward racing a stop can never leak an un-terminated group.
     */
    protected EventLoopGroup getForwardClientEventLoopGroup() {
        EventLoopGroup group = forwardClientGroup;
        if (group != null) {
            return group;
        }
        synchronized (forwardClientGroupLock) {
            if (forwardClientGroup == null) {
                // Always a NEW, distinct group (never workerGroup) — the disjointness invariant.
                EventLoopGroup created = NettyTransport.newEventLoopGroup(
                    configuration.clientNioEventLoopThreadCount(),
                    new Scheduler.SchedulerThreadFactory(getClass().getSimpleName() + "-forwardClientEventLoop"),
                    configuration.useNativeTransport());
                if (stopping.get()) {
                    // stopAsync()'s teardown pass for this group may already have run; shut this one
                    // down immediately so it is never left un-terminated. No thread has started yet
                    // (no task submitted), so this terminates promptly.
                    created.shutdownGracefully(0, 0, MILLISECONDS);
                }
                forwardClientGroup = created;
            }
            return forwardClientGroup;
        }
    }

    public Scheduler getScheduler() {
        return scheduler;
    }

    public boolean isRunning() {
        return !bossGroup.isShuttingDown() || !workerGroup.isShuttingDown();
    }

    public List<Integer> getLocalPorts() {
        return getBoundPorts(serverChannelFutures);
    }

    /**
     * @deprecated use getLocalPort instead of getPort
     */
    @Deprecated
    public Integer getPort() {
        return getLocalPort();
    }

    public int getLocalPort() {
        return getFirstBoundPort(serverChannelFutures);
    }

    private Integer getFirstBoundPort(List<Future<Channel>> channelFutures) {
        for (Future<Channel> channelOpened : channelFutures) {
            try {
                return ((InetSocketAddress) channelOpened.get(15, SECONDS).localAddress()).getPort();
            } catch (Throwable throwable) {
                if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(WARN)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(WARN)
                            .setMessageFormat("exception while retrieving port from channel future, ignoring port for this channel - " + throwable.getMessage())
                            .setArguments(throwable)
                    );
                }
            }
        }
        return -1;
    }

    private List<Integer> getBoundPorts(List<Future<Channel>> channelFutures) {
        List<Integer> ports = new ArrayList<>();
        for (Future<Channel> channelOpened : channelFutures) {
            try {
                ports.add(((InetSocketAddress) channelOpened.get(3, SECONDS).localAddress()).getPort());
            } catch (Exception e) {
                if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(DEBUG)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(DEBUG)
                            .setMessageFormat("exception while retrieving port from channel future, ignoring port for this channel")
                            .setArguments(e)
                    );
                }
            }
        }
        return ports;
    }

    public List<Integer> bindServerPorts(final List<Integer> requestedPortBindings) {
        return bindPorts(serverServerBootstrap, requestedPortBindings, serverChannelFutures);
    }

    private List<Integer> bindPorts(final ServerBootstrap serverBootstrap, List<Integer> requestedPortBindings, List<Future<Channel>> channelFutures) {
        List<Integer> actualPortBindings = new ArrayList<>();
        final String localBoundIP = configuration.localBoundIP();
        for (final Integer portToBind : requestedPortBindings) {
            try {
                final CompletableFuture<Channel> channelOpened = new CompletableFuture<>();
                channelFutures.add(channelOpened);
                new Scheduler.SchedulerThreadFactory("MockServer thread for port: " + portToBind, false).newThread(() -> {
                    try {
                        InetSocketAddress inetSocketAddress;
                        if (isBlank(localBoundIP)) {
                            inetSocketAddress = new InetSocketAddress(portToBind);
                        } else {
                            inetSocketAddress = new InetSocketAddress(localBoundIP, portToBind);
                        }
                        serverBootstrap
                            .bind(inetSocketAddress)
                            .addListener((ChannelFutureListener) future -> {
                                if (future.isSuccess()) {
                                    channelOpened.complete(future.channel());
                                } else {
                                    channelOpened.completeExceptionally(future.cause());
                                }
                            })
                            .channel().closeFuture().syncUninterruptibly();

                    } catch (Exception e) {
                        channelOpened.completeExceptionally(new RuntimeException("Exception while binding MockServer to port " + portToBind, e));
                    }
                }).start();

                actualPortBindings.add(((InetSocketAddress) channelOpened.get(configuration.maxFutureTimeoutInMillis(), MILLISECONDS).localAddress()).getPort());
            } catch (Exception e) {
                throw new RuntimeException("Exception while binding MockServer to port " + portToBind, e instanceof ExecutionException ? e.getCause() : e);
            }
        }
        return actualPortBindings;
    }

    protected void startedServer(List<Integer> ports) {
        final String message = "started on port" + (ports.size() == 1 ? ": " + ports.get(0) : "s: " + ports);
        setPort(ports);
        if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(INFO)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(SERVER_CONFIGURATION)
                    .setLogLevel(INFO)
                    .setMessageFormat(message)
            );
        }
        logProxySetup(ports);
        startupWarmup(ports);
    }

    /**
     * Pay the one-off "first request" cost in the background so the first real request a caller (or a
     * readiness poll such as Testcontainers) makes is fast.
     * <p>
     * The very first request handled by a freshly started server is a few hundred milliseconds slower
     * than every subsequent one because the request-handling path (Netty HTTP codec, Jackson
     * serialisation, response writers) is only loaded/initialised lazily on first use. We exercise that
     * path once, off the start-up thread, by sending a single plain-HTTP {@code PUT /mockserver/status}
     * to the first bound port over loopback. {@code /status} is a control-plane endpoint that answers
     * without touching the mock-matching or recorded-request paths, so it does NOT create any log
     * events, recorded requests, or verification-visible state.
     * <p>
     * Fail-soft by design: this must never delay port binding (it runs on a daemon thread started after
     * bind), never throw, and never leak a hanging thread (short connect/read timeouts). Any failure is
     * swallowed at TRACE — warm-up is a pure latency optimisation, so a failure to warm up is not an
     * error. Gated by {@code startupWarmup} (default true).
     */
    private void startupWarmup(List<Integer> ports) {
        if (configuration == null || !Boolean.TRUE.equals(configuration.startupWarmup())) {
            return;
        }
        if (ports == null || ports.isEmpty() || ports.get(0) == null || ports.get(0) <= 0) {
            return;
        }
        final int port = ports.get(0);
        Thread warmupThread = new Thread(() -> {
            java.net.HttpURLConnection connection = null;
            try {
                // Plain HTTP against the first bound port — MockServer uses unified protocol detection,
                // so /status answers over plain HTTP on any port (including TLS-capable ports). If that
                // assumption ever fails to hold in a given environment the fail-soft catch below covers
                // it: the only cost is that this instance is not warmed up.
                java.net.URL url = new java.net.URL("http", "127.0.0.1", port, "/mockserver/status");
                connection = (java.net.HttpURLConnection) url.openConnection();
                connection.setRequestMethod("PUT");
                connection.setConnectTimeout(2000);
                connection.setReadTimeout(2000);
                connection.setDoOutput(false);
                connection.connect();
                // Read and discard the response body to exercise the full write path and free the socket.
                try (java.io.InputStream inputStream = connection.getResponseCode() < 400
                    ? connection.getInputStream()
                    : connection.getErrorStream()) {
                    if (inputStream != null) {
                        byte[] buffer = new byte[4096];
                        while (inputStream.read(buffer) != -1) {
                            // drain
                        }
                    }
                }
            } catch (Throwable throwable) {
                if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(TRACE)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setType(SERVER_CONFIGURATION)
                            .setLogLevel(TRACE)
                            .setMessageFormat("exception during start-up warm-up request (ignored):{}")
                            .setThrowable(throwable)
                    );
                }
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }, "MockServer-startup-warmup");
        warmupThread.setDaemon(true);
        warmupThread.start();
    }

    /**
     * When proxySetupLogging is enabled, materialise the active Certificate Authority certificate to a
     * stable file and print an OS-specific copy-paste block describing how to route TLS traffic through
     * MockServer and trust its CA. When proxySetupLogging is disabled (the default — e.g. embedded
     * {@code ClientAndServer} usage) nothing is written or logged. When the public baked-in CA is in
     * effect the block is logged at WARN with a security warning; with a unique/custom CA it is logged at
     * INFO. Fail-soft: any error here never prevents the server from starting.
     */
    private void logProxySetup(List<Integer> ports) {
        try {
            if (configuration == null || !Boolean.TRUE.equals(configuration.proxySetupLogging())) {
                return;
            }
            org.mockserver.socket.tls.KeyAndCertificateFactory keyAndCertificateFactory =
                org.mockserver.socket.tls.KeyAndCertificateFactoryFactory.createKeyAndCertificateFactory(configuration, mockServerLogger);
            String caCertificatePath = keyAndCertificateFactory.writeCertificateAuthorityToDisk();
            org.mockserver.socket.tls.ProxySetupInfo proxySetupInfo =
                new org.mockserver.socket.tls.ProxySetupInfo(caCertificatePath, ports, configuration, System.getProperty("os.name"));
            if (mockServerLogger != null) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(SERVER_CONFIGURATION)
                        .setLogLevel(proxySetupInfo.usingDefaultCa() ? WARN : INFO)
                        .setMessageFormat(proxySetupInfo.copyPasteText())
                );
            }
        } catch (Throwable throwable) {
            if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(DEBUG)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(SERVER_CONFIGURATION)
                        .setLogLevel(DEBUG)
                        .setMessageFormat("exception while preparing proxy setup information:{}")
                        .setThrowable(throwable)
                );
            }
        }
    }

    public LifeCycle registerListener(ExpectationsListener expectationsListener) {
        httpState.getRequestMatchers().registerListener((requestMatchers, cause) -> {
            if (cause == MockServerMatcherNotifier.Cause.API) {
                expectationsListener.updated(requestMatchers.retrieveActiveExpectations(null));
            }
        });
        return this;
    }

}
