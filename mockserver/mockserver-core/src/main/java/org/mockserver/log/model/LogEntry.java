package org.mockserver.log.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private Expectation expectation;
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
     * Memoized estimate of the heap retained by the stable primary request/response bodies of this
     * entry, used as the weight by the byte-budget eviction in {@link org.mockserver.collections.CircularConcurrentLinkedDeque}.
     * Lazily computed once and cached so the weight is identical at add-time and at evict-time (the
     * deque recomputes the weight when it evicts). {@code -1} means "not yet computed".
     */
    private transient long estimatedHeapSize = -1;

    public LogEntry() {

    }

    /**
     * Stable lower-bound estimate of the bytes this entry retains on the heap, counting only the
     * primary request/response body bytes (the dominant cost for large LLM-capture exchanges). It
     * deliberately ignores the lazily-derived {@code httpUpdated*} copies and the {@code arguments}
     * array so the value never changes after the entry is built — the byte-budget eviction relies on
     * the add-time weight matching the evict-time weight exactly. Reads the {@code httpRequests} field
     * directly (not {@link #getHttpRequests()}, which substitutes a default request when unset).
     */
    @JsonIgnore
    public long estimatedHeapSize() {
        if (estimatedHeapSize < 0) {
            long size = 0;
            RequestDefinition[] reqs = this.httpRequests;
            if (reqs != null) {
                for (RequestDefinition rd : reqs) {
                    if (rd instanceof HttpRequest) {
                        byte[] b = ((HttpRequest) rd).getBodyAsRawBytes();
                        if (b != null) {
                            size += b.length;
                        }
                    }
                }
            }
            if (httpResponse != null) {
                byte[] b = httpResponse.getBodyAsRawBytes();
                if (b != null) {
                    size += b.length;
                }
            }
            estimatedHeapSize = size;
        }
        return estimatedHeapSize;
    }

    private LogEntry setId(String id) {
        this.id = id;
        return this;
    }

    @JsonIgnore
    public String id() {
        if (id == null) {
            id = UUIDService.getUUID();
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
        if (httpRequests == null) {
            return EMPTY_REQUEST_DEFINITIONS;
        } else if (httpUpdatedRequests == null) {
            org.mockserver.fixture.FixtureRedactor redactor = logRedactor();
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
        RequestDefinition[] requests = getHttpRequests();
        org.mockserver.fixture.FixtureRedactor redactor = logRedactor();
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
        RequestDefinition request = getHttpRequest();
        org.mockserver.fixture.FixtureRedactor redactor = logRedactor();
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
        HttpResponse response = getHttpResponse();
        org.mockserver.fixture.FixtureRedactor redactor = logRedactor();
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
        this.hashCode = 0;
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
        this.hashCode = 0;
        return this;
    }

    public HttpResponse getHttpResponse() {
        return httpResponse;
    }

    public HttpResponse getHttpUpdatedResponse() {
        if (httpResponse == null) {
            return null;
        } else if (httpUpdatedResponse == null) {
            HttpResponse updated = updateBody(httpResponse);
            org.mockserver.fixture.FixtureRedactor redactor = logRedactor();
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
        this.hashCode = 0;
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
        return expectation;
    }

    @JsonIgnore
    public LogEntry setExpectation(Expectation expectation) {
        this.expectation = expectation;
        this.hashCode = 0;
        return this;
    }

    @JsonIgnore
    public LogEntry setExpectation(RequestDefinition httpRequest, HttpResponse httpResponse) {
        this.expectation = new Expectation(httpRequest, Times.once(), TimeToLive.unlimited(), 0).thenRespond(httpResponse);
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
                message = formatLogMessage(messageFormat, arguments);
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

    public Object[] getArguments() {
        return arguments;
    }

    public LogEntry setArguments(Object... arguments) {
        if (arguments != null) {
            this.arguments = Arrays
                .stream(arguments)
                .map(argument -> {
                    if (argument instanceof HttpRequest) {
                        return updateBody((HttpRequest) argument);
                    } else if (argument instanceof HttpResponse) {
                        return updateBody((HttpResponse) argument);
                    } else if (argument == null) {
                        return "";
                    } else {
                        return argument;
                    }
                })
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
        if (!org.mockserver.configuration.ConfigurationProperties.redactSecretsInLog()) {
            return null;
        }
        String bodyFields = org.mockserver.configuration.ConfigurationProperties.fixtureBodyRedactFields();
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
            .setExpectation(getExpectation())
            .setExpectationId(getExpectationId())
            .setMessageFormat(getMessageFormat())
            .setArguments(getArguments())
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
            .setExpectation(getExpectation())
            .setExpectationId(getExpectationId())
            .setMessageFormat(getMessageFormat())
            .setArguments(getArguments())
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
            Objects.equals(expectationId, logEntry.expectationId) &&
            Objects.equals(consumer, logEntry.consumer) &&
            Arrays.equals(arguments, logEntry.arguments) &&
            Arrays.equals(httpRequests, logEntry.httpRequests);
    }

    @Override
    public int hashCode() {
        if (hashCode == 0) {
            int result = Objects.hash(epochTime, deleted, type, logLevel, alwaysLog, messageFormat, httpResponse, httpError, expectation, expectationId, consumer);
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
    }

    public enum LogMessageTypeCategory {
        MATCHING(LogMessageType.EXPECTATION_MATCHED, LogMessageType.EXPECTATION_NOT_MATCHED, LogMessageType.NO_MATCH_RESPONSE),
        REQUEST_LIFECYCLE(LogMessageType.RECEIVED_REQUEST, LogMessageType.FORWARDED_REQUEST, LogMessageType.EXPECTATION_RESPONSE, LogMessageType.TEMPLATE_GENERATED),
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
