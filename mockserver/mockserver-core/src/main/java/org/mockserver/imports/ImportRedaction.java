package org.mockserver.imports;

import org.mockserver.fixture.FixtureRedactor;
import org.mockserver.matchers.TimeToLive;
import org.mockserver.matchers.Times;
import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpRequestAndHttpResponse;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.RequestDefinition;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Applies {@link FixtureRedactor} masking to expectations produced by the import
 * pipeline ({@link HarImporter}, {@link PostmanCollectionImporter}) before they
 * are persisted, so real credentials in captured traffic never land in the
 * expectation store.
 *
 * <p>Redaction is <strong>on by default</strong>. Imported requests and responses
 * have sensitive headers ({@code Authorization}, {@code X-Api-Key}, {@code Cookie},
 * {@code Set-Cookie}, {@code Proxy-Authorization}, etc. — see
 * {@link FixtureRedactor#defaultSensitiveHeaders()}) and well-known sensitive JSON
 * body fields replaced with {@link FixtureRedactor#REDACTED_PLACEHOLDER}. The
 * placeholder keeps the expectation structurally intact so it still matches.
 *
 * <p>Callers can disable redaction or extend the sensitive-key list via
 * {@link Options}. The expectation {@code id} assigned by the importer is preserved
 * across redaction (the underlying {@link FixtureRedactor} rebuilds expectations
 * without copying the id).
 */
public class ImportRedaction {

    /**
     * Default JSON body field names redacted on import (case-insensitive, matched
     * at any depth). Covers the common secret-bearing field names seen in captured
     * API traffic.
     */
    public static final Set<String> DEFAULT_SENSITIVE_BODY_FIELDS = Set.of(
        "api_key", "apiKey", "apikey",
        "access_token", "accessToken",
        "refresh_token", "refreshToken",
        "client_secret", "clientSecret",
        "password", "passwd", "pwd",
        "secret", "token", "authorization"
    );

    /**
     * Redaction options for an import. Defaults to redaction enabled with the
     * built-in sensitive header and body-field lists.
     */
    public static class Options {

        private boolean enabled = true;
        private final Set<String> additionalSensitiveHeaders = new LinkedHashSet<>();
        private final Set<String> additionalSensitiveBodyFields = new LinkedHashSet<>();

        /**
         * @return options with redaction enabled and the default sensitive lists
         */
        public static Options enabled() {
            return new Options();
        }

        /**
         * @return options with redaction disabled (imported values are kept verbatim)
         */
        public static Options disabled() {
            return new Options().withEnabled(false);
        }

        public Options withEnabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public boolean isEnabled() {
            return enabled;
        }

        /**
         * Add header names to redact on top of {@link FixtureRedactor#defaultSensitiveHeaders()}.
         */
        public Options withAdditionalSensitiveHeaders(Collection<String> headerNames) {
            if (headerNames != null) {
                this.additionalSensitiveHeaders.addAll(headerNames);
            }
            return this;
        }

        /**
         * Add JSON body field names to redact on top of {@link #DEFAULT_SENSITIVE_BODY_FIELDS}.
         */
        public Options withAdditionalSensitiveBodyFields(Collection<String> bodyFields) {
            if (bodyFields != null) {
                this.additionalSensitiveBodyFields.addAll(bodyFields);
            }
            return this;
        }

        Set<String> additionalSensitiveHeaders() {
            return additionalSensitiveHeaders;
        }

        Set<String> additionalSensitiveBodyFields() {
            return additionalSensitiveBodyFields;
        }
    }

    /**
     * Redact the supplied expectations according to {@code options}. When
     * redaction is disabled the input list is returned unchanged; otherwise a new
     * list of redacted expectations (preserving each expectation's id) is returned.
     *
     * @param expectations expectations produced by an importer (may be empty)
     * @param options      redaction options; defaults to enabled if {@code null}
     * @return the redacted (or original, when disabled) expectations
     */
    public static List<Expectation> redact(List<Expectation> expectations, Options options) {
        if (expectations == null || expectations.isEmpty()) {
            return expectations;
        }
        Options effectiveOptions = options != null ? options : Options.enabled();
        if (!effectiveOptions.isEnabled()) {
            return expectations;
        }

        Set<String> sensitiveHeaders = new LinkedHashSet<>(FixtureRedactor.defaultSensitiveHeaders());
        sensitiveHeaders.addAll(effectiveOptions.additionalSensitiveHeaders());

        Set<String> sensitiveBodyFields = new LinkedHashSet<>(DEFAULT_SENSITIVE_BODY_FIELDS);
        sensitiveBodyFields.addAll(effectiveOptions.additionalSensitiveBodyFields());

        FixtureRedactor redactor = new FixtureRedactor(sensitiveHeaders, sensitiveBodyFields);

        Expectation[] redacted = redactor.redact(expectations.toArray(new Expectation[0]));

        // FixtureRedactor rebuilds expectations without copying the id; restore the
        // importer-assigned ids so persisted expectations remain stable/upsertable.
        List<Expectation> result = new ArrayList<>(redacted.length);
        for (int i = 0; i < redacted.length; i++) {
            Expectation original = expectations.get(i);
            Expectation masked = redacted[i];
            if (original.getId() != null) {
                masked.withId(original.getId());
            }
            result.add(masked);
        }
        return result;
    }

    /**
     * Redact expectations produced by a migration importer ({@link WireMockImporter},
     * {@link MountebankImporter}, {@link MockoonImporter}) <strong>while preserving the action
     * type and matching constraints</strong>.
     *
     * <p>{@link #redact(List, Options)} delegates to {@link FixtureRedactor#redact(Expectation[])},
     * which only carries over response actions and rebuilds each expectation with default
     * {@code Times}/{@code TimeToLive} — fine for HAR/Postman (single static responses) but lossy
     * for the migration importers, which also emit {@code httpForward} (proxy), {@code httpError}
     * (fault), sequential/random multi-responses and {@code Times} constraints ({@code repeat}).
     * This method redacts the request and each response individually (via the granular
     * {@link FixtureRedactor#redactRequestDefinition}/{@link FixtureRedactor#redactResponseObject}
     * clones) and re-attaches them to a rebuilt expectation that keeps the original action type,
     * {@code Times}, {@code TimeToLive}, priority, id, scenario state and response mode.
     *
     * @param expectations expectations produced by a migration importer (may be empty)
     * @param options      redaction options; defaults to enabled if {@code null}
     * @return the redacted (or original, when disabled) expectations
     */
    public static List<Expectation> redactPreservingActions(List<Expectation> expectations, Options options) {
        if (expectations == null || expectations.isEmpty()) {
            return expectations;
        }
        Options effectiveOptions = options != null ? options : Options.enabled();
        if (!effectiveOptions.isEnabled()) {
            return expectations;
        }

        Set<String> sensitiveHeaders = new LinkedHashSet<>(FixtureRedactor.defaultSensitiveHeaders());
        sensitiveHeaders.addAll(effectiveOptions.additionalSensitiveHeaders());
        Set<String> sensitiveBodyFields = new LinkedHashSet<>(DEFAULT_SENSITIVE_BODY_FIELDS);
        sensitiveBodyFields.addAll(effectiveOptions.additionalSensitiveBodyFields());
        FixtureRedactor redactor = new FixtureRedactor(sensitiveHeaders, sensitiveBodyFields);

        List<Expectation> result = new ArrayList<>(expectations.size());
        for (Expectation source : expectations) {
            result.add(redactPreservingAction(source, redactor));
        }
        return result;
    }

    private static Expectation redactPreservingAction(Expectation source, FixtureRedactor redactor) {
        RequestDefinition redactedRequest = redactor.redactRequestDefinition(source.getHttpRequest());

        Expectation result = new Expectation(
            redactedRequest,
            source.getTimes() != null ? source.getTimes() : Times.unlimited(),
            source.getTimeToLive() != null ? source.getTimeToLive() : TimeToLive.unlimited(),
            source.getPriority()
        );
        if (source.getId() != null) {
            result.withId(source.getId());
        }
        result
            .withScenarioName(source.getScenarioName())
            .withScenarioState(source.getScenarioState())
            .withNewScenarioState(source.getNewScenarioState());

        List<HttpResponse> responses = source.getHttpResponses();
        if (responses != null && !responses.isEmpty()) {
            List<HttpResponse> redactedResponses = new ArrayList<>(responses.size());
            for (HttpResponse response : responses) {
                redactedResponses.add(redactor.redactResponseObject(response));
            }
            result.thenRespond(redactedResponses);
            if (source.getResponseMode() != null) {
                result.withResponseMode(source.getResponseMode());
            }
        } else if (source.getHttpResponse() != null) {
            result.thenRespond(redactor.redactResponseObject(source.getHttpResponse()));
        } else if (source.getHttpForward() != null) {
            result.thenForward(source.getHttpForward());
        } else if (source.getHttpError() != null) {
            result.thenError(source.getHttpError());
        }
        return result;
    }

    /**
     * Redact recorded request/response pairs (loaded from a persisted NDJSON archive by
     * {@link RecordedTrafficImporter}) according to {@code options}, using the same sensitive-header
     * and body-field masking as {@link #redact(List, Options)}. When redaction is disabled the input
     * list is returned unchanged; otherwise a new list of redacted pairs is returned. This is a
     * defence-in-depth re-mask on the import side — the persist side already redacts on write when
     * {@code mockserver.redactSecretsInLog} is on, and re-masking an already-masked value is a no-op.
     *
     * @param pairs   recorded request/response pairs (may be null or empty)
     * @param options redaction options; defaults to enabled if {@code null}
     * @return the redacted (or original, when disabled) pairs
     */
    public static List<HttpRequestAndHttpResponse> redactRecordedTraffic(List<HttpRequestAndHttpResponse> pairs, Options options) {
        if (pairs == null || pairs.isEmpty()) {
            return pairs;
        }
        Options effectiveOptions = options != null ? options : Options.enabled();
        if (!effectiveOptions.isEnabled()) {
            return pairs;
        }

        Set<String> sensitiveHeaders = new LinkedHashSet<>(FixtureRedactor.defaultSensitiveHeaders());
        sensitiveHeaders.addAll(effectiveOptions.additionalSensitiveHeaders());

        Set<String> sensitiveBodyFields = new LinkedHashSet<>(DEFAULT_SENSITIVE_BODY_FIELDS);
        sensitiveBodyFields.addAll(effectiveOptions.additionalSensitiveBodyFields());

        FixtureRedactor redactor = new FixtureRedactor(sensitiveHeaders, sensitiveBodyFields);

        List<HttpRequestAndHttpResponse> result = new ArrayList<>(pairs.size());
        for (HttpRequestAndHttpResponse pair : pairs) {
            if (pair == null) {
                continue;
            }
            HttpRequestAndHttpResponse masked = new HttpRequestAndHttpResponse();
            RequestDefinition redactedRequest = redactor.redactRequestDefinition(pair.getHttpRequest());
            if (redactedRequest instanceof HttpRequest) {
                masked.withHttpRequest((HttpRequest) redactedRequest);
            } else {
                masked.withHttpRequest(pair.getHttpRequest());
            }
            masked.withHttpResponse(redactor.redactResponseObject(pair.getHttpResponse()));
            result.add(masked);
        }
        return result;
    }
}
