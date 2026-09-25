package org.mockserver.log.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Multimap;
import com.lmax.disruptor.EventTranslator;
import org.mockserver.matchers.HttpRequestMatcher;
import org.mockserver.matchers.MatchDifference;
import org.mockserver.matchers.TimeToLive;
import org.mockserver.matchers.Times;
import org.mockserver.mock.Expectation;
import org.mockserver.model.*;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.time.EpochService;
import org.mockserver.uuid.UUIDService;
import org.slf4j.event.Level;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.Date;
import java.util.Locale;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.formatting.StringFormatter.formatCompactLogMessage;
import static org.mockserver.formatting.StringFormatter.formatLogMessage;
import static org.mockserver.model.HttpRequest.request;

/**
 * @author jamesdbloom
 */
public class LogEntry implements EventTranslator<LogEntry> {

    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.createObjectMapper();
    private static final RequestDefinition[] EMPTY_REQUEST_DEFINITIONS = new RequestDefinition[0];
    private static final RequestDefinition[] DEFAULT_REQUESTS_DEFINITIONS = {request()};
    /**
     * Thread-safe replacement for the previous shared {@code SimpleDateFormat}.
     * <p>
     * {@code SimpleDateFormat} is NOT thread-safe; this single static instance was formatted
     * concurrently from the Disruptor log handler and the retrieve/export/serialize threads,
     * which can corrupt its internal {@code Calendar} and produce garbled timestamps or an
     * intermittent {@link ArrayIndexOutOfBoundsException}. {@link DateTimeFormatter} is
     * immutable and thread-safe, so a single shared instance is safe to format from any number
     * of threads. The pattern and the system-default zone reproduce exactly the same output the
     * old {@code SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")} (which used the default zone)
     * produced, so timestamp strings remain byte-for-byte identical.
     */
    public static final LogDateFormat LOG_DATE_FORMAT = new LogDateFormat();

    /**
     * Tiny thread-safe formatter exposing the same {@code format(Date)} call shape the previous
     * public {@code DateFormat LOG_DATE_FORMAT} field offered, backed by an immutable
     * {@link DateTimeFormatter}. Kept as a nested type so existing callers
     * ({@code LOG_DATE_FORMAT.format(new Date(...))}) compile unchanged while gaining
     * thread-safety.
     */
    public static final class LogDateFormat {
        private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ENGLISH).withZone(ZoneId.systemDefault());

        private LogDateFormat() {
        }

        public String format(Date date) {
            return FORMATTER.format(date.toInstant());
        }

        public String format(long epochMillis) {
            return FORMATTER.format(Instant.ofEpochMilli(epochMillis));
        }
    }
    private int hashCode;
    private String id;
    private String correlationId;
    private Integer port;
    private Level logLevel = Level.INFO;
    private boolean alwaysLog = false;
    private long epochTime = EpochService.currentTimeMillis();
    private String timestamp;
    private LogMessageType type;
    private RequestDefinition[] httpRequests;
    private RequestDefinition[] httpUpdatedRequests;
    private HttpResponse httpResponse;
    private HttpResponse httpUpdatedResponse;
    private HttpError httpError;
    // Holds a REAL expectation supplied via setExpectation(Expectation) (a closest-match or matched
    // expectation). It is null for the synthetic two-argument form, which is derived on demand from the
    // entry's own request/response instead of being built and retained per served request - see
    // expectationIsSynthetic and getExpectation().
    private Expectation expectation;
    // Set by the synthetic setExpectation(RequestDefinition, HttpResponse) form used on the serving path.
    // The synthetic Expectation is a pure function of the entry's request/response, so it is derived
    // lazily rather than allocated and retained per request (which added a full Expectation + Timing graph
    // to every log entry). Equality-relevant, so an entry that carries a synthetic expectation stays
    // unequal to one that carries none, matching the previous behaviour where the field was non-null.
    private boolean expectationIsSynthetic;
    // Memoized synthetic Expectation, materialized lazily by getExpectation(). One retained LogEntry is
    // read concurrently by multiple off-consumer threads (logQueryExecutor scans, parallel /retrieve
    // serializations), so this cache is written on reader threads with no mutual happens-before. It MUST
    // stay volatile: Expectation is a mutable model without all-final fields, so only the volatile write
    // of the reference after construction safely publishes the referent's fields to another reader - a
    // plain field could hand a second reader a partially constructed object (NPE / torn serialized output).
    // Double construction under contention is benign (the divergent random id is already random). NOT
    // equality-relevant: it caches a value fully derivable from equality-relevant fields.
    private transient volatile Expectation derivedSyntheticExpectation;
    private String expectationId;
    private Throwable throwable;
    private Runnable consumer;
    private boolean deleted = false;
    // Transient marker set on entries injected back into the log by the recorded-traffic re-import
    // path ({@code PUT /mockserver/import?format=recording}). It tells MockServerEventLog.processLogEntry
    // NOT to hand the entry to the recorded-request disk consumer, so re-loading an archive does not
    // append the same exchanges back to the (possibly same) NDJSON file and grow it without bound.
    private transient boolean skipRecordedRequestPersistence = false;

    private String messageFormat;
    private String message;
    private Object[] arguments;
    private String because;
    /**
     * Memoized estimate of the heap retained by this entry, used as the weight by the byte-budget
     * eviction in {@link org.mockserver.collections.CircularConcurrentLinkedDeque} and by the ring
     * in-flight bound in {@link org.mockserver.log.MockServerEventLog}. Lazily computed once and cached
     * so the weight is identical at add-time and at evict-time (the deque recomputes the weight when it
     * evicts). {@code -1} means "not yet computed". See {@link #estimatedHeapSize()} for what is counted.
     */
    private transient long estimatedHeapSize = -1;

    // Per-entry structural overhead of the retained LogEntry graph beyond the request/response bodies:
    // the LogEntry object itself, its id (UUID)/timestamp/messageFormat/correlationId strings, the
    // arguments and httpRequests arrays, and the ConcurrentLinkedDeque node that holds it. Charged once
    // per entry that carries at least one HTTP message (a pure diagnostic entry with no request/response
    // retains none of this graph and stays weightless, so the ring in-flight bound continues to ignore
    // it). Empirically ~0.9 KB on a HotSpot heap dump (compressed oops); see estimatedHeapSize().
    private static final long BASE_ENTRY_OVERHEAD_BYTES = 896;
    // Structural overhead of ONE retained HttpRequest or HttpResponse model object beyond its body and
    // header bytes: the model object, its method/path/statusCode/reasonPhrase NottableStrings, the Body
    // wrapper (e.g. JsonBody), and the Headers container. Charged per request definition and once for
    // the response. Empirically ~1.15 KB per message on a HotSpot heap dump; see estimatedHeapSize().
    private static final long PER_HTTP_MESSAGE_OVERHEAD_BYTES = 1152;
    // Per-header-value overhead beyond the name and value characters themselves: the Header/NottableString
    // objects and the multimap entry that holds them. Added to the summed name+value character counts.
    private static final long HEADER_ENTRY_OVERHEAD_BYTES = 64;

    public LogEntry() {

    }

    /**
     * Stable estimate of the bytes this entry retains on the heap, used as the weight for the event
     * log's byte budget ({@code maxEventLogSizeInBytes}). It is a materially honest estimate, not a
     * precise object-graph walk: it counts the terms that actually dominate a retained entry, so the
     * budget approximates real retained memory rather than raw body bytes alone.
     * <p>
     * Counted, per HTTP message (each request definition and the response):
     * <ul>
     *   <li>the raw body bytes ({@code getBodyAsRawBytes()}) — the dominant cost for large captures;</li>
     *   <li>any decoded String view still cached on the body ({@code retainedDerivedFormBytes()}); a
     *   retained live-traffic body has released this, so it usually adds nothing (see {@code releaseDerivedForms});</li>
     *   <li>the header name + value characters, plus a small fixed overhead per header value;</li>
     *   <li>a fixed structural overhead ({@link #PER_HTTP_MESSAGE_OVERHEAD_BYTES}) for the model object,
     *   its method/path/status NottableStrings and its Body/Headers wrappers.</li>
     * </ul>
     * plus a fixed per-entry structural overhead ({@link #BASE_ENTRY_OVERHEAD_BYTES}) for the LogEntry
     * graph itself, charged only when the entry carries at least one HTTP message (a pure diagnostic
     * entry with no request/response retains almost none of this and stays weightless, so the ring
     * in-flight bound keeps ignoring the overwhelming majority of small control entries).
     * <p>
     * The constants were calibrated against a HotSpot live heap dump (compressed oops) of a filled event
     * log: at {@code WARN} this brings the estimate to within a few percent of the real retained heap.
     * <p>
     * <strong>What is deliberately NOT counted, and why.</strong> The lazily-derived {@code httpUpdated*}
     * copies and the rendered {@code arguments} are transient (rebuilt at render time, not retained), so
     * they are excluded — as is the memoized {@code message} string. The {@code message} is the one large
     * term that is level-dependent: it is materialized on the retained entry only at a rendering log
     * level ({@code INFO}/{@code DEBUG}/{@code TRACE}, where {@code writeToSystemOut} renders each entry),
     * and it is populated <em>after</em> this weight is first computed and memoized, so it cannot be
     * counted here without either double-counting it at non-rendering levels or making the weight depend
     * on when it happens to be rendered — both of which would break the invariant that the add-time
     * weight equals the evict-time weight exactly. The residual message cost is instead handled at the
     * budget-sizing layer, whose default divisor is already log-level-aware (tighter at rendering levels)
     * precisely to cover it — see {@code ConfigurationProperties.defaultMaxEventLogSizeInBytes}. So the
     * estimate lands near 1x real retention at {@code WARN} and remains a fraction under at {@code INFO}.
     * <p>
     * Reads the {@code httpRequests} field directly (not {@link #getHttpRequests()}, which substitutes a
     * default request when unset), and all counted terms are stable once the entry is built, so the value
     * never changes after first computation — the byte-budget eviction relies on the add-time weight
     * matching the evict-time weight exactly.
     */
    @JsonIgnore
    public long estimatedHeapSize() {
        if (estimatedHeapSize < 0) {
            long size = 0;
            boolean hasHttpMessage = false;
            RequestDefinition[] reqs = this.httpRequests;
            if (reqs != null) {
                for (RequestDefinition rd : reqs) {
                    if (rd instanceof HttpRequest) {
                        HttpRequest request = (HttpRequest) rd;
                        hasHttpMessage = true;
                        size += PER_HTTP_MESSAGE_OVERHEAD_BYTES;
                        byte[] b = request.getBodyAsRawBytes();
                        if (b != null) {
                            size += b.length;
                        }
                        size += derivedBodyBytes(request.getBody());
                        size += headerBytes(request.getHeaders());
                    }
                }
            }
            if (httpResponse != null) {
                hasHttpMessage = true;
                size += PER_HTTP_MESSAGE_OVERHEAD_BYTES;
                byte[] b = httpResponse.getBodyAsRawBytes();
                if (b != null) {
                    size += b.length;
                }
                size += derivedBodyBytes(httpResponse.getBody());
                size += headerBytes(httpResponse.getHeaders());
            }
            if (hasHttpMessage) {
                size += BASE_ENTRY_OVERHEAD_BYTES;
            }
            estimatedHeapSize = size;
        }
        return estimatedHeapSize;
    }

    /**
     * Sum the retained bytes of a {@link Headers} block: the name and value characters of every header
     * value, plus {@link #HEADER_ENTRY_OVERHEAD_BYTES} per value for the Header/NottableString objects.
     * Iterates the backing multimap directly (no {@code getHeaderList()} allocation) and is skipped
     * entirely when the block is null or empty, so a header-less entry pays nothing here. Deterministic:
     * the header content of a retained entry does not change after it is built.
     */
    private static long headerBytes(Headers headers) {
        if (headers == null || headers.isEmpty()) {
            return 0;
        }
        long size = 0;
        Multimap<NottableString, NottableString> multimap = headers.getMultimap();
        for (Map.Entry<NottableString, NottableString> entry : multimap.entries()) {
            size += HEADER_ENTRY_OVERHEAD_BYTES;
            NottableString key = entry.getKey();
            if (key != null && key.getValue() != null) {
                size += key.getValue().length();
            }
            NottableString value = entry.getValue();
            if (value != null && value.getValue() != null) {
                size += value.getValue().length();
            }
        }
        return size;
    }

    /**
     * Heap held by a body's derived String view, in bytes ({@code length * 2} for UTF-16), or 0 once it
     * has been released. Charged so an entry still holding the decoded copy (a re-imported recording whose
     * canonical value differs from its raw bytes, or one not yet passed through the release hook) is weighed
     * for it; a retained live-traffic body has released it, so this adds nothing and the weight stays at
     * roughly the raw bytes plus the structural constants.
     */
    private static long derivedBodyBytes(Body<?> body) {
        return body == null ? 0L : body.retainedDerivedFormBytes();
    }

    /**
     * Drop the lazily-derived String (and JSON tree) cached on this entry's request/response bodies, called
     * once the entry has been moved into the retained event-log deque (see
     * {@code MockServerEventLog.processLogEntry}). The bodies keep their canonical raw bytes, so a later
     * render/verify re-derives the identical String; this only frees the redundant second copy.
     */
    public void releaseDerivedForms() {
        RequestDefinition[] reqs = this.httpRequests;
        if (reqs != null) {
            for (RequestDefinition rd : reqs) {
                if (rd instanceof HttpRequest) {
                    Body<?> body = ((HttpRequest) rd).getBody();
                    if (body != null) {
                        body.releaseDerivedForms();
                    }
                }
            }
        }
        if (httpResponse != null && httpResponse.getBody() != null) {
            httpResponse.getBody().releaseDerivedForms();
        }
    }

    private LogEntry setId(String id) {
        this.id = id;
        return this;
    }

    @JsonIgnore
    public String id() {
        if (id == null) {
            // A log-entry id needs only UNIQUENESS (it correlates and de-duplicates event-log entries);
            // it is never a security token. Minting a cryptographically-secure UUID here - for EVERY
            // log entry, on the disruptor ring-buffer publish path - serialised all worker event loops
            // on the shared SecureRandom monitor under load, so use the fast contention-free generator.
            id = UUIDService.getNonSecureUUID();
        }
        return id;
    }

    public void clear() {
        id = null;
        hashCode = 0;
        logLevel = Level.INFO;
        alwaysLog = false;
        correlationId = null;
        port = null;
        epochTime = -1;
        timestamp = null;
        type = null;
        httpRequests = null;
        httpUpdatedRequests = null;
        httpResponse = null;
        httpUpdatedResponse = null;
        httpError = null;
        expectation = null;
        expectationIsSynthetic = false;
        derivedSyntheticExpectation = null;
        expectationId = null;
        throwable = null;
        consumer = null;
        deleted = false;
        skipRecordedRequestPersistence = false;
        messageFormat = null;
        message = null;
        arguments = null;
        because = null;
        estimatedHeapSize = -1;
    }

    public Level getLogLevel() {
        return logLevel;
    }

    public LogEntry setLogLevel(Level logLevel) {
        this.logLevel = logLevel;
        if (type == null) {
            type = LogMessageType.valueOf(logLevel.name());
        }
        this.hashCode = 0;
        return this;
    }

    public boolean isAlwaysLog() {
        return alwaysLog;
    }

    public LogEntry setAlwaysLog(boolean alwaysLog) {
        this.alwaysLog = alwaysLog;
        this.hashCode = 0;
        return this;
    }

    public long getEpochTime() {
        return epochTime;
    }

    public LogEntry setEpochTime(long epochTime) {
        this.epochTime = epochTime;
        this.timestamp = null;
        this.hashCode = 0;
        return this;
    }

    public String getTimestamp() {
        if (timestamp == null) {
            timestamp = LOG_DATE_FORMAT.format(epochTime);
        }
        return timestamp;
    }

    public LogMessageType getType() {
        return type;
    }

    public LogEntry setType(LogMessageType type) {
        this.type = type;
        this.hashCode = 0;
        return this;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public LogEntry setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
        return this;
    }

    public LogEntry setPort(Integer port) {
        this.port = port;
        return this;
    }

    public Integer getPort() {
        return port;
    }

    @JsonIgnore
    public RequestDefinition[] getHttpRequests() {
        if (httpRequests == null) {
            return EMPTY_REQUEST_DEFINITIONS;
        } else {
            return httpRequests;
        }
    }

    @JsonIgnore
    public RequestDefinition[] getHttpUpdatedRequests() {
        return getHttpUpdatedRequests(null);
    }

    /**
     * As {@link #getHttpUpdatedRequests()} but consulting {@code configuration} for
     * {@code redactSecretsInLog}, so redaction enabled on a {@link org.mockserver.configuration.Configuration}
     * instance (including via {@code PUT /mockserver/configuration}) applies to the dashboard and the
     * JSON log-message surface. Falls back to the static store when {@code configuration} is {@code null}.
     * <p>
     * NOTE the result is memoised on first call, so toggling redaction after an entry has already been
     * rendered does not retroactively change that entry.
     *
     * @param configuration the effective server configuration (may be {@code null})
     */
    @JsonIgnore
    public RequestDefinition[] getHttpUpdatedRequests(org.mockserver.configuration.Configuration configuration) {
        if (httpRequests == null) {
            return EMPTY_REQUEST_DEFINITIONS;
        } else if (httpUpdatedRequests == null) {
            org.mockserver.fixture.FixtureRedactor redactor = logRedactor(configuration);
            httpUpdatedRequests = Arrays
                .stream(httpRequests)
                .map(this::updateBody)
                .map(requestDefinition -> redactor == null ? requestDefinition : redactor.redactRequestDefinition(requestDefinition))
                .toArray(RequestDefinition[]::new);
            return httpUpdatedRequests;
        } else {
            return httpUpdatedRequests;
        }
    }

    /**
     * Like {@link #getHttpRequests()} but with sensitive headers / configured JSON body
     * fields masked when {@code mockserver.redactSecretsInLog} is enabled. Unlike
     * {@link #getHttpUpdatedRequests()} this does NOT apply body templating ({@code updateBody}),
     * so when redaction is off it returns the raw requests byte-for-byte unchanged — it is the
     * redaction-aware view for the {@code retrieveRecordedRequests} / export paths, which must
     * otherwise preserve the captured request exactly.
     */
    @JsonIgnore
    public RequestDefinition[] getRedactedHttpRequests() {
        return getRedactedHttpRequests(null);
    }

    /**
     * @param configuration the effective server configuration, consulted for {@code redactSecretsInLog}
     *                      (may be {@code null}, in which case the static store is used)
     * @see #getRedactedHttpRequests()
     */
    @JsonIgnore
    public RequestDefinition[] getRedactedHttpRequests(org.mockserver.configuration.Configuration configuration) {
        RequestDefinition[] requests = getHttpRequests();
        org.mockserver.fixture.FixtureRedactor redactor = logRedactor(configuration);
        if (redactor == null) {
            return requests;
        }
        return Arrays
            .stream(requests)
            .map(redactor::redactRequestDefinition)
            .toArray(RequestDefinition[]::new);
    }

    /**
     * Like {@link #getHttpRequest()} but with sensitive data masked when
     * {@code mockserver.redactSecretsInLog} is enabled; returns the raw request unchanged
     * when redaction is off. Used by the {@code retrieveRecordedRequestsAndResponses} path.
     */
    @JsonIgnore
    public RequestDefinition getRedactedHttpRequest() {
        return getRedactedHttpRequest(null);
    }

    /**
     * @param configuration the effective server configuration (may be {@code null})
     * @see #getRedactedHttpRequest()
     */
    @JsonIgnore
    public RequestDefinition getRedactedHttpRequest(org.mockserver.configuration.Configuration configuration) {
        RequestDefinition request = getHttpRequest();
        org.mockserver.fixture.FixtureRedactor redactor = logRedactor(configuration);
        if (redactor == null || request == null) {
            return request;
        }
        return redactor.redactRequestDefinition(request);
    }

    /**
     * Like {@link #getHttpResponse()} but with sensitive data masked when
     * {@code mockserver.redactSecretsInLog} is enabled; returns the raw response unchanged
     * when redaction is off. Used by the {@code retrieveRecordedRequestsAndResponses} path.
     */
    @JsonIgnore
    public HttpResponse getRedactedHttpResponse() {
        return getRedactedHttpResponse(null);
    }

    /**
     * @param configuration the effective server configuration (may be {@code null})
     * @see #getRedactedHttpResponse()
     */
    @JsonIgnore
    public HttpResponse getRedactedHttpResponse(org.mockserver.configuration.Configuration configuration) {
        HttpResponse response = getHttpResponse();
        org.mockserver.fixture.FixtureRedactor redactor = logRedactor(configuration);
        if (redactor == null || response == null) {
            return response;
        }
        return redactor.redactResponseObject(response);
    }

    @JsonIgnore
    public boolean matches(HttpRequestMatcher matcher) {
        if (matcher == null) {
            return true;
        }
        if (httpRequests == null || httpRequests.length == 0) {
            return true;
        }
        for (RequestDefinition httpRequest : httpRequests) {
            RequestDefinition request = httpRequest.cloneWithLogCorrelationId();
            if (matcher.matches(type == LogMessageType.RECEIVED_REQUEST ? new MatchDifference(false, request) : null, request)) {
                return true;
            }
        }
        return false;
    }

    @JsonIgnore
    public LogEntry setHttpRequests(RequestDefinition[] httpRequests) {
        this.httpRequests = httpRequests;
        this.httpUpdatedRequests = null;
        this.derivedSyntheticExpectation = null;
        this.hashCode = 0;
        this.estimatedHeapSize = -1;
        return this;
    }

    public RequestDefinition getHttpRequest() {
        if (httpRequests != null && httpRequests.length > 0) {
            return httpRequests[0];
        } else {
            return null;
        }
    }

    @JsonIgnore
    public LogEntry setHttpRequest(RequestDefinition httpRequest) {
        if (httpRequest != null) {
            if (isNotBlank(httpRequest.getLogCorrelationId())) {
                setCorrelationId(httpRequest.getLogCorrelationId());
            }
            this.httpRequests = new RequestDefinition[]{httpRequest};
        } else {
            this.httpRequests = DEFAULT_REQUESTS_DEFINITIONS;
        }
        this.httpUpdatedRequests = null;
        this.derivedSyntheticExpectation = null;
        this.hashCode = 0;
        this.estimatedHeapSize = -1;
        return this;
    }

    public HttpResponse getHttpResponse() {
        return httpResponse;
    }

    public HttpResponse getHttpUpdatedResponse() {
        return getHttpUpdatedResponse(null);
    }

    /**
     * As {@link #getHttpUpdatedResponse()} but consulting {@code configuration} for
     * {@code redactSecretsInLog}. Memoised on first call, as above.
     *
     * @param configuration the effective server configuration (may be {@code null})
     */
    public HttpResponse getHttpUpdatedResponse(org.mockserver.configuration.Configuration configuration) {
        if (httpResponse == null) {
            return null;
        } else if (httpUpdatedResponse == null) {
            HttpResponse updated = updateBody(httpResponse);
            org.mockserver.fixture.FixtureRedactor redactor = logRedactor(configuration);
            httpUpdatedResponse = redactor == null ? updated : redactor.redactResponseObject(updated);
            return httpUpdatedResponse;
        } else {
            return httpUpdatedResponse;
        }
    }

    @JsonIgnore
    public LogEntry setHttpResponse(HttpResponse httpResponse) {
        this.httpResponse = httpResponse;
        this.httpUpdatedResponse = null;
        this.derivedSyntheticExpectation = null;
        this.hashCode = 0;
        this.estimatedHeapSize = -1;
        return this;
    }

    public HttpError getHttpError() {
        return httpError;
    }

    @JsonIgnore
    public LogEntry setHttpError(HttpError httpError) {
        this.httpError = httpError;
        this.hashCode = 0;
        return this;
    }

    public Expectation getExpectation() {
        if (expectation != null) {
            return expectation;
        }
        if (expectationIsSynthetic) {
            Expectation derived = derivedSyntheticExpectation;
            if (derived == null) {
                // Build fully, THEN publish via the volatile write so any concurrent reader that observes
                // a non-null reference also observes the fully-constructed referent. Two threads racing may
                // each build one; each returns its own safely-published instance (benign - see field).
                derived = new Expectation(getHttpRequest(), Times.once(), TimeToLive.unlimited(), 0).thenRespond(httpResponse);
                derivedSyntheticExpectation = derived;
            }
            return derived;
        }
        return null;
    }

    @JsonIgnore
    public LogEntry setExpectation(Expectation expectation) {
        this.expectation = expectation;
        this.expectationIsSynthetic = false;
        this.derivedSyntheticExpectation = null;
        this.hashCode = 0;
        return this;
    }

    /**
     * Records that this entry carries the synthetic expectation historically built as
     * {@code new Expectation(httpRequest, once, unlimited, 0).thenRespond(httpResponse)}. The object is no
     * longer allocated or retained here (that added a per-request Expectation + Timing graph to every log
     * entry); it is derived on demand in {@link #getExpectation()} from the entry's own request/response,
     * which every caller sets to these same instances via {@code setHttpRequest}/{@code setHttpResponse}
     * immediately before this call. The arguments are therefore only a marker of intent - the derivation
     * reads the fields - so callers must set the request/response before calling this.
     */
    @JsonIgnore
    public LogEntry setExpectation(RequestDefinition httpRequest, HttpResponse httpResponse) {
        this.expectation = null;
        this.expectationIsSynthetic = true;
        this.derivedSyntheticExpectation = null;
        this.hashCode = 0;
        return this;
    }

    private LogEntry copyExpectationFrom(LogEntry source) {
        this.expectation = source.expectation;
        this.expectationIsSynthetic = source.expectationIsSynthetic;
        this.derivedSyntheticExpectation = source.derivedSyntheticExpectation;
        this.hashCode = 0;
        return this;
    }

    public String getExpectationId() {
        return expectationId;
    }

    public LogEntry setExpectationId(String expectationId) {
        this.expectationId = expectationId;
        this.hashCode = 0;
        return this;
    }

    public boolean matchesAnyExpectationId(List<String> expectationIds) {
        if (expectationIds != null && isNotBlank(this.expectationId)) {
            return expectationIds.contains(this.expectationId);
        }
        return false;
    }

    public Throwable getThrowable() {
        return throwable;
    }

    @JsonIgnore
    public LogEntry setThrowable(Throwable throwable) {
        this.throwable = throwable;
        if (isBlank(messageFormat) && throwable != null) {
            messageFormat = throwable.getClass().getSimpleName();
            this.message = null;
            this.hashCode = 0;
        }
        return this;
    }

    public Runnable getConsumer() {
        return consumer;
    }

    @JsonIgnore
    public LogEntry setConsumer(Runnable consumer) {
        this.consumer = consumer;
        this.hashCode = 0;
        return this;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public LogEntry setDeleted(boolean deleted) {
        this.deleted = deleted;
        this.hashCode = 0;
        return this;
    }

    @JsonIgnore
    public boolean isSkipRecordedRequestPersistence() {
        return skipRecordedRequestPersistence;
    }

    public LogEntry setSkipRecordedRequestPersistence(boolean skipRecordedRequestPersistence) {
        this.skipRecordedRequestPersistence = skipRecordedRequestPersistence;
        return this;
    }

    public String getMessageFormat() {
        return messageFormat;
    }

    public LogEntry setMessageFormat(String messageFormat) {
        if (isBlank(messageFormat) && throwable != null) {
            this.messageFormat = throwable.getClass().getSimpleName();
        } else {
            this.messageFormat = messageFormat;
        }
        this.message = null;
        this.hashCode = 0;
        return this;
    }

    @JsonIgnore
    public String getMessage() {
        if (message == null) {
            if (arguments != null) {
                message = formatLogMessage(messageFormat, getArguments());
            } else {
                message = messageFormat;
            }
        }
        return message;
    }

    @JsonIgnore
    public String getCompactMessage() {
        if (arguments != null) {
            return formatCompactLogMessage(messageFormat, arguments);
        } else {
            return messageFormat;
        }
    }

    /**
     * The log-message arguments in their <em>rendered</em> form: any {@link HttpRequest}/{@link HttpResponse}
     * argument has its body converted to a {@link LogEntryBody} (a parsed {@link com.fasterxml.jackson.databind.JsonNode}
     * for a {@link JsonBody}, else the stringified body) so both the JSON log surface ({@link org.mockserver.serialization.serializers.log.LogEntrySerializer})
     * and the rendered message text ({@link #getMessage()}) reproduce exactly the same output.
     * <p>
     * The conversion is performed <strong>transiently here at read/render time</strong> and is NOT retained: the entry
     * stores the arguments in their raw form ({@link #arguments}, sharing the already-retained primary request/response
     * bodies), so a logged entry sitting in the event-log deque no longer pins a second, ~5x larger parsed
     * {@code JsonNode}/{@code LinkedHashMap} tree per JSON body for its whole lifetime. Each call rebuilds the converted
     * array and the caller is expected to discard it once it has produced its output (the serializer writes it and drops
     * it; {@link #getMessage()} memoises the resulting string and drops the tree).
     */
    public Object[] getArguments() {
        if (arguments == null) {
            return null;
        }
        return Arrays
            .stream(arguments)
            .map(argument -> {
                if (argument instanceof HttpRequest) {
                    return updateBody((HttpRequest) argument);
                } else if (argument instanceof HttpResponse) {
                    return updateBody((HttpResponse) argument);
                } else {
                    return argument;
                }
            })
            .toArray(Object[]::new);
    }

    /**
     * The arguments exactly as stored — the raw {@link HttpRequest}/{@link HttpResponse} references (bodies unparsed)
     * with only {@code null} normalised to {@code ""}. Used by {@link #clone()} and {@link #translateTo} so copying an
     * entry (including the Disruptor ring-buffer copy that populates the retained event-log entry) carries the raw form
     * and never materialises a parsed body tree onto the retained copy. Also the basis of {@link #equals(Object)} /
     * {@link #hashCode()}.
     */
    @JsonIgnore
    Object[] getRawArguments() {
        return arguments;
    }

    public LogEntry setArguments(Object... arguments) {
        if (arguments != null) {
            this.arguments = Arrays
                .stream(arguments)
                .map(argument -> argument == null ? "" : argument)
                .toArray(Object[]::new);
        } else {
            this.arguments = null;
        }
        this.message = null;
        this.hashCode = 0;
        return this;
    }

    public String getBecause() {
        return because;
    }

    public LogEntry setBecause(String because) {
        this.because = because;
        return this;
    }

    /**
     * Build the redactor applied to the displayed/retrieved copies of the request and
     * response when {@code mockserver.redactSecretsInLog} is enabled, or {@code null}
     * when redaction is off (the default) so the log is byte-for-byte unchanged.
     * <p>
     * Sensitive headers are the {@link org.mockserver.fixture.FixtureRedactor} defaults
     * (Authorization, Proxy-Authorization, Cookie, Set-Cookie, x-api-key, api-key); JSON
     * body fields named in {@code mockserver.fixtureBodyRedactFields} are masked too. The
     * redactor only ever operates on clones, so the live log entry is never mutated and
     * matching/verification (which read the unredacted request) are unaffected.
     */
    private static org.mockserver.fixture.FixtureRedactor logRedactor() {
        return logRedactor(null);
    }

    /**
     * As {@link #logRedactor()} but preferring the {@code redactSecretsInLog} value carried on the
     * supplied {@link org.mockserver.configuration.Configuration} instance, falling back to the
     * static store when {@code configuration} is {@code null}. This is what makes redaction enabled
     * programmatically or via {@code PUT /mockserver/configuration} actually take effect.
     * <p>
     * {@code fixtureBodyRedactFields} is resolved the same way — instance first, static store as the
     * fallback — so the set of masked body fields is settable over the REST config API too.
     */
    private static org.mockserver.fixture.FixtureRedactor logRedactor(org.mockserver.configuration.Configuration configuration) {
        boolean redact = configuration != null
            ? configuration.redactSecretsInLog()
            : org.mockserver.configuration.ConfigurationProperties.redactSecretsInLog();
        if (!redact) {
            return null;
        }
        String bodyFields = configuration != null
            ? configuration.fixtureBodyRedactFields()
            : org.mockserver.configuration.ConfigurationProperties.fixtureBodyRedactFields();
        List<String> bodyFieldList = isBlank(bodyFields)
            ? Collections.emptyList()
            : Arrays.asList(bodyFields.split(","));
        return new org.mockserver.fixture.FixtureRedactor(
            org.mockserver.fixture.FixtureRedactor.defaultSensitiveHeaders(),
            bodyFieldList
        );
    }

    private RequestDefinition updateBody(RequestDefinition requestDefinition) {
        if (requestDefinition instanceof HttpRequest) {
            HttpRequest httpRequest = (HttpRequest) requestDefinition;
            Body<?> body = httpRequest.getBody();
            if (body instanceof JsonBody) {
                try {
                    return httpRequest
                        .shallowClone()
                        .withBody(
                            new LogEntryBody(OBJECT_MAPPER.readTree(body.toString()))
                        );
                } catch (Throwable throwable) {
                    return httpRequest
                        .shallowClone()
                        .withBody(
                            new LogEntryBody(body.toString())
                        );
                }
            } else if (body instanceof ParameterBody) {
                return httpRequest
                    .shallowClone()
                    .withBody(
                        new LogEntryBody(body.toString())
                    );
            } else if (body instanceof BodyWithContentType && !(body instanceof LogEntryBody)) {
                return httpRequest
                    .shallowClone()
                    .withBody(
                        new LogEntryBody(body.toString())
                    );
            } else {
                return httpRequest;
            }
        } else {
            return null;
        }
    }

    private HttpResponse updateBody(HttpResponse httpResponse) {
        if (httpResponse != null) {
            Body<?> body = httpResponse.getBody();
            if (body != null && JsonBody.class.isAssignableFrom(body.getClass())) {
                try {
                    return httpResponse
                        .shallowClone()
                        .withBody(
                            new LogEntryBody(OBJECT_MAPPER.readTree(body.toString()))
                        );
                } catch (Throwable throwable) {
                    return httpResponse
                        .shallowClone()
                        .withBody(
                            new LogEntryBody(body.toString())
                        );
                }
            } else if (body != null && !(body instanceof LogEntryBody)) {
                return httpResponse
                    .shallowClone()
                    .withBody(
                        new LogEntryBody(body.toString())
                    );
            } else {
                return httpResponse;
            }
        } else {
            return null;
        }
    }

    public LogEntry cloneAndClear() {
        LogEntry clone = this.clone();
        clear();
        return clone;
    }

    @SuppressWarnings("MethodDoesntCallSuperMethod")
    public LogEntry clone() {
        return new LogEntry()
            .setId(id())
            .setType(getType())
            .setLogLevel(getLogLevel())
            .setAlwaysLog(isAlwaysLog())
            .setEpochTime(getEpochTime())
            .setCorrelationId(getCorrelationId())
            .setPort(getPort())
            .setHttpRequests(getHttpRequests())
            .setHttpResponse(getHttpResponse())
            .setHttpError(getHttpError())
            .copyExpectationFrom(this)
            .setExpectationId(getExpectationId())
            .setMessageFormat(getMessageFormat())
            .setArguments(getRawArguments())
            .setBecause(getBecause())
            .setThrowable(getThrowable())
            .setConsumer(getConsumer())
            .setDeleted(isDeleted())
            .setSkipRecordedRequestPersistence(isSkipRecordedRequestPersistence());
    }

    @Override
    public void translateTo(LogEntry event, long sequence) {
        event
            .setId(id())
            .setType(getType())
            .setLogLevel(getLogLevel())
            .setAlwaysLog(isAlwaysLog())
            .setEpochTime(getEpochTime())
            .setCorrelationId(getCorrelationId())
            .setPort(getPort())
            .setHttpRequests(getHttpRequests())
            .setHttpResponse(getHttpResponse())
            .setHttpError(getHttpError())
            .copyExpectationFrom(this)
            .setExpectationId(getExpectationId())
            .setMessageFormat(getMessageFormat())
            .setArguments(getRawArguments())
            .setBecause(getBecause())
            .setThrowable(getThrowable())
            .setConsumer(getConsumer())
            .setDeleted(isDeleted())
            .setSkipRecordedRequestPersistence(isSkipRecordedRequestPersistence());
        clear();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (hashCode() != o.hashCode()) {
            return false;
        }
        LogEntry logEntry = (LogEntry) o;
        return epochTime == logEntry.epochTime &&
            deleted == logEntry.deleted &&
            type == logEntry.type &&
            logLevel == logEntry.logLevel &&
            alwaysLog == logEntry.alwaysLog &&
            Objects.equals(messageFormat, logEntry.messageFormat) &&
            Objects.equals(httpResponse, logEntry.httpResponse) &&
            Objects.equals(httpError, logEntry.httpError) &&
            Objects.equals(expectation, logEntry.expectation) &&
            expectationIsSynthetic == logEntry.expectationIsSynthetic &&
            Objects.equals(expectationId, logEntry.expectationId) &&
            Objects.equals(consumer, logEntry.consumer) &&
            Arrays.equals(arguments, logEntry.arguments) &&
            Arrays.equals(httpRequests, logEntry.httpRequests);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            int result = Objects.hash(epochTime, deleted, type, logLevel, alwaysLog, messageFormat, httpResponse, httpError, expectation, expectationIsSynthetic, expectationId, consumer);
            result = 31 * result + Arrays.hashCode(arguments);
            result = 31 * result + Arrays.hashCode(httpRequests);
            hashCode = result;
        }
        return hashCode;
    }

    @Override
    public String toString() {
        try {
            return ObjectMapperFactory
                .createObjectMapper(true, false)
                .writeValueAsString(this);
        } catch (Exception e) {
            return super.toString();
        }
    }

    public enum LogMessageType {
        RUNNABLE,
        TRACE,
        DEBUG,
        INFO,
        WARN,
        ERROR,
        EXCEPTION,
        CLEARED,
        RETRIEVED,
        UPDATED_EXPECTATION,
        CREATED_EXPECTATION,
        REMOVED_EXPECTATION,
        RECEIVED_REQUEST,
        EXPECTATION_RESPONSE,
        EXPECTATION_MATCHED,
        EXPECTATION_NOT_MATCHED,
        NO_MATCH_RESPONSE,
        VERIFICATION,
        VERIFICATION_FAILED,
        VERIFICATION_PASSED,
        FORWARDED_REQUEST,
        OPENAPI_REQUEST_VALIDATION_FAILED,
        OPENAPI_RESPONSE_VALIDATION_FAILED,
        TEMPLATE_GENERATED,
        SERVER_CONFIGURATION,
        AUTHENTICATION_FAILED,
        // A matched response/forward template rendered output that could not be turned into a valid
        // HttpResponse/HttpRequest (failed JSON-schema validation, or threw while transforming). The
        // action then degrades to a 404 fallback; this distinct type makes that failure visible and
        // request-correlated instead of being buried as a generic ERROR while the 404 is logged as a
        // success-looking EXPECTATION_RESPONSE. Counterpart to TEMPLATE_GENERATED.
        TEMPLATE_GENERATION_FAILED,
    }

    public enum LogMessageTypeCategory {
        MATCHING(LogMessageType.EXPECTATION_MATCHED, LogMessageType.EXPECTATION_NOT_MATCHED, LogMessageType.NO_MATCH_RESPONSE),
        REQUEST_LIFECYCLE(LogMessageType.RECEIVED_REQUEST, LogMessageType.FORWARDED_REQUEST, LogMessageType.EXPECTATION_RESPONSE, LogMessageType.TEMPLATE_GENERATED, LogMessageType.TEMPLATE_GENERATION_FAILED),
        EXPECTATION_MANAGEMENT(LogMessageType.CREATED_EXPECTATION, LogMessageType.UPDATED_EXPECTATION, LogMessageType.REMOVED_EXPECTATION, LogMessageType.CLEARED),
        VERIFICATION(LogMessageType.VERIFICATION, LogMessageType.VERIFICATION_FAILED, LogMessageType.VERIFICATION_PASSED, LogMessageType.RETRIEVED),
        SERVER(LogMessageType.SERVER_CONFIGURATION, LogMessageType.AUTHENTICATION_FAILED, LogMessageType.OPENAPI_REQUEST_VALIDATION_FAILED, LogMessageType.OPENAPI_RESPONSE_VALIDATION_FAILED),
        GENERAL(LogMessageType.TRACE, LogMessageType.DEBUG, LogMessageType.INFO, LogMessageType.WARN, LogMessageType.ERROR, LogMessageType.EXCEPTION);

        private static final Map<LogMessageType, LogMessageTypeCategory> TYPE_TO_CATEGORY = new EnumMap<>(LogMessageType.class);

        static {
            for (LogMessageTypeCategory category : values()) {
                for (LogMessageType type : category.types) {
                    TYPE_TO_CATEGORY.put(type, category);
                }
            }
        }

        private final LogMessageType[] types;

        LogMessageTypeCategory(LogMessageType... types) {
            this.types = types;
        }

        public static LogMessageTypeCategory categoryFor(LogMessageType type) {
            return TYPE_TO_CATEGORY.get(type);
        }

        private static final Map<String, Level> VALID_LEVELS = new HashMap<>();

        static {
            for (Level level : Level.values()) {
                VALID_LEVELS.put(level.name(), level);
            }
        }

        /**
         * Immutable holder pairing the raw overrides map with its normalized form, referenced by a
         * single volatile field so both halves are always read and published together. Storing the
         * two halves in separate volatile fields allowed a reader to pair a freshly published {@code raw}
         * with a stale (or null) {@code normalized}, because the two volatile writes were independent.
         */
        private static final class OverrideCacheEntry {
            private final Map<String, String> raw;
            private final Map<String, Level> normalized;

            private OverrideCacheEntry(Map<String, String> raw, Map<String, Level> normalized) {
                this.raw = raw;
                this.normalized = normalized;
            }
        }

        private static volatile OverrideCacheEntry cachedOverrides;

        private static Map<String, Level> normalizeOverrides(Map<String, String> overrides) {
            OverrideCacheEntry cached = cachedOverrides;
            if (cached != null && cached.raw == overrides) {
                return cached.normalized;
            }
            Map<String, Level> normalized = new HashMap<>();
            for (Map.Entry<String, String> entry : overrides.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                String key = entry.getKey().toUpperCase(Locale.ROOT);
                Level level = VALID_LEVELS.get(entry.getValue().toUpperCase(Locale.ROOT));
                if (level != null) {
                    normalized.put(key, level);
                }
            }
            cachedOverrides = new OverrideCacheEntry(overrides, normalized);
            return normalized;
        }

        public static Level resolveEffectiveLevel(LogMessageType type, Map<String, String> overrides, Level globalLevel) {
            if (overrides == null || overrides.isEmpty()) {
                return globalLevel;
            }
            Map<String, Level> normalized = normalizeOverrides(overrides);
            if (normalized.isEmpty()) {
                return globalLevel;
            }
            if (type != null) {
                Level typeLevel = normalized.get(type.name());
                if (typeLevel != null) {
                    return typeLevel;
                }
            }
            LogMessageTypeCategory category = type != null ? categoryFor(type) : null;
            if (category != null) {
                Level categoryLevel = normalized.get(category.name());
                if (categoryLevel != null) {
                    return categoryLevel;
                }
            }
            return globalLevel;
        }
    }

}
