package org.mockserver.log;

import com.lmax.disruptor.ExceptionHandler;
import com.lmax.disruptor.dsl.Disruptor;
import org.mockserver.collections.CircularConcurrentLinkedDeque;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.log.model.RequestAndExpectationId;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.HttpRequestMatcher;
import org.mockserver.matchers.HttpResponseMatcher;
import org.mockserver.matchers.MatchDifference;
import org.mockserver.matchers.MatchDifferenceFormatter;
import org.mockserver.matchers.MatcherBuilder;
import org.mockserver.metrics.Metrics;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.listeners.MockServerEventLogNotifier;
import org.mockserver.mock.listeners.MockServerLogListener;
import org.mockserver.model.ExpectationId;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.LogEventRequestAndResponse;
import org.mockserver.model.RequestDefinition;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.HttpResponseSerializer;
import org.mockserver.serialization.RequestDefinitionSerializer;
import org.mockserver.uuid.UUIDService;
import org.mockserver.verify.Disposition;
import org.mockserver.verify.Verification;
import org.mockserver.verify.VerificationSequence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.log.model.LogEntry.LogMessageType.*;
import static org.mockserver.log.model.LogEntryMessages.VERIFICATION_REQUESTS_MESSAGE_FORMAT;
import static org.mockserver.log.model.LogEntryMessages.VERIFICATION_REQUEST_SEQUENCES_MESSAGE_FORMAT;
import static org.mockserver.logging.MockServerLogger.writeToSystemOut;
import static org.mockserver.mock.HttpState.getPort;
import static org.mockserver.model.HttpRequest.request;

/**
 * @author jamesdbloom
 */
@SuppressWarnings("FieldMayBeFinal")
public class MockServerEventLog extends MockServerEventLogNotifier {

    private static final Logger logger = LoggerFactory.getLogger(MockServerEventLog.class);
    /**
     * Hard upper bound (60s) on a server-side eventual-verification {@code timeout}. A client-supplied
     * {@link Verification#getTimeout()} / {@link VerificationSequence#getTimeout()} larger than this is
     * silently clamped to this value so a single verify request can never pin server resources (a
     * scheduled deadline task plus a transient log listener) for an unbounded period. 60s comfortably
     * covers realistic async-application settle times (the Java client's own client-side poll defaults
     * are in the single-digit seconds) while bounding worst-case resource hold. The wait itself never
     * holds a Netty I/O thread — completion is delivered asynchronously through the existing result
     * consumer + scheduler, never via a blocking sleep on the request thread.
     */
    static final long MAX_VERIFY_TIMEOUT_MILLIS = 60_000L;
    private static final Predicate<LogEntry> allPredicate = input
        -> true;
    private static final Predicate<LogEntry> notDeletedPredicate = input
        -> !input.isDeleted();
    private static final Predicate<LogEntry> requestLogPredicate = input
        -> !input.isDeleted() && input.getType() == RECEIVED_REQUEST;
    // matches only responses the configured mocks actually produced (a matched expectation
    // response or a forwarded/proxied response), NOT MockServer's own auto-generated no-match
    // responses (NO_MATCH_RESPONSE, e.g. the default 404 for an unmatched request). Used both for
    // "matching expectations only" request retrieval AND for response VERIFICATION
    // (responseVerificationLogPredicate is an alias for that second use) — deliberately narrower
    // than requestResponseLogPredicate, which is shared with /retrieve and must keep NO_MATCH_RESPONSE.
    private static final Predicate<LogEntry> expectationLogPredicate = input
        -> !input.isDeleted() && (
        input.getType() == EXPECTATION_RESPONSE
            || input.getType() == FORWARDED_REQUEST
    );
    // response VERIFICATION must exclude MockServer's own NO_MATCH_RESPONSE auto-404s and count
    // only mock-produced responses — the same filter as expectationLogPredicate; aliased here so
    // the verification call sites read clearly and a future change to one is forced to consider both.
    private static final Predicate<LogEntry> responseVerificationLogPredicate = expectationLogPredicate;
    private static final Predicate<LogEntry> requestResponseLogPredicate = input
        -> !input.isDeleted() && (
        input.getType() == EXPECTATION_RESPONSE
            || input.getType() == NO_MATCH_RESPONSE
            || input.getType() == FORWARDED_REQUEST
    );
    private static final Predicate<LogEntry> recordedExpectationLogPredicate = input
        -> !input.isDeleted() && input.getType() == FORWARDED_REQUEST;
    // Disposition predicates for verify-by-disposition (Verification.withDisposition). A MOCKED
    // request is one that matched an expectation and produced a mocked response (EXPECTATION_RESPONSE);
    // a FORWARDED request is one that was forwarded/proxied to an upstream server (FORWARDED_REQUEST).
    // NO_MATCH_RESPONSE (MockServer's own auto-404 for unmatched requests) is excluded from both.
    private static final Predicate<LogEntry> mockedRequestLogPredicate = input
        -> !input.isDeleted() && input.getType() == EXPECTATION_RESPONSE;
    private static final Predicate<LogEntry> forwardedRequestLogPredicate = input
        -> !input.isDeleted() && input.getType() == FORWARDED_REQUEST;
    // Redaction-aware getter: when mockserver.redactSecretsInLog is enabled it masks sensitive
    // headers / body fields on clones so retrieveRecordedRequests (and the JSON / HAR / cURL /
    // OpenAPI / Postman export formats derived from it) does not leak proxied credentials. When the
    // flag is off (the default) it returns the raw requests byte-for-byte unchanged. Request matching
    // and verification read the un-redacted fields directly elsewhere; this mapper is used only on the
    // request-retrieval / export surface (where the result is displayed/exported, or counted — and
    // redaction never adds/drops entries, so the verification count is unaffected).
    private static final Function<LogEntry, RequestDefinition[]> logEntryToRequest = LogEntry::getRedactedHttpRequests;
    private static final Function<LogEntry, Expectation> logEntryToExpectation = LogEntry::getExpectation;
    // Raw request/response pair — used by the response-aware verification DECISION path, which must
    // match against the original (un-redacted) content so enabling redaction never changes a
    // verification pass/fail result.
    private static final Function<LogEntry, LogEventRequestAndResponse> logEntryToHttpRequestAndHttpResponse =
        logEntry -> new LogEventRequestAndResponse()
            .withHttpRequest((HttpRequest) logEntry.getHttpRequest())
            .withHttpResponse(logEntry.getHttpResponse())
            .withTimestamp(logEntry.getTimestamp());
    // Redacted request/response pair — used only by the retrieveRecordedRequestsAndResponses
    // retrieve/export surface, so secrets are masked in the exported/displayed copy while the
    // verification path above keeps matching raw content.
    private static final Function<LogEntry, LogEventRequestAndResponse> logEntryToRedactedHttpRequestAndHttpResponse =
        logEntry -> new LogEventRequestAndResponse()
            .withHttpRequest((HttpRequest) logEntry.getRedactedHttpRequest())
            .withHttpResponse(logEntry.getRedactedHttpResponse())
            .withTimestamp(logEntry.getTimestamp());
    private static final String[] EXCLUDED_FIELDS = {"id", "disruptor"};
    private final Configuration configuration;
    private MockServerLogger mockServerLogger;
    private CircularConcurrentLinkedDeque<LogEntry> eventLog;
    private MatcherBuilder matcherBuilder;
    private RequestDefinitionSerializer requestDefinitionSerializer;
    private final boolean asynchronousEventProcessing;
    private Disruptor<LogEntry> disruptor;
    // Count of INFO/DEBUG log events silently dropped because the disruptor ring buffer was full.
    // Under sustained load the ring buffer can saturate and tryPublishEvent() fails; previously
    // these drops were invisible (only WARN/ERROR drops were logged), so the saturation cliff was
    // undetectable. This counter (plus a WARN logged once on the first drop and the
    // mock_server_dropped_log_events Prometheus counter) makes the cliff observable.
    private final AtomicLong droppedLogEvents = new AtomicLong(0);
    private final AtomicBoolean droppedLogEventWarned = new AtomicBoolean(false);
    // Header name added to the in-memory (and, if it ran before disk-write, persisted) copy of a
    // request/response body that was truncated by maxLoggedBodyBytes; its value is the original
    // (pre-truncation) body length in bytes.
    private static final String TRUNCATED_BODY_HEADER = "x-mockserver-body-truncated";
    // Optional per-entry hook invoked (off the matching/forwarding path) for each recorded exchange
    // (FORWARDED_REQUEST or EXPECTATION_RESPONSE) log entry, used to persist recorded requests to
    // disk. Null when disk persistence is disabled.
    private Consumer<LogEntry> recordedRequestConsumer;

    public MockServerEventLog(Configuration configuration, MockServerLogger mockServerLogger, Scheduler scheduler, boolean asynchronousEventProcessing) {
        super(scheduler);
        this.configuration = configuration;
        this.mockServerLogger = mockServerLogger;
        this.matcherBuilder = new MatcherBuilder(configuration, mockServerLogger);
        this.requestDefinitionSerializer = new RequestDefinitionSerializer(mockServerLogger);
        this.asynchronousEventProcessing = asynchronousEventProcessing;
        this.eventLog = new CircularConcurrentLinkedDeque<>(
            configuration.maxLogEntries(),
            configuration.maxEventLogSizeInBytes(),
            LogEntry::estimatedHeapSize,
            LogEntry::clear);
        startRingBuffer();
    }

    public void add(LogEntry logEntry) {
        if (isLoadGenerated(logEntry)) {
            // Load-generation self-traffic is kept out of this bounded ring buffer: a running load
            // scenario would otherwise flood it and evict real / LLM traffic that the Traffic, Trace
            // and Optimise views depend on. The run's throughput/latency and SLO samples are recorded
            // client-side by LoadScenarioOrchestrator, so they are unaffected by never being logged here.
            return;
        }
        logEntry.setPort(getPort());
        if (asynchronousEventProcessing) {
            if (!disruptor.getRingBuffer().tryPublishEvent(logEntry)) {
                // ring buffer full: the event is dropped. Make the drop observable — count it,
                // mirror it to the Prometheus mock_server_dropped_log_events counter (no-op when
                // metrics are disabled), and WARN once so the saturation cliff surfaces in the log
                // even for INFO/DEBUG events (which were previously dropped silently).
                droppedLogEvents.incrementAndGet();
                Metrics.incrementDroppedLogEvents();
                if (droppedLogEventWarned.compareAndSet(false, true)) {
                    logger.warn("Log event ring buffer full — dropping log events; increase ringBufferSize or reduce log verbosity to avoid losing events");
                }
                // if ring buffer full only write WARN and ERROR to logger
                if (logEntry.getLogLevel().toInt() >= Level.WARN.toInt()) {
                    logger.warn("Too many log events failed to add log event to ring buffer: " + logEntry);
                }
            }
        } else {
            processLogEntry(logEntry);
        }
    }

    public int size() {
        return eventLog.size();
    }

    /**
     * True when this entry's request(s) carry the in-process load-generation marker set by
     * {@link org.mockserver.mock.action.http.LoadScenarioOrchestrator} via
     * {@link HttpRequest#setLoadGenerated(boolean)}, i.e. the entry describes the server's own
     * load-generation traffic and should be kept out of the event log. The marker is an in-process
     * flag, not a wire header, so it never travels to an upstream target and only suppresses logging
     * on the driver that generated the traffic.
     */
    private static boolean isLoadGenerated(LogEntry logEntry) {
        for (RequestDefinition request : logEntry.getHttpRequests()) {
            if (request instanceof HttpRequest && ((HttpRequest) request).isLoadGenerated()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Number of log events dropped because the disruptor ring buffer was full. A non-zero, growing
     * value indicates the event log cannot keep up with the incoming load — increase
     * {@code ringBufferSize} or reduce log verbosity. Always available (independent of whether
     * Prometheus metrics are enabled); also mirrored to the {@code mock_server_dropped_log_events}
     * Prometheus counter when metrics are enabled.
     */
    public long getDroppedLogEventCount() {
        return droppedLogEvents.get();
    }

    private void startRingBuffer() {
        disruptor = new Disruptor<>(LogEntry::new, configuration.ringBufferSize(), new Scheduler.SchedulerThreadFactory("EventLog"));

        final ExceptionHandler<LogEntry> errorHandler = new ExceptionHandler<LogEntry>() {
            @Override
            public void handleEventException(Throwable ex, long sequence, LogEntry logEntry) {
                logger.error("exception handling log entry in log ring buffer, for log entry: " + logEntry, ex);
            }

            @Override
            public void handleOnStartException(Throwable ex) {
                logger.error("exception starting log ring buffer", ex);
            }

            @Override
            public void handleOnShutdownException(Throwable ex) {
                logger.error("exception during shutdown of log ring buffer", ex);
            }
        };
        disruptor.setDefaultExceptionHandler(errorHandler);

        disruptor.handleEventsWith((logEntry, sequence, endOfBatch) -> {
            if (logEntry.getType() != RUNNABLE) {
                processLogEntry(logEntry);
            } else {
                logEntry.getConsumer().run();
                logEntry.clear();
            }
        });

        disruptor.start();
    }

    /**
     * Register a hook invoked once per recorded exchange (FORWARDED_REQUEST or EXPECTATION_RESPONSE)
     * log entry, off the request-matching / forwarding hot path, so a recorded proxied or mocked
     * exchange can be persisted to disk without coupling file I/O into this class. The hook receives
     * the entry with FULL (un-truncated) bodies because {@link #processLogEntry} invokes it BEFORE
     * applying {@code maxLoggedBodyBytes} truncation.
     */
    public void setRecordedRequestConsumer(Consumer<LogEntry> recordedRequestConsumer) {
        this.recordedRequestConsumer = recordedRequestConsumer;
    }

    /**
     * Re-inject a recorded request/response pair loaded from a persisted NDJSON archive back into the
     * live event log so it is retrievable exactly like an in-memory recording (via
     * {@code retrieveRecordedRequests} / {@code retrieveRequestResponses} and the derived export
     * formats). The entry is logged as a FORWARDED_REQUEST and marked
     * {@link LogEntry#setSkipRecordedRequestPersistence(boolean)} so re-loading an archive is
     * idempotent and never appends the same exchanges back to the (possibly same) file.
     */
    public void importRecordedRequestResponse(HttpRequest httpRequest, HttpResponse httpResponse) {
        if (httpRequest == null) {
            return;
        }
        add(new LogEntry()
            .setType(FORWARDED_REQUEST)
            .setLogLevel(Level.INFO)
            .setHttpRequest(httpRequest)
            .setHttpResponse(httpResponse)
            .setExpectation(httpRequest, httpResponse)
            .setSkipRecordedRequestPersistence(true)
            .setMessageFormat("re-imported recorded request:{}and response:{}from persisted archive")
            .setArguments(httpRequest, httpResponse));
    }

    private void processLogEntry(LogEntry logEntry) {
        logEntry = logEntry.cloneAndClear();
        // Disk capture runs FIRST so persisted recorded requests keep full fidelity even when
        // maxLoggedBodyBytes truncates the in-memory copy below. (Recommended combo: disk-capture ON
        // with maxLoggedBodyBytes=0, so memory is bounded by the byte budget and disk is complete.)
        // Both mocked (EXPECTATION_RESPONSE) and forwarded/proxied (FORWARDED_REQUEST) exchanges are
        // captured, so the archive is a complete record of served traffic, not proxy traffic only.
        // Entries injected by the re-import path carry skipRecordedRequestPersistence=true so reloading
        // an archive never appends the same exchanges back to the (possibly same) file.
        if (recordedRequestConsumer != null
            && !logEntry.isSkipRecordedRequestPersistence()
            && (logEntry.getType() == FORWARDED_REQUEST || logEntry.getType() == EXPECTATION_RESPONSE)) {
            recordedRequestConsumer.accept(logEntry);
        }
        // Secondary memory valve: cap the body bytes retained per entry. Builds NEW copies (never
        // mutates the shared live request/response) because LogEntry.clone() copies them by reference.
        if (configuration.maxLoggedBodyBytes() > 0) {
            truncateBodiesForLog(logEntry);
        }
        // add() weighs the (possibly truncated) entry via LogEntry::estimatedHeapSize for the byte
        // budget — truncation has already run, and the estimate is computed lazily inside add().
        eventLog.add(logEntry);
        notifyListeners(this, false);
        writeToSystemOut(logger, logEntry, configuration);
    }

    /**
     * Replace the request and/or response on the (already-cloned) log entry with body-truncated COPIES
     * when their raw body exceeds {@code maxLoggedBodyBytes}. The original request/response objects are
     * shared by reference with the live proxied exchange (LogEntry.clone() copies them by reference), so
     * we must build fresh clones — {@link HttpRequest#clone()} / {@link HttpResponse#clone()} also clone
     * the headers map, so adding the truncation marker header never mutates the live objects. The header
     * value is the original body length so a reader can tell the body was clipped.
     */
    private void truncateBodiesForLog(LogEntry logEntry) {
        int maxLoggedBodyBytes = configuration.maxLoggedBodyBytes();
        RequestDefinition requestDefinition = logEntry.getHttpRequest();
        if (requestDefinition instanceof HttpRequest) {
            HttpRequest httpRequest = (HttpRequest) requestDefinition;
            byte[] body = httpRequest.getBodyAsRawBytes();
            if (body != null && body.length > maxLoggedBodyBytes) {
                logEntry.setHttpRequest(httpRequest
                    .clone()
                    .withBody(Arrays.copyOf(body, maxLoggedBodyBytes))
                    .withHeader(TRUNCATED_BODY_HEADER, String.valueOf(body.length)));
            }
        }
        HttpResponse httpResponse = logEntry.getHttpResponse();
        if (httpResponse != null) {
            byte[] body = httpResponse.getBodyAsRawBytes();
            if (body != null && body.length > maxLoggedBodyBytes) {
                logEntry.setHttpResponse(httpResponse
                    .clone()
                    .withBody(Arrays.copyOf(body, maxLoggedBodyBytes))
                    .withHeader(TRUNCATED_BODY_HEADER, String.valueOf(body.length)));
            }
        }
    }

    private void drainDisruptor() {
        if (asynchronousEventProcessing) {
            CountDownLatch latch = new CountDownLatch(1);
            disruptor.publishEvent(new LogEntry()
                .setType(RUNNABLE)
                .setConsumer(latch::countDown)
            );
            try {
                if (!latch.await(2, SECONDS)) {
                    logger.warn("disruptor drain timed out after 2 seconds before verification, results may be incomplete");
                }
            } catch (InterruptedException ignore) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void stop() {
        try {
            notifyListeners(this, true);
            // cancel any pending coalesced (debounced) listener notification so the scheduled
            // task does not outlive the log — the synchronous notifyListeners above already
            // flushed the final state to every listener.
            stopNotifications();
            eventLog.clear();
            disruptor.shutdown(2, SECONDS);
        } catch (Throwable throwable) {
            if (!(throwable instanceof com.lmax.disruptor.TimeoutException)) {
                if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                    writeToSystemOut(logger, new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("exception while shutting down log ring buffer")
                        .setThrowable(throwable)
                    );
                }
            }
        }
    }

    public void reset() {
        CompletableFuture<String> future = new CompletableFuture<>();
        disruptor.publishEvent(new LogEntry()
            .setType(RUNNABLE)
            .setConsumer(() -> {
                eventLog.clear();
                future.complete("done");
                notifyListeners(this, false);
            })
        );
        try {
            future.get(2, SECONDS);
        } catch (ExecutionException | InterruptedException | TimeoutException ignore) {
        }
    }

    public void clear(RequestDefinition requestDefinition) {
        CompletableFuture<String> future = new CompletableFuture<>();
        final boolean markAsDeletedOnly = mockServerLogger.isEnabledForInstance(Level.INFO);
        disruptor.publishEvent(new LogEntry()
            .setType(RUNNABLE)
            .setConsumer(() -> {
                String logCorrelationId = UUIDService.getUUID();
                // A null filter means "clear everything". The previous code built a fresh empty
                // request().withLogCorrelationId(uuid) matcher for this case, but the unique
                // correlation id made it miss the matcher LRU cache on every clear, forcing an
                // uncached matcher rebuild — and an empty matcher matches every entry that carries
                // at least one request. So short-circuit the whole scan rather than building and
                // running a matcher. Note getHttpRequests() returns a length-0 array (never null)
                // for request-less entries (SERVER_CONFIGURATION, TRACE/WARN/EXCEPTION/CLEARED logged
                // without a request); the old per-request loop never set matches for those, so they
                // SURVIVED clear(null). The `length > 0` guard reproduces that exactly while keeping
                // the perf win. When a real filter is supplied the matcher is built once (cached by
                // the filter) and matched fail-fast (single-arg matches => context == null => no
                // MatchDifference allocation) exactly as before.
                final boolean clearEverything = requestDefinition == null;
                HttpRequestMatcher requestMatcher = clearEverything ? null : matcherBuilder.transformsToMatcher(requestDefinition);
                // Only the single Disruptor handler thread mutates eventLog (add via processLogEntry,
                // removeItem/eviction here), and this consumer runs on that same thread, so there is no
                // concurrent writer to guard against — iterate the deque directly rather than copying up
                // to maxLogEntries entries into a LinkedList first. eventLog's ConcurrentLinkedDeque
                // iterator is weakly consistent and tolerates the removeItem (super.remove) calls below.
                for (LogEntry logEntry : eventLog) {
                    if (markAsDeletedOnly && logEntry.isDeleted()) {
                        // already tombstoned by an earlier clear — skip the expensive matcher so
                        // repeated clear cycles do not re-match the whole accumulated log (#2359)
                        continue;
                    }
                    RequestDefinition[] requests = logEntry.getHttpRequests();
                    boolean matches = false;
                    if (clearEverything) {
                        matches = requests.length > 0;
                    } else if (requests != null) {
                        for (RequestDefinition request : requests) {
                            if (requestMatcher.matches(request.cloneWithLogCorrelationId())) {
                                matches = true;
                            }
                        }
                    } else {
                        matches = true;
                    }
                    if (matches) {
                        if (markAsDeletedOnly) {
                            logEntry.setDeleted(true);
                        } else {
                            eventLog.removeItem(logEntry);
                        }
                    }
                }
                if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setType(CLEARED)
                            .setLogLevel(Level.INFO)
                            .setCorrelationId(logCorrelationId)
                            .setHttpRequest(requestDefinition)
                            .setMessageFormat("cleared logs that match:{}")
                            .setArguments((requestDefinition == null ? "{}" : requestDefinition))
                    );
                }
                future.complete("done");
                notifyListeners(this, false);
            })
        );
        try {
            future.get(2, SECONDS);
        } catch (ExecutionException | InterruptedException | TimeoutException ignore) {
        }
    }

    public void retrieveMessageLogEntries(RequestDefinition requestDefinition, Consumer<List<LogEntry>> listConsumer) {
        retrieveLogEntries(
            requestDefinition,
            notDeletedPredicate,
            (Stream<LogEntry> logEventStream) -> listConsumer.accept(logEventStream.filter(Objects::nonNull).collect(Collectors.toList()))
        );
    }

    public void retrieveMessageLogEntriesIncludingDeleted(RequestDefinition requestDefinition, Consumer<List<LogEntry>> listConsumer) {
        retrieveLogEntries(
            requestDefinition,
            allPredicate,
            (Stream<LogEntry> logEventStream) -> listConsumer.accept(logEventStream.filter(Objects::nonNull).collect(Collectors.toList()))
        );
    }

    public void retrieveRequestLogEntries(RequestDefinition requestDefinition, Consumer<List<LogEntry>> listConsumer) {
        retrieveLogEntries(
            requestDefinition,
            requestLogPredicate,
            (Stream<LogEntry> logEventStream) -> listConsumer.accept(logEventStream.filter(Objects::nonNull).collect(Collectors.toList()))
        );
    }

    public void retrieveRequests(Verification verification, String logCorrelationId, Consumer<List<RequestDefinition>> listConsumer) {
        if (verification.getExpectationId() != null) {
            retrieveLogEntries(
                Collections.singletonList(verification.getExpectationId().getId()),
                expectationLogPredicate,
                logEntryToRequest,
                logEventStream -> listConsumer.accept(
                    logEventStream
                        .filter(Objects::nonNull)
                        .flatMap(Arrays::stream)
                        .collect(Collectors.toList())
                )
            );
        } else {
            // When a disposition filter is set, count requests by how they were handled
            // (FORWARDED_REQUEST or EXPECTATION_RESPONSE) rather than all RECEIVED_REQUEST entries.
            Predicate<LogEntry> typePredicate = requestLogPredicate;
            if (verification.getDisposition() != null) {
                typePredicate = verification.getDisposition() == Disposition.FORWARDED
                    ? forwardedRequestLogPredicate
                    : mockedRequestLogPredicate;
            }
            retrieveLogEntries(
                verification.getHttpRequest().withLogCorrelationId(logCorrelationId),
                typePredicate,
                logEntryToRequest,
                logEventStream -> listConsumer.accept(
                    logEventStream
                        .filter(Objects::nonNull)
                        .flatMap(Arrays::stream)
                        .collect(Collectors.toList())
                )
            );
        }
    }

    public void retrieveAllRequests(boolean matchingExpectationsOnly, Consumer<List<RequestDefinition>> listConsumer) {
        if (matchingExpectationsOnly) {
            retrieveLogEntries(
                (List<String>) null,
                expectationLogPredicate,
                logEntryToRequest,
                logEventStream -> listConsumer.accept(
                    logEventStream
                        .filter(Objects::nonNull)
                        .flatMap(Arrays::stream)
                        .collect(Collectors.toList())
                )
            );
        } else {
            retrieveLogEntries(
                (RequestDefinition) null,
                requestLogPredicate,
                logEntryToRequest,
                logEventStream -> listConsumer.accept(
                    logEventStream
                        .filter(Objects::nonNull)
                        .flatMap(Arrays::stream)
                        .collect(Collectors.toList())
                )
            );
        }
    }

    public void retrieveAllRequests(List<String> expectationIds, Consumer<List<RequestAndExpectationId>> listConsumer) {
        retrieveLogEntries(
            expectationIds,
            expectationLogPredicate,
            logEntry -> new RequestAndExpectationId(logEntry.getHttpRequest(), logEntry.getExpectationId()),
            logEventStream -> listConsumer.accept(
                logEventStream
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList())
            )
        );
    }

    public void retrieveRequests(RequestDefinition requestDefinition, Consumer<List<RequestDefinition>> listConsumer) {
        retrieveLogEntries(
            requestDefinition,
            requestLogPredicate,
            logEntryToRequest,
            logEventStream -> listConsumer.accept(
                logEventStream
                    .filter(Objects::nonNull)
                    .flatMap(Arrays::stream)
                    .collect(Collectors.toList())
            )
        );
    }

    public void retrieveRequests(ExpectationId expectationId, Consumer<List<RequestDefinition>> listConsumer) {
        retrieveLogEntries(
            expectationId != null ? Collections.singletonList(expectationId.getId()) : Collections.emptyList(),
            expectationLogPredicate,
            logEntryToRequest,
            logEventStream -> listConsumer.accept(
                logEventStream
                    .filter(Objects::nonNull)
                    .flatMap(Arrays::stream)
                    .collect(Collectors.toList())
            )
        );
    }

    public void retrieveRequests(List<String> expectationIds, Consumer<List<RequestDefinition>> listConsumer) {
        retrieveLogEntries(
            expectationIds,
            expectationLogPredicate,
            logEntryToRequest,
            logEventStream -> listConsumer.accept(
                logEventStream
                    .filter(Objects::nonNull)
                    .flatMap(Arrays::stream)
                    .collect(Collectors.toList())
            )
        );
    }

    public void retrieveRequestResponseMessageLogEntries(RequestDefinition requestDefinition, Consumer<List<LogEntry>> listConsumer) {
        retrieveLogEntries(
            requestDefinition,
            requestResponseLogPredicate,
            (Stream<LogEntry> logEventStream) -> listConsumer.accept(logEventStream.filter(Objects::nonNull).collect(Collectors.toList()))
        );
    }

    public void retrieveRequestResponses(RequestDefinition requestDefinition, Consumer<List<LogEventRequestAndResponse>> listConsumer) {
        // Public retrieve/export surface (retrieveRecordedRequestsAndResponses) — redact when enabled.
        retrieveRequestResponses(requestDefinition, requestResponseLogPredicate, logEntryToRedactedHttpRequestAndHttpResponse, listConsumer);
    }

    private void retrieveRequestResponses(RequestDefinition requestDefinition, Predicate<LogEntry> logEntryPredicate, Consumer<List<LogEventRequestAndResponse>> listConsumer) {
        // Internal verification-decision callers pass the raw mapper so response matching always sees
        // the original, un-redacted content (redaction must not change a verification pass/fail result).
        retrieveRequestResponses(requestDefinition, logEntryPredicate, logEntryToHttpRequestAndHttpResponse, listConsumer);
    }

    private void retrieveRequestResponses(RequestDefinition requestDefinition, Predicate<LogEntry> logEntryPredicate, Function<LogEntry, LogEventRequestAndResponse> logEntryMapper, Consumer<List<LogEventRequestAndResponse>> listConsumer) {
        retrieveLogEntries(
            requestDefinition,
            logEntryPredicate,
            logEntryMapper,
            logEventStream -> listConsumer.accept(logEventStream.filter(Objects::nonNull).collect(Collectors.toList()))
        );
    }

    public void retrieveRecordedExpectationLogEntries(RequestDefinition requestDefinition, Consumer<List<LogEntry>> listConsumer) {
        retrieveLogEntries(
            requestDefinition,
            recordedExpectationLogPredicate,
            (Stream<LogEntry> logEventStream) -> listConsumer.accept(logEventStream.filter(Objects::nonNull).collect(Collectors.toList()))
        );
    }

    public void retrieveRecordedExpectations(RequestDefinition requestDefinition, Consumer<List<Expectation>> listConsumer) {
        retrieveLogEntries(
            requestDefinition,
            recordedExpectationLogPredicate,
            logEntryToExpectation,
            logEventStream -> listConsumer.accept(logEventStream.filter(Objects::nonNull).collect(Collectors.toList()))
        );
    }

    private void retrieveLogEntries(RequestDefinition requestDefinition, Predicate<LogEntry> logEntryPredicate, Consumer<Stream<LogEntry>> consumer) {
        disruptor.publishEvent(new LogEntry()
            .setType(RUNNABLE)
            .setConsumer(() -> {
                // build the matcher (which can throw, e.g. for an invalid OpenAPI/schema filter)
                // BEFORE invoking the consumer so any failure routes an empty stream to the consumer
                // rather than being swallowed by the disruptor exception handler — otherwise a verify
                // CompletableFuture waiting on the consumer would never complete and the caller hangs
                Stream<LogEntry> logEntryStream;
                try {
                    HttpRequestMatcher httpRequestMatcher = matcherBuilder.transformsToMatcher(requestDefinition);
                    logEntryStream = this.eventLog
                        .stream()
                        // cheap type/not-deleted predicate first so the expensive request matcher
                        // (which clones the request and runs full matching) only runs for entries
                        // that can actually be returned, not for deleted tombstones or wrong-type
                        // entries — keeps /retrieve cost low as the log fills (#2359)
                        .filter(logEntryPredicate)
                        .filter(logItem -> logItem.matches(httpRequestMatcher));
                } catch (Throwable throwable) {
                    logger.error("exception building request matcher while retrieving log entries", throwable);
                    logEntryStream = Stream.empty();
                }
                consumer.accept(logEntryStream);
            })
        );
    }

    private <T> void retrieveLogEntries(RequestDefinition requestDefinition, Predicate<LogEntry> logEntryPredicate, Function<LogEntry, T> logEntryMapper, Consumer<Stream<T>> consumer) {
        disruptor.publishEvent(new LogEntry()
            .setType(RUNNABLE)
            .setConsumer(() -> {
                // build the matcher before invoking the consumer so a build failure routes an empty
                // stream rather than being swallowed and hanging a waiting verify future — see above
                Stream<T> resultStream;
                try {
                    RequestDefinition requestDefinitionMatcher = requestDefinition != null ? requestDefinition : request().withLogCorrelationId(UUIDService.getUUID());
                    HttpRequestMatcher httpRequestMatcher = matcherBuilder.transformsToMatcher(requestDefinitionMatcher);
                    resultStream = this.eventLog
                        .stream()
                        // cheap predicate before the expensive request matcher — see #2359
                        .filter(logEntryPredicate)
                        .filter(logItem -> logItem.matches(httpRequestMatcher))
                        .map(logEntryMapper);
                } catch (Throwable throwable) {
                    logger.error("exception building request matcher while retrieving log entries", throwable);
                    resultStream = Stream.empty();
                }
                consumer.accept(resultStream);
            })
        );
    }

    @SuppressWarnings("SameParameterValue")
    private <T> void retrieveLogEntries(List<String> expectationIds, Predicate<LogEntry> logEntryPredicate, Function<LogEntry, T> logEntryMapper, Consumer<Stream<T>> consumer) {
        disruptor.publishEvent(new LogEntry()
            .setType(RUNNABLE)
            .setConsumer(() -> consumer.accept(this.eventLog
                .stream()
                .filter(logEntryPredicate)
                .filter(logItem -> expectationIds == null || logItem.matchesAnyExpectationId(expectationIds))
                .map(logEntryMapper)
            ))
        );
    }

    public void retrieveLogEntriesByCorrelationId(String correlationId, Consumer<List<LogEntry>> listConsumer) {
        disruptor.publishEvent(new LogEntry()
            .setType(RUNNABLE)
            .setConsumer(() -> listConsumer.accept(this.eventLog
                .stream()
                .filter(notDeletedPredicate)
                .filter(logItem -> correlationId.equals(logItem.getCorrelationId()))
                .collect(Collectors.toList())
            ))
        );
    }

    public void retrieveAlmostMatchedEntries(Consumer<List<LogEntry>> listConsumer) {
        disruptor.publishEvent(new LogEntry()
            .setType(RUNNABLE)
            .setConsumer(() -> listConsumer.accept(this.eventLog
                .stream()
                .filter(notDeletedPredicate)
                .filter(logItem -> logItem.getType() == EXPECTATION_NOT_MATCHED)
                .filter(logItem -> {
                    String msg = logItem.getMessageFormat();
                    return msg != null && msg.startsWith("closest expectation:");
                })
                .collect(Collectors.toList())
            ))
        );
    }

    /**
     * Retrieves the most recent NO_MATCH_RESPONSE log entries (requests that hit the server
     * and matched no expectation). Results are ordered most-recent-first and limited.
     *
     * @param limit       maximum number of entries to return (capped at 100)
     * @param listConsumer callback receiving the list of matching log entries
     */
    public void retrieveUnmatchedRequests(int limit, Consumer<List<LogEntry>> listConsumer) {
        drainDisruptor();
        final int effectiveLimit = Math.max(1, Math.min(limit, 100));
        disruptor.publishEvent(new LogEntry()
            .setType(RUNNABLE)
            .setConsumer(() -> {
                List<LogEntry> entries = StreamSupport
                    .stream(Spliterators.spliteratorUnknownSize(this.eventLog.descendingIterator(), 0), false)
                    .filter(notDeletedPredicate)
                    .filter(logItem -> logItem.getType() == NO_MATCH_RESPONSE)
                    .limit(effectiveLimit)
                    .collect(Collectors.toList());
                listConsumer.accept(entries);
            })
        );
    }

    public <T> void retrieveLogEntriesInReverseForUI(RequestDefinition requestDefinition, Predicate<LogEntry> logEntryPredicate, Function<LogEntry, T> logEntryMapper, Consumer<Stream<T>> consumer) {
        disruptor.publishEvent(new LogEntry()
            .setType(RUNNABLE)
            .setConsumer(() -> {
                HttpRequestMatcher httpRequestMatcher = matcherBuilder.transformsToMatcher(requestDefinition);
                consumer.accept(
                    StreamSupport
                        .stream(Spliterators.spliteratorUnknownSize(this.eventLog.descendingIterator(), 0), false)
                        // cheap predicate before the expensive request matcher — see #2359
                        .filter(logEntryPredicate)
                        .filter(logItem -> logItem.matches(httpRequestMatcher))
                        .map(logEntryMapper)
                );
            })
        );
    }

    public Future<String> verify(Verification verification) {
        CompletableFuture<String> result = new CompletableFuture<>();
        verify(verification, result::complete);
        return result;
    }

    public void verify(Verification verification, Consumer<String> resultConsumer) {
        verify(verification, 0, resultConsumer);
    }

    /**
     * Evaluate a request-count verification, adding {@code additionalRemoteMatchCount} to this
     * node's local match count before comparing against the {@link org.mockserver.verify.VerificationTimes}.
     * <p>
     * Used by the T1.9 cluster verify fan-in: {@code HttpState} sums the match count reported by
     * every cluster peer's LOCAL log and passes the total remote count here, so a count-based
     * verification behind a load balancer is evaluated against the fleet-wide total rather than
     * only the traffic that reached this node. {@code additionalRemoteMatchCount} is {@code 0} on
     * the ordinary single-node / non-fan-in path (identical behaviour to before). Only applies to
     * request verification; response-aware verification always receives {@code 0}.
     */
    public void verify(Verification verification, int additionalRemoteMatchCount, Consumer<String> resultConsumer) {
        drainDisruptor();
        final String logCorrelationId = UUIDService.getUUID();
        if (verification != null) {
            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(VERIFICATION)
                        .setLogLevel(Level.INFO)
                        .setCorrelationId(logCorrelationId)
                        .setHttpRequest(verification.getHttpRequest())
                        .setMessageFormat(VERIFICATION_REQUESTS_MESSAGE_FORMAT)
                        .setArguments(verification)
                );
            }
            // A single evaluation of this verification against the current event log; it always calls
            // the supplied consumer exactly once with "" (pass) or a non-empty failure message. The
            // logResult flag controls whether this evaluation writes its VERIFICATION_PASSED/FAILED
            // outcome to the event log — suppressed for intermediate eventual-wait re-evaluations so a
            // failing-and-waiting verify does not flood the log with one VERIFICATION_FAILED per retry.
            final SingleVerificationEvaluation singleEvaluation = (logResult, consumer) -> {
                if (verification.getHttpResponse() != null) {
                    // response-aware verification: count recorded request-response pairs
                    // (cluster fan-in of response-aware verify is a deferred boundary — always local)
                    verifyResponse(verification, logCorrelationId, logResult, consumer);
                } else {
                    // original request-only verification (plus any cluster-peer remote match count)
                    verifyRequest(verification, additionalRemoteMatchCount, logCorrelationId, logResult, consumer);
                }
            };
            eventuallyVerify(verification.getTimeout(), singleEvaluation, resultConsumer);
        } else {
            resultConsumer.accept("");
        }
    }

    private void verifyRequest(Verification verification, int additionalRemoteMatchCount, String logCorrelationId, boolean logResult, Consumer<String> resultConsumer) {
        retrieveRequests(verification, logCorrelationId, httpRequests -> {
            try {
                final int totalMatchedCount = httpRequests.size() + additionalRemoteMatchCount;
                if (!verification.getTimes().matches(totalMatchedCount)) {
                    final int matchedCount = totalMatchedCount;
                    boolean matchByExpectationId = verification.getExpectationId() != null;
                    retrieveAllRequests(matchByExpectationId, allRequests -> {
                        String failureMessage;
                        String serializedRequestToBeVerified = requestDefinitionSerializer.serialize(true, verification.getHttpRequest());
                        Integer maximumNumberOfRequestToReturnInVerificationFailure = verification.getMaximumNumberOfRequestToReturnInVerificationFailure() != null ? verification.getMaximumNumberOfRequestToReturnInVerificationFailure() : configuration.maximumNumberOfRequestToReturnInVerificationFailure();
                        if (allRequests.size() < maximumNumberOfRequestToReturnInVerificationFailure) {
                            String serializedAllRequestInLog = allRequests.size() == 1 ? requestDefinitionSerializer.serialize(true, allRequests.get(0)) : requestDefinitionSerializer.serialize(true, allRequests);
                            failureMessage = "Request not found " + verification.getTimes() + ", expected:<" + serializedRequestToBeVerified + "> but was:<" + serializedAllRequestInLog + ">";
                        } else {
                            failureMessage = "Request not found " + verification.getTimes() + ", expected:<" + serializedRequestToBeVerified + "> but was found " + matchedCount + " time" + (matchedCount == 1 ? "" : "s") + " among " + allRequests.size() + " total requests";
                        }
                        if (configuration.detailedVerificationFailures() && !allRequests.isEmpty() && verification.getHttpRequest() instanceof HttpRequest) {
                            String diffSummary = buildClosestMatchDiff((HttpRequest) verification.getHttpRequest(), allRequests);
                            if (isNotBlank(diffSummary)) {
                                failureMessage += diffSummary;
                            }
                        }
                        final Object[] arguments = new Object[]{verification.getHttpRequest(), allRequests.size() == 1 ? allRequests.get(0) : allRequests};
                        if (logResult && mockServerLogger.isEnabledForInstance(Level.INFO)) {
                            mockServerLogger.logEvent(
                                new LogEntry()
                                    .setType(VERIFICATION_FAILED)
                                    .setLogLevel(Level.INFO)
                                    .setCorrelationId(logCorrelationId)
                                    .setHttpRequest(verification.getHttpRequest())
                                    .setMessageFormat("request not found " + verification.getTimes() + ", expected:{}but was:{}")
                                    .setArguments(arguments)
                            );
                        }
                        resultConsumer.accept(failureMessage);
                    });
                } else {
                    if (logResult && mockServerLogger.isEnabledForInstance(Level.INFO)) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setType(VERIFICATION_PASSED)
                                .setLogLevel(Level.INFO)
                                .setCorrelationId(logCorrelationId)
                                .setHttpRequest(verification.getHttpRequest())
                                .setMessageFormat("request:{}found " + verification.getTimes())
                                .setArguments(verification.getHttpRequest())
                        );
                    }
                    resultConsumer.accept("");
                }
            } catch (Throwable throwable) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(EXCEPTION)
                        .setCorrelationId(logCorrelationId)
                        .setMessageFormat("exception:{} while processing verification:{}")
                        .setArguments(throwable.getMessage(), verification)
                        .setThrowable(throwable)
                );
                resultConsumer.accept("exception while processing verification" + (isNotBlank(throwable.getMessage()) ? " " + throwable.getMessage() : ""));
            }
        });
    }

    private void verifyResponse(Verification verification, String logCorrelationId, boolean logResult, Consumer<String> resultConsumer) {
        RequestDefinition requestFilter = verification.getHttpRequest() != null
            ? verification.getHttpRequest().withLogCorrelationId(logCorrelationId)
            : null;
        retrieveRequestResponses(requestFilter, responseVerificationLogPredicate, allPairs -> {
            try {
                HttpResponseMatcher responseMatcher = new HttpResponseMatcher(configuration, mockServerLogger, verification.getHttpResponse());
                List<LogEventRequestAndResponse> matchingPairs = allPairs.stream()
                    .filter(pair -> responseMatcher.matches(pair.getHttpResponse()))
                    .collect(Collectors.toList());
                int matchedCount = matchingPairs.size();
                if (!verification.getTimes().matches(matchedCount)) {
                    HttpResponseSerializer httpResponseSerializer = new HttpResponseSerializer(mockServerLogger);
                    String serializedResponseToBeVerified = httpResponseSerializer.serialize(verification.getHttpResponse());
                    Integer maximumNumberOfRequestToReturnInVerificationFailure = verification.getMaximumNumberOfRequestToReturnInVerificationFailure() != null ? verification.getMaximumNumberOfRequestToReturnInVerificationFailure() : configuration.maximumNumberOfRequestToReturnInVerificationFailure();
                    String failureMessage;
                    if (allPairs.size() < maximumNumberOfRequestToReturnInVerificationFailure) {
                        List<HttpResponse> allResponses = allPairs.stream()
                            .map(LogEventRequestAndResponse::getHttpResponse)
                            .collect(Collectors.toList());
                        String serializedAllResponsesInLog = allResponses.size() == 1
                            ? httpResponseSerializer.serialize(allResponses.get(0))
                            : httpResponseSerializer.serialize(allResponses);
                        failureMessage = "Response not found " + verification.getTimes() + ", expected:<" + serializedResponseToBeVerified + "> but was:<" + serializedAllResponsesInLog + ">";
                    } else {
                        failureMessage = "Response not found " + verification.getTimes() + ", expected:<" + serializedResponseToBeVerified + "> but was found " + matchedCount + " time" + (matchedCount == 1 ? "" : "s") + " among " + allPairs.size() + " recorded responses";
                    }
                    // Mirror the request side (buildClosestMatchDiff): when detailed failures are on and
                    // there is at least one recorded response to compare against, append a field-level
                    // "closest response" diff to the failure message. This is diagnostic only — it never
                    // changes the pass/fail result (already inside the failed branch) and is gated
                    // identically to the request-side closest-match diff (which does NOT gate on INFO, so
                    // the diff reaches the returned failure message regardless of log level).
                    if (configuration.detailedVerificationFailures() && !allPairs.isEmpty()) {
                        List<HttpResponse> recordedResponses = allPairs.stream()
                            .map(LogEventRequestAndResponse::getHttpResponse)
                            .filter(Objects::nonNull)
                            // bound the work like the request side — a huge recorded log should not blow
                            // up the diff computation; cap at the same configured maximum
                            .limit(maximumNumberOfRequestToReturnInVerificationFailure)
                            .collect(Collectors.toList());
                        String diffSummary = buildClosestResponseMatchDiff(verification, verification.getHttpResponse(), recordedResponses);
                        if (isNotBlank(diffSummary)) {
                            failureMessage += diffSummary;
                        }
                    }
                    if (logResult && mockServerLogger.isEnabledForInstance(Level.INFO)) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setType(VERIFICATION_FAILED)
                                .setLogLevel(Level.INFO)
                                .setCorrelationId(logCorrelationId)
                                .setHttpRequest(verification.getHttpRequest())
                                .setMessageFormat("response not found " + verification.getTimes() + ", expected:{}but was:{}")
                                .setArguments(verification.getHttpResponse(), allPairs)
                        );
                    }
                    resultConsumer.accept(failureMessage);
                } else {
                    if (logResult && mockServerLogger.isEnabledForInstance(Level.INFO)) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setType(VERIFICATION_PASSED)
                                .setLogLevel(Level.INFO)
                                .setCorrelationId(logCorrelationId)
                                .setHttpRequest(verification.getHttpRequest())
                                .setMessageFormat("response:{}found " + verification.getTimes())
                                .setArguments(verification.getHttpResponse())
                        );
                    }
                    resultConsumer.accept("");
                }
            } catch (Throwable throwable) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(EXCEPTION)
                        .setCorrelationId(logCorrelationId)
                        .setMessageFormat("exception:{} while processing verification:{}")
                        .setArguments(throwable.getMessage(), verification)
                        .setThrowable(throwable)
                );
                resultConsumer.accept("exception while processing verification" + (isNotBlank(throwable.getMessage()) ? " " + throwable.getMessage() : ""));
            }
        });
    }

    /**
     * Runs one verification evaluation against the current event log, invoking its consumer once with
     * "" (pass) or a non-empty failure message. The {@code logResult} flag decides whether this
     * evaluation writes its {@code VERIFICATION_PASSED}/{@code VERIFICATION_FAILED} outcome to the event
     * log — set {@code false} for intermediate eventual-wait re-evaluations so they don't pollute the log.
     */
    @FunctionalInterface
    private interface SingleVerificationEvaluation {
        void evaluate(boolean logResult, Consumer<String> resultConsumer);
    }

    /**
     * Server-side eventual-verification harness shared by request, response and sequence verification.
     *
     * <p>When {@code timeoutMillis} is {@code null} or {@code <= 0} this runs the supplied
     * single-evaluation exactly once <em>with logging on</em> and forwards its result verbatim —
     * byte-identical to the original single-shot behaviour (no listener, no scheduling, one
     * {@code VERIFICATION_PASSED}/{@code VERIFICATION_FAILED} log entry exactly as before).</p>
     *
     * <p>When {@code timeoutMillis > 0} (clamped to {@link #MAX_VERIFY_TIMEOUT_MILLIS}): it runs the
     * single evaluation with logging <em>suppressed</em>; if it PASSES it completes immediately. If it
     * FAILS it registers a transient {@link MockServerLogListener} on this event log and arms a deadline
     * on the scheduler's executor. On each coalesced {@code updated(...)} notification it re-runs the
     * evaluation (still logging-suppressed) until it passes, and the deadline fires otherwise. The
     * winning path — first passing re-evaluation OR the deadline — runs one FINAL evaluation with
     * logging ON so exactly one outcome (passed or failed) is logged for the whole wait, instead of one
     * {@code VERIFICATION_FAILED} per retry. A single {@link AtomicBoolean} completion guard ensures the
     * result consumer is invoked exactly once whichever fires first, and the transient listener and
     * deadline future are always cleaned up so neither leaks. The wait never blocks a Netty I/O thread —
     * re-evaluation and deadline both run on the scheduler, completion is delivered through the async
     * result consumer.</p>
     *
     * <p>If no scheduled executor is available (synchronous {@code Scheduler}, e.g. WAR/servlet) the
     * eventual path cannot be armed, so it degrades to the logging-on single-shot result.</p>
     */
    private void eventuallyVerify(Long timeoutMillis, SingleVerificationEvaluation singleEvaluation, Consumer<String> resultConsumer) {
        final long effectiveTimeout = timeoutMillis == null ? 0L : Math.min(timeoutMillis, MAX_VERIFY_TIMEOUT_MILLIS);
        final ScheduledExecutorService executor = getScheduler().getExecutorService();
        if (effectiveTimeout <= 0 || executor == null || executor.isShutdown()) {
            // single-shot: original behaviour, logging on, no listener and no scheduling
            singleEvaluation.evaluate(true, resultConsumer);
            return;
        }
        // first evaluation with logging suppressed: complete immediately on pass (logging the single
        // PASSED outcome via a final logging-on evaluation), only arm the eventual path on failure
        singleEvaluation.evaluate(false, firstFailureMessage -> {
            if (isBlank(firstFailureMessage)) {
                // re-evaluate once with logging on to emit the single PASSED outcome and complete
                singleEvaluation.evaluate(true, resultConsumer);
            } else {
                armEventualVerification(effectiveTimeout, executor, singleEvaluation, resultConsumer);
            }
        });
    }

    private void armEventualVerification(long effectiveTimeout, ScheduledExecutorService executor, SingleVerificationEvaluation singleEvaluation, Consumer<String> resultConsumer) {
        final AtomicBoolean completed = new AtomicBoolean(false);
        // holder so the listener can unregister itself and the deadline can be cancelled on completion
        final MockServerLogListener[] listenerHolder = new MockServerLogListener[1];
        final ScheduledFuture<?>[] deadlineHolder = new ScheduledFuture<?>[1];

        final Runnable cleanup = () -> {
            if (listenerHolder[0] != null) {
                unregisterListener(listenerHolder[0]);
            }
            if (deadlineHolder[0] != null) {
                deadlineHolder[0].cancel(false);
            }
        };
        // Claims completion exactly once, then runs ONE final logging-on evaluation so the single
        // PASSED/FAILED outcome is logged and the real result (re-derived now, including any request
        // that arrived right at the deadline) is forwarded to the caller. Cleanup happens first so the
        // listener/deadline are gone before the final evaluation runs.
        final Runnable completeOnce = () -> {
            if (completed.compareAndSet(false, true)) {
                cleanup.run();
                singleEvaluation.evaluate(true, resultConsumer);
            }
        };

        final MockServerLogListener listener = mockServerLog -> {
            if (completed.get()) {
                return;
            }
            // re-run the evaluation (logging suppressed) on each coalesced log change; only a pass
            // triggers completion — a continued failure just keeps waiting for the deadline
            singleEvaluation.evaluate(false, message -> {
                if (isBlank(message)) {
                    completeOnce.run();
                }
            });
        };
        listenerHolder[0] = listener;
        registerListener(listener);

        try {
            deadlineHolder[0] = executor.schedule(completeOnce, effectiveTimeout, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            // executor shutting down between the isShutdown() check and schedule() — complete now so the
            // caller never hangs, and clean up the just-registered listener
            completeOnce.run();
            return;
        }
        // If a notification already completed the verification while the deadline was being scheduled,
        // cleanup ran before deadlineHolder was assigned — cancel the now-assigned future so it never
        // fires (it would be a harmless no-op via the guard, but cancelling avoids a lingering timer).
        if (completed.get()) {
            deadlineHolder[0].cancel(false);
        }

        // A passing event may have arrived between the first evaluation and the listener registration.
        // Re-run once more now so we don't miss it while waiting for the next (coalesced) notification.
        listener.updated(this);
    }

    public Future<String> verify(VerificationSequence verification) {
        CompletableFuture<String> result = new CompletableFuture<>();
        verify(verification, result::complete);
        return result;
    }

    public void verify(VerificationSequence verificationSequence, Consumer<String> resultConsumer) {
        drainDisruptor();
        if (verificationSequence != null) {
            final String logCorrelationId = UUIDService.getUUID();
            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(VERIFICATION)
                        .setLogLevel(Level.INFO)
                        .setCorrelationId(logCorrelationId)
                        .setHttpRequests(verificationSequence.getHttpRequests().toArray(new RequestDefinition[0]))
                        .setMessageFormat(VERIFICATION_REQUEST_SEQUENCES_MESSAGE_FORMAT)
                        .setArguments(verificationSequence)
                );
            }
            eventuallyVerify(
                verificationSequence.getTimeout(),
                (logResult, consumer) -> verifySequenceOnce(verificationSequence, logCorrelationId, logResult, consumer),
                resultConsumer
            );
        } else {
            resultConsumer.accept("");
        }
    }

    private void verifySequenceOnce(VerificationSequence verificationSequence, String logCorrelationId, boolean logResult, Consumer<String> resultConsumer) {
        {
            boolean hasExpectationIds = verificationSequence.getExpectationIds() != null && !verificationSequence.getExpectationIds().isEmpty();
            boolean hasRequests = verificationSequence.getHttpRequests() != null && !verificationSequence.getHttpRequests().isEmpty();
            boolean hasResponses = verificationSequence.getHttpResponses() != null && !verificationSequence.getHttpResponses().isEmpty();
            if (!hasExpectationIds && !hasRequests && !hasResponses) {
                // an entirely-empty sequence (no expectationIds, no requests, no responses) is
                // meaningless — reject it with a clear error rather than vacuously passing
                resultConsumer.accept("No expectations, requests or responses specified in verification sequence");
                return;
            }
            if (hasExpectationIds) {
                retrieveAllRequests(verificationSequence.getExpectationIds().stream().map(ExpectationId::getId).collect(Collectors.toList()), allRequests -> {
                    List<RequestDefinition> requestDefinitions = allRequests.stream().map(RequestAndExpectationId::getRequestDefinition).collect(Collectors.toList());
                    try {
                        String failureMessage = "";
                        int requestLogCounter = 0;
                        for (ExpectationId expectationId : verificationSequence.getExpectationIds()) {
                            if (expectationId != null) {
                                boolean foundRequest = false;
                                for (; !foundRequest && requestLogCounter < allRequests.size(); requestLogCounter++) {
                                    if (allRequests.get(requestLogCounter).matches(expectationId)) {
                                        // move on to next request
                                        foundRequest = true;
                                    }
                                }
                                if (!foundRequest) {
                                    // expectation-ID sequence steps match by recorded expectation id, not by
                                    // request fields, so there is no per-field template to diff against — pass
                                    // null so no closest-match diff is appended for this path
                                    failureMessage = verificationSequenceFailureMessage(verificationSequence, logCorrelationId, logResult, requestDefinitions, null);
                                    break;
                                }
                            }
                        }
                        verificationSequenceSuccessMessage(verificationSequence, resultConsumer, logCorrelationId, logResult, failureMessage);

                    } catch (Throwable throwable) {
                        verificationSequenceExceptionHandler(verificationSequence, resultConsumer, logCorrelationId, throwable, "exception:{} while processing verification sequence:{}", "exception while processing verification sequence");
                    }
                });
            } else if (hasResponses) {
                // response-aware sequence verification over recorded request-response pairs.
                // Only count responses the mocks actually produced (EXPECTATION_RESPONSE +
                // FORWARDED_REQUEST), never MockServer's own NO_MATCH_RESPONSE auto-404s.
                final List<RequestDefinition> httpRequests = verificationSequence.getHttpRequests();
                final List<HttpResponse> httpResponses = verificationSequence.getHttpResponses();
                // A response-aware sequence pairs the i-th request with the i-th response, so when
                // both lists are supplied they MUST be the same length. Previously the shorter list
                // was padded with null and a null matcher always matched, so a mismatched-length
                // sequence silently passed on the unspecified steps — reject it instead. One list
                // being empty (request-only or response-only sequence) is still valid.
                if (!httpRequests.isEmpty() && httpRequests.size() != httpResponses.size()) {
                    resultConsumer.accept(
                        "Request and response sequences must be the same length for a response-aware verification sequence, found "
                            + httpRequests.size() + " request(s) and " + httpResponses.size() + " response(s)"
                    );
                    return;
                }
                retrieveRequestResponses(null, responseVerificationLogPredicate, allPairs -> {
                    try {
                        String failureMessage = "";
                        int pairLogCounter = 0;
                        // requests are either absent (response-only) or the same length as responses
                        // (enforced above), so the response count is the number of steps to verify
                        int stepCount = httpResponses.size();
                        for (int i = 0; i < stepCount; i++) {
                            RequestDefinition verificationHttpRequest = i < httpRequests.size() ? httpRequests.get(i) : null;
                            HttpResponse verificationHttpResponse = i < httpResponses.size() ? httpResponses.get(i) : null;
                            HttpRequestMatcher httpRequestMatcher = verificationHttpRequest != null
                                ? matcherBuilder.transformsToMatcher(verificationHttpRequest.withLogCorrelationId(logCorrelationId))
                                : null;
                            HttpResponseMatcher httpResponseMatcher = verificationHttpResponse != null
                                ? new HttpResponseMatcher(configuration, mockServerLogger, verificationHttpResponse)
                                : null;
                            boolean foundMatch = false;
                            for (; !foundMatch && pairLogCounter < allPairs.size(); pairLogCounter++) {
                                LogEventRequestAndResponse pair = allPairs.get(pairLogCounter);
                                // a pair with a null recorded request can never satisfy a
                                // request-constrained step — treat it as non-matching rather than
                                // dereferencing it (which would NPE and be masked as a generic
                                // "exception while processing verification sequence")
                                boolean requestMatches = httpRequestMatcher == null
                                    || (pair.getHttpRequest() != null && httpRequestMatcher.matches(((HttpRequest) pair.getHttpRequest()).cloneWithLogCorrelationId()));
                                boolean responseMatches = httpResponseMatcher == null || httpResponseMatcher.matches(pair.getHttpResponse());
                                if (requestMatches && responseMatches) {
                                    foundMatch = true;
                                }
                            }
                            if (!foundMatch) {
                                List<HttpResponse> recordedResponses = allPairs.stream()
                                    .map(LogEventRequestAndResponse::getHttpResponse)
                                    .collect(Collectors.toList());
                                failureMessage = verificationResponseSequenceFailureMessage(verificationSequence, logCorrelationId, logResult, recordedResponses, verificationHttpRequest, verificationHttpResponse);
                                break;
                            }
                        }
                        verificationSequenceSuccessMessage(verificationSequence, resultConsumer, logCorrelationId, logResult, failureMessage);
                    } catch (Throwable throwable) {
                        verificationSequenceExceptionHandler(verificationSequence, resultConsumer, logCorrelationId, throwable, "exception:{} while processing verification sequence:{}", "exception while processing verification sequence");
                    }
                });
            } else {
                retrieveAllRequests(false, allRequests -> {
                    try {
                        String failureMessage = "";
                        int requestLogCounter = 0;
                        for (RequestDefinition verificationHttpRequest : verificationSequence.getHttpRequests()) {
                            if (verificationHttpRequest != null) {
                                verificationHttpRequest.withLogCorrelationId(logCorrelationId);
                                HttpRequestMatcher httpRequestMatcher = matcherBuilder.transformsToMatcher(verificationHttpRequest);
                                boolean foundRequest = false;
                                for (; !foundRequest && requestLogCounter < allRequests.size(); requestLogCounter++) {
                                    if (httpRequestMatcher.matches(allRequests.get(requestLogCounter).cloneWithLogCorrelationId())) {
                                        // move on to next request
                                        foundRequest = true;
                                    }
                                }
                                if (!foundRequest) {
                                    failureMessage = verificationSequenceFailureMessage(verificationSequence, logCorrelationId, logResult, allRequests, verificationHttpRequest);
                                    break;
                                }
                            }
                        }
                        verificationSequenceSuccessMessage(verificationSequence, resultConsumer, logCorrelationId, logResult, failureMessage);

                    } catch (Throwable throwable) {
                        verificationSequenceExceptionHandler(verificationSequence, resultConsumer, logCorrelationId, throwable, "exception:{} while processing verification sequence:{}", "exception while processing verification sequence");
                    }
                });
            }
        }
    }

    private String buildClosestMatchDiff(HttpRequest verificationRequest, List<RequestDefinition> allRequests) {
        try {
            HttpRequestMatcher verificationMatcher = matcherBuilder.transformsToMatcher(verificationRequest);
            int closestMatchFailures = Integer.MAX_VALUE;
            Map<MatchDifference.Field, List<String>> closestDifferences = null;
            int totalFields = MatchDifference.Field.values().length;

            for (RequestDefinition receivedRequest : allRequests) {
                if (receivedRequest instanceof HttpRequest) {
                    HttpRequest received = (HttpRequest) receivedRequest;
                    MatchDifference matchDifference = new MatchDifference(true, received);
                    verificationMatcher.matches(matchDifference, received);
                    Map<MatchDifference.Field, List<String>> differences = matchDifference.getAllDifferences();
                    int failures = differences.size();
                    if (failures < closestMatchFailures) {
                        closestMatchFailures = failures;
                        closestDifferences = differences;
                        if (failures == 0) {
                            break;
                        }
                    }
                }
            }

            if (closestDifferences != null && !closestDifferences.isEmpty()) {
                return MatchDifferenceFormatter.formatDifferences(closestDifferences);
            }
        } catch (Exception e) {
            if (mockServerLogger.isEnabledForInstance(Level.TRACE)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.TRACE)
                        .setMessageFormat("exception generating closest match diff:{}")
                        .setArguments(e.getMessage())
                        .setThrowable(e)
                );
            }
        }
        return "";
    }

    /**
     * Response-verification analogue of {@link #buildClosestMatchDiff(HttpRequest, List)}: compares the
     * verification response template against each recorded response and returns a field-level diff for
     * the closest one (fewest differing fields), formatted by {@link MatchDifferenceFormatter} exactly
     * as the request side is. Diagnostic only — the result is appended to the failure message and never
     * affects the pass/fail outcome.
     * <p>
     * The {@code verification} argument supplies a non-null request for the {@link MatchDifference}: it
     * dereferences its {@code httpRequest} when TRACE logging records a difference, so a null request
     * would NPE — fall back to an empty {@link HttpRequest#request()} placeholder when the verification
     * carries no request (a response-only verify), mirroring the request side's non-null guarantee.
     */
    private String buildClosestResponseMatchDiff(Verification verification, HttpResponse verificationResponse, List<HttpResponse> recordedResponses) {
        try {
            HttpResponseMatcher verificationMatcher = new HttpResponseMatcher(configuration, mockServerLogger, verificationResponse);
            // MatchDifference dereferences its request under TRACE logging, so it must never be null —
            // use the verification request when present, otherwise a non-null empty request placeholder
            RequestDefinition diffRequest = verification.getHttpRequest() instanceof HttpRequest
                ? (HttpRequest) verification.getHttpRequest()
                : request();
            int closestMatchFailures = Integer.MAX_VALUE;
            Map<MatchDifference.Field, List<String>> closestDifferences = null;

            for (HttpResponse recordedResponse : recordedResponses) {
                // defensive: the call site already filters nulls, but guard here too so this helper is
                // safe in isolation (HttpResponseMatcher.matches returns false on a null actual anyway)
                if (recordedResponse == null) {
                    continue;
                }
                MatchDifference matchDifference = new MatchDifference(true, diffRequest);
                verificationMatcher.matches(matchDifference, recordedResponse);
                Map<MatchDifference.Field, List<String>> differences = matchDifference.getAllDifferences();
                int failures = differences.size();
                if (failures < closestMatchFailures) {
                    closestMatchFailures = failures;
                    closestDifferences = differences;
                    if (failures == 0) {
                        break;
                    }
                }
            }

            if (closestDifferences != null && !closestDifferences.isEmpty()) {
                return MatchDifferenceFormatter.formatDifferences(closestDifferences);
            }
        } catch (Exception e) {
            if (mockServerLogger.isEnabledForInstance(Level.TRACE)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.TRACE)
                        .setMessageFormat("exception generating closest response match diff:{}")
                        .setArguments(e.getMessage())
                        .setThrowable(e)
                );
            }
        }
        return "";
    }

    private void verificationSequenceSuccessMessage(VerificationSequence verificationSequence, Consumer<String> resultConsumer, String logCorrelationId, boolean logResult, String failureMessage) {
        if (isBlank(failureMessage) && logResult && mockServerLogger.isEnabledForInstance(Level.INFO)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(VERIFICATION_PASSED)
                    .setLogLevel(Level.INFO)
                    .setCorrelationId(logCorrelationId)
                    .setMessageFormat("request sequence found:{}")
                    .setArguments(verificationSequence.getHttpRequests())
            );
        }
        resultConsumer.accept(failureMessage);
    }

    private String verificationSequenceFailureMessage(VerificationSequence verificationSequence, String logCorrelationId, boolean logResult, List<RequestDefinition> allRequests, RequestDefinition unmatchedStepRequest) {
        String failureMessage;
        String serializedRequestToBeVerified = requestDefinitionSerializer.serialize(true, verificationSequence.getHttpRequests());
        Integer maximumNumberOfRequestToReturnInVerificationFailure = verificationSequence.getMaximumNumberOfRequestToReturnInVerificationFailure() != null ? verificationSequence.getMaximumNumberOfRequestToReturnInVerificationFailure() : configuration.maximumNumberOfRequestToReturnInVerificationFailure();
        if (allRequests.size() < maximumNumberOfRequestToReturnInVerificationFailure) {
            String serializedAllRequestInLog = allRequests.size() == 1 ? requestDefinitionSerializer.serialize(true, allRequests.get(0)) : requestDefinitionSerializer.serialize(true, allRequests);
            failureMessage = "Request sequence not found, expected:<" + serializedRequestToBeVerified + "> but was:<" + serializedAllRequestInLog + ">";
        } else {
            failureMessage = "Request sequence not found, expected:<" + serializedRequestToBeVerified + "> but was not found, found " + allRequests.size() + " other requests";
        }
        // Mirror the single-request verify path (verifyRequest -> buildClosestMatchDiff): when detailed
        // failures are enabled and there is a recorded request to compare against, append a field-level
        // closest-match diff for the specific sequence step that failed to match, so the failure shows
        // which fields (method/path/headers/body/...) differ from the closest actual request. This is
        // diagnostic only — it never changes the pass/fail outcome (already inside the failed branch).
        if (configuration.detailedVerificationFailures() && unmatchedStepRequest instanceof HttpRequest && !allRequests.isEmpty()) {
            String diffSummary = buildClosestMatchDiff((HttpRequest) unmatchedStepRequest, allRequests);
            if (isNotBlank(diffSummary)) {
                failureMessage += diffSummary;
            }
        }
        final Object[] arguments = new Object[]{verificationSequence.getHttpRequests(), allRequests.size() == 1 ? allRequests.get(0) : allRequests};
        if (logResult && mockServerLogger.isEnabledForInstance(Level.INFO)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(VERIFICATION_FAILED)
                    .setLogLevel(Level.INFO)
                    .setCorrelationId(logCorrelationId)
                    .setHttpRequests(verificationSequence.getHttpRequests().toArray(new RequestDefinition[0]))
                    .setMessageFormat("request sequence not found, expected:{}but was:{}")
                    .setArguments(arguments)
            );
        }
        return failureMessage;
    }

    private String verificationResponseSequenceFailureMessage(VerificationSequence verificationSequence, String logCorrelationId, boolean logResult, List<HttpResponse> recordedResponses, RequestDefinition unmatchedStepRequest, HttpResponse unmatchedStepResponse) {
        // for a response-aware sequence the meaningful "expected" and "actual" are the RESPONSES,
        // not the requests — serialize the expected response sequence and the recorded responses
        HttpResponseSerializer httpResponseSerializer = new HttpResponseSerializer(mockServerLogger);
        List<HttpResponse> expectedResponses = verificationSequence.getHttpResponses();
        String serializedExpectedResponses = expectedResponses.size() == 1
            ? httpResponseSerializer.serialize(expectedResponses.get(0))
            : httpResponseSerializer.serialize(expectedResponses);
        Integer maximumNumberOfRequestToReturnInVerificationFailure = verificationSequence.getMaximumNumberOfRequestToReturnInVerificationFailure() != null ? verificationSequence.getMaximumNumberOfRequestToReturnInVerificationFailure() : configuration.maximumNumberOfRequestToReturnInVerificationFailure();
        String failureMessage;
        if (recordedResponses.size() < maximumNumberOfRequestToReturnInVerificationFailure) {
            String serializedRecordedResponses = recordedResponses.size() == 1
                ? httpResponseSerializer.serialize(recordedResponses.get(0))
                : httpResponseSerializer.serialize(recordedResponses);
            failureMessage = "Response sequence not found, expected:<" + serializedExpectedResponses + "> but was:<" + serializedRecordedResponses + ">";
        } else {
            failureMessage = "Response sequence not found, expected:<" + serializedExpectedResponses + "> but was not found, found " + recordedResponses.size() + " other responses";
        }
        // Mirror the single-response verify path (verifyResponse -> buildClosestResponseMatchDiff): when
        // detailed failures are enabled and the failed step constrains the response, append a field-level
        // closest-response diff for that step against the recorded responses. Diagnostic only.
        if (configuration.detailedVerificationFailures() && unmatchedStepResponse != null && !recordedResponses.isEmpty()) {
            List<HttpResponse> nonNullRecordedResponses = recordedResponses.stream()
                .filter(Objects::nonNull)
                .limit(maximumNumberOfRequestToReturnInVerificationFailure)
                .collect(Collectors.toList());
            if (!nonNullRecordedResponses.isEmpty()) {
                Verification diffVerification = new Verification()
                    .withRequest(unmatchedStepRequest instanceof HttpRequest ? unmatchedStepRequest : null)
                    .withResponse(unmatchedStepResponse);
                String diffSummary = buildClosestResponseMatchDiff(diffVerification, unmatchedStepResponse, nonNullRecordedResponses);
                if (isNotBlank(diffSummary)) {
                    failureMessage += diffSummary;
                }
            }
        }
        if (logResult && mockServerLogger.isEnabledForInstance(Level.INFO)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(VERIFICATION_FAILED)
                    .setLogLevel(Level.INFO)
                    .setCorrelationId(logCorrelationId)
                    .setMessageFormat("response sequence not found, expected:{}but was:{}")
                    .setArguments(expectedResponses, recordedResponses)
            );
        }
        return failureMessage;
    }

    private void verificationSequenceExceptionHandler(VerificationSequence verificationSequence, Consumer<String> resultConsumer, String logCorrelationId, Throwable throwable, String s, String s2) {
        mockServerLogger.logEvent(
            new LogEntry()
                .setType(EXCEPTION)
                .setCorrelationId(logCorrelationId)
                .setMessageFormat(s)
                .setArguments(throwable.getMessage(), verificationSequence)
                .setThrowable(throwable)
        );
        resultConsumer.accept(s2 + (isNotBlank(throwable.getMessage()) ? " " + throwable.getMessage() : ""));
    }

    protected String[] fieldsExcludedFromEqualsAndHashCode() {
        return EXCLUDED_FIELDS;
    }

}
