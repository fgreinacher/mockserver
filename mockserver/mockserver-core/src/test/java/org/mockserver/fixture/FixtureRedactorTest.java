package org.mockserver.fixture;

import org.junit.Test;
import org.mockserver.matchers.TimeToLive;
import org.mockserver.matchers.Times;
import org.mockserver.mock.Expectation;
import org.mockserver.model.*;

import java.util.Arrays;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockserver.fixture.FixtureRedactor.REDACTED_PLACEHOLDER;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.HttpSseResponse.sseResponse;
import static org.mockserver.model.SseEvent.sseEvent;

public class FixtureRedactorTest {

    private final FixtureRedactor redactor = new FixtureRedactor();

    private static HttpRequest requestOf(Expectation expectation) {
        return (HttpRequest) expectation.getHttpRequest();
    }

    // --- Request header redaction ---

    @Test
    public void shouldRedactAuthorizationHeader() {
        // given
        Expectation expectation = Expectation.when(
            request().withMethod("POST").withPath("/api")
                .withHeader("Authorization", "Bearer sk-secret-key-123")
                .withHeader("Content-Type", "application/json")
        ).thenRespond(response().withStatusCode(200));

        // when
        Expectation[] redacted = redactor.redact(new Expectation[]{expectation});

        // then
        assertThat(requestOf(redacted[0]).getFirstHeader("Authorization"), is(REDACTED_PLACEHOLDER));
        assertThat(requestOf(redacted[0]).getFirstHeader("Content-Type"), is("application/json"));
    }

    @Test
    public void shouldRedactXApiKeyHeader() {
        // given
        Expectation expectation = Expectation.when(
            request().withHeader("x-api-key", "my-secret-api-key")
        ).thenRespond(response().withStatusCode(200));

        // when
        Expectation[] redacted = redactor.redact(new Expectation[]{expectation});

        // then
        assertThat(requestOf(redacted[0]).getFirstHeader("x-api-key"), is(REDACTED_PLACEHOLDER));
    }

    @Test
    public void shouldRedactApiKeyHeader() {
        // given
        Expectation expectation = Expectation.when(
            request().withHeader("api-key", "secret")
        ).thenRespond(response().withStatusCode(200));

        // when
        Expectation[] redacted = redactor.redact(new Expectation[]{expectation});

        // then
        assertThat(requestOf(redacted[0]).getFirstHeader("api-key"), is(REDACTED_PLACEHOLDER));
    }

    @Test
    public void shouldRedactCookieHeader() {
        // given
        Expectation expectation = Expectation.when(
            request().withHeader("Cookie", "session=abc123")
        ).thenRespond(response().withStatusCode(200));

        // when
        Expectation[] redacted = redactor.redact(new Expectation[]{expectation});

        // then
        assertThat(requestOf(redacted[0]).getFirstHeader("Cookie"), is(REDACTED_PLACEHOLDER));
    }

    @Test
    public void shouldRedactProxyAuthorizationHeader() {
        // given
        Expectation expectation = Expectation.when(
            request().withHeader("Proxy-Authorization", "Basic dXNlcjpwYXNz")
        ).thenRespond(response().withStatusCode(200));

        // when
        Expectation[] redacted = redactor.redact(new Expectation[]{expectation});

        // then
        assertThat(requestOf(redacted[0]).getFirstHeader("Proxy-Authorization"), is(REDACTED_PLACEHOLDER));
    }

    // --- Response header redaction ---

    @Test
    public void shouldRedactSetCookieInResponse() {
        // given
        Expectation expectation = Expectation.when(
            request().withMethod("GET").withPath("/login")
        ).thenRespond(
            response().withStatusCode(200)
                .withHeader("Set-Cookie", "session=xyz789")
                .withHeader("Content-Type", "text/html")
        );

        // when
        Expectation[] redacted = redactor.redact(new Expectation[]{expectation});

        // then
        assertThat(redacted[0].getHttpResponse().getFirstHeader("Set-Cookie"), is(REDACTED_PLACEHOLDER));
        assertThat(redacted[0].getHttpResponse().getFirstHeader("Content-Type"), is("text/html"));
    }

    // --- Non-sensitive headers preserved ---

    @Test
    public void shouldPreserveNonSensitiveHeaders() {
        // given
        Expectation expectation = Expectation.when(
            request().withMethod("GET").withPath("/data")
                .withHeader("Accept", "application/json")
                .withHeader("User-Agent", "TestClient/1.0")
        ).thenRespond(
            response().withStatusCode(200)
                .withHeader("Content-Type", "application/json")
                .withHeader("X-Request-Id", "abc-123")
        );

        // when
        Expectation[] redacted = redactor.redact(new Expectation[]{expectation});

        // then
        assertThat(requestOf(redacted[0]).getFirstHeader("Accept"), is("application/json"));
        assertThat(requestOf(redacted[0]).getFirstHeader("User-Agent"), is("TestClient/1.0"));
        assertThat(redacted[0].getHttpResponse().getFirstHeader("Content-Type"), is("application/json"));
        assertThat(redacted[0].getHttpResponse().getFirstHeader("X-Request-Id"), is("abc-123"));
    }

    // --- Case-insensitive matching ---

    @Test
    public void shouldRedactHeadersCaseInsensitively() {
        // given
        Expectation expectation = Expectation.when(
            request().withHeader("AUTHORIZATION", "Bearer secret")
                .withHeader("X-API-KEY", "secret")
        ).thenRespond(response().withStatusCode(200));

        // when
        Expectation[] redacted = redactor.redact(new Expectation[]{expectation});

        // then
        assertThat(requestOf(redacted[0]).getFirstHeader("AUTHORIZATION"), is(REDACTED_PLACEHOLDER));
        assertThat(requestOf(redacted[0]).getFirstHeader("X-API-KEY"), is(REDACTED_PLACEHOLDER));
    }

    // --- Custom redaction list ---

    @Test
    public void shouldRedactCustomHeaders() {
        // given
        FixtureRedactor customRedactor = new FixtureRedactor(Arrays.asList("X-Custom-Secret", "X-Internal-Token"));
        Expectation expectation = Expectation.when(
            request()
                .withHeader("X-Custom-Secret", "secret-value")
                .withHeader("X-Internal-Token", "token-value")
                .withHeader("Authorization", "Bearer should-not-redact") // not in custom list
        ).thenRespond(response().withStatusCode(200));

        // when
        Expectation[] redacted = customRedactor.redact(new Expectation[]{expectation});

        // then
        assertThat(requestOf(redacted[0]).getFirstHeader("X-Custom-Secret"), is(REDACTED_PLACEHOLDER));
        assertThat(requestOf(redacted[0]).getFirstHeader("X-Internal-Token"), is(REDACTED_PLACEHOLDER));
        // Authorization is NOT in the custom list, so it should be preserved
        assertThat(requestOf(redacted[0]).getFirstHeader("Authorization"), is("Bearer should-not-redact"));
    }

    // --- Copies, not live entries ---

    @Test
    public void shouldNotMutateOriginalExpectation() {
        // given
        Expectation original = Expectation.when(
            request().withHeader("Authorization", "Bearer secret-key")
        ).thenRespond(response().withStatusCode(200).withHeader("Set-Cookie", "session=abc"));

        // when
        Expectation[] redacted = redactor.redact(new Expectation[]{original});

        // then -- originals unchanged
        assertThat(requestOf(original).getFirstHeader("Authorization"), is("Bearer secret-key"));
        assertThat(original.getHttpResponse().getFirstHeader("Set-Cookie"), is("session=abc"));

        // redacted copies changed
        assertThat(requestOf(redacted[0]).getFirstHeader("Authorization"), is(REDACTED_PLACEHOLDER));
        assertThat(redacted[0].getHttpResponse().getFirstHeader("Set-Cookie"), is(REDACTED_PLACEHOLDER));
    }

    // --- Multiple expectations ---

    @Test
    public void shouldRedactMultipleExpectations() {
        // given
        Expectation[] expectations = {
            Expectation.when(
                request().withHeader("Authorization", "Bearer key1")
            ).thenRespond(response().withStatusCode(200)),
            Expectation.when(
                request().withHeader("x-api-key", "key2")
            ).thenRespond(response().withStatusCode(201))
        };

        // when
        Expectation[] redacted = redactor.redact(expectations);

        // then
        assertThat(redacted.length, is(2));
        assertThat(requestOf(redacted[0]).getFirstHeader("Authorization"), is(REDACTED_PLACEHOLDER));
        assertThat(requestOf(redacted[1]).getFirstHeader("x-api-key"), is(REDACTED_PLACEHOLDER));
    }

    // --- Null/empty handling ---

    @Test
    public void shouldHandleNullInput() {
        // when
        Expectation[] redacted = redactor.redact(null);

        // then
        assertThat(redacted.length, is(0));
    }

    @Test
    public void shouldHandleEmptyArray() {
        // when
        Expectation[] redacted = redactor.redact(new Expectation[0]);

        // then
        assertThat(redacted.length, is(0));
    }

    // --- SSE response header redaction ---

    @Test
    public void shouldRedactHeadersInSseResponse() {
        // given
        Expectation expectation = new Expectation(
            request().withMethod("GET").withPath("/stream")
                .withHeader("Authorization", "Bearer secret"),
            Times.unlimited(),
            TimeToLive.unlimited(),
            0
        ).thenRespondWithSse(
            sseResponse()
                .withStatusCode(200)
                .withHeader("Set-Cookie", "session=xyz")
                .withHeader("X-Custom", "visible")
                .withEvent(sseEvent().withData("test"))
        );

        // when
        Expectation[] redacted = redactor.redact(new Expectation[]{expectation});

        // then -- request header redacted
        assertThat(requestOf(redacted[0]).getFirstHeader("Authorization"), is(REDACTED_PLACEHOLDER));
        // SSE response headers redacted
        HttpSseResponse sseResp = redacted[0].getHttpSseResponse();
        boolean foundSetCookie = false;
        boolean foundCustom = false;
        for (Header header : sseResp.getHeaders().getEntries()) {
            if (header.getName().getValue().equalsIgnoreCase("Set-Cookie")) {
                assertThat(header.getValues().get(0).getValue(), is(REDACTED_PLACEHOLDER));
                foundSetCookie = true;
            }
            if (header.getName().getValue().equalsIgnoreCase("X-Custom")) {
                assertThat(header.getValues().get(0).getValue(), is("visible"));
                foundCustom = true;
            }
        }
        assertThat("Set-Cookie header should be found and redacted", foundSetCookie, is(true));
        assertThat("X-Custom header should be found and not redacted", foundCustom, is(true));
    }

    // --- Expectation without headers ---

    @Test
    public void shouldHandleExpectationWithNoHeaders() {
        // given
        Expectation expectation = Expectation.when(
            request().withMethod("GET").withPath("/no-headers")
        ).thenRespond(response().withStatusCode(200).withBody("ok"));

        // when
        Expectation[] redacted = redactor.redact(new Expectation[]{expectation});

        // then
        assertThat(redacted[0].getHttpResponse().getBodyAsString(), is("ok"));
    }

    // --- Body field redaction ---

    private final FixtureRedactor bodyRedactor = new FixtureRedactor(
        Arrays.asList("Authorization"), Arrays.asList("api_key", "password"));

    @Test
    public void shouldRedactConfiguredJsonBodyFieldsInRequest() {
        Expectation expectation = Expectation.when(
            request().withMethod("POST").withPath("/api")
                .withBody("{\"api_key\":\"sk-secret\",\"prompt\":\"hello\"}")
        ).thenRespond(response().withStatusCode(200));

        Expectation[] redacted = bodyRedactor.redact(new Expectation[]{expectation});

        String body = requestOf(redacted[0]).getBodyAsString();
        assertThat(body.contains("sk-secret"), is(false));
        assertThat(body.contains(REDACTED_PLACEHOLDER), is(true));
        assertThat(body.contains("hello"), is(true));
    }

    @Test
    public void shouldRedactNestedJsonBodyFields() {
        Expectation expectation = Expectation.when(
            request().withMethod("POST").withPath("/api")
                .withBody("{\"outer\":{\"password\":\"p@ss\",\"keep\":1}}")
        ).thenRespond(response().withStatusCode(200));

        Expectation[] redacted = bodyRedactor.redact(new Expectation[]{expectation});

        String body = requestOf(redacted[0]).getBodyAsString();
        assertThat(body.contains("p@ss"), is(false));
        assertThat(body.contains(REDACTED_PLACEHOLDER), is(true));
        assertThat(body.contains("keep"), is(true));
    }

    @Test
    public void shouldRedactConfiguredJsonBodyFieldsInResponse() {
        Expectation expectation = Expectation.when(
            request().withMethod("POST").withPath("/api")
        ).thenRespond(response().withStatusCode(200).withBody("{\"password\":\"secret\",\"ok\":true}"));

        Expectation[] redacted = bodyRedactor.redact(new Expectation[]{expectation});

        assertThat(redacted[0].getHttpResponse().getBodyAsString().contains("secret"), is(false));
    }

    @Test
    public void shouldFailClosedOnUnparseableBodyWhenBodyRedactionConfigured() {
        // a body that is neither a JSON document nor an SSE stream, but for which
        // field redaction is configured, is replaced wholesale (fail closed) so a
        // credential hidden in an unparseable payload cannot leak into a fixture
        Expectation expectation = Expectation.when(
            request().withMethod("POST").withPath("/api").withBody("not json api_key=sk-secret")
        ).thenRespond(response().withStatusCode(200));

        Expectation[] redacted = bodyRedactor.redact(new Expectation[]{expectation});

        String body = requestOf(redacted[0]).getBodyAsString();
        assertThat(body.contains("sk-secret"), is(false));
        assertThat(body, is(FixtureRedactor.UNPARSEABLE_BODY_PLACEHOLDER));
    }

    @Test
    public void shouldLeaveUnstructuredBodyWithoutSecretFieldUnchanged() {
        // an ordinary non-JSON body (plain text / HTML / decoded binary) that does
        // not mention any configured field name is preserved — there is nothing to
        // redact, so it must NOT be destroyed by the fail-closed path
        Expectation expectation = Expectation.when(
            request().withMethod("POST").withPath("/api")
        ).thenRespond(response().withStatusCode(200).withBody("Hello, World!"));

        Expectation[] redacted = bodyRedactor.redact(new Expectation[]{expectation});

        assertThat(redacted[0].getHttpResponse().getBodyAsString(), is("Hello, World!"));
    }

    @Test
    public void shouldLeaveScalarJsonBodyUnchangedWhenBodyRedactionConfigured() {
        // a scalar JSON value parses cleanly and cannot carry a named field — it is
        // left intact rather than fail-closed (it is structurally inspectable)
        Expectation expectation = Expectation.when(
            request().withMethod("POST").withPath("/api").withBody("\"just a string\"")
        ).thenRespond(response().withStatusCode(200));

        Expectation[] redacted = bodyRedactor.redact(new Expectation[]{expectation});

        assertThat(requestOf(redacted[0]).getBodyAsString(), is("\"just a string\""));
    }

    @Test
    public void shouldRedactConfiguredFieldsInsideSseStreamBody() {
        // a streamed (SSE) body has its configured fields redacted per data: payload,
        // preserving the event structure and non-JSON markers such as [DONE]
        String sse = "event: message\n"
            + "data: {\"api_key\":\"sk-secret\",\"text\":\"hi\"}\n"
            + "\n"
            + "data: [DONE]\n";
        Expectation expectation = Expectation.when(
            request().withMethod("POST").withPath("/api")
        ).thenRespond(response().withStatusCode(200).withBody(sse));

        Expectation[] redacted = bodyRedactor.redact(new Expectation[]{expectation});

        String body = redacted[0].getHttpResponse().getBodyAsString();
        assertThat(body.contains("sk-secret"), is(false));
        assertThat(body.contains(REDACTED_PLACEHOLDER), is(true));
        assertThat(body.contains("hi"), is(true));
        assertThat(body.contains("[DONE]"), is(true));
        assertThat(body.contains("event: message"), is(true));
    }

    @Test
    public void shouldFailClosedOnUnparseableSseDataPayloadMentioningSecretField() {
        // a data: payload that mentions a configured field but is not parseable as a
        // standalone JSON object/array (e.g. a truncated chunk) is failed closed,
        // while a plain marker like [DONE] is left intact
        String sse = "data: {\"api_key\":\"sk-secret\",\"truncated\n"
            + "\n"
            + "data: [DONE]\n";
        Expectation expectation = Expectation.when(
            request().withMethod("POST").withPath("/api")
        ).thenRespond(response().withStatusCode(200).withBody(sse));

        Expectation[] redacted = bodyRedactor.redact(new Expectation[]{expectation});

        String body = redacted[0].getHttpResponse().getBodyAsString();
        assertThat(body.contains("sk-secret"), is(false));
        assertThat(body.contains(FixtureRedactor.UNPARSEABLE_BODY_PLACEHOLDER), is(true));
        assertThat(body.contains("[DONE]"), is(true));
    }

    @Test
    public void defaultRedactorDoesNotTouchBodies() {
        Expectation expectation = Expectation.when(
            request().withMethod("POST").withPath("/api").withBody("{\"api_key\":\"sk-secret\"}")
        ).thenRespond(response().withStatusCode(200));

        Expectation[] redacted = redactor.redact(new Expectation[]{expectation});

        assertThat(requestOf(redacted[0]).getBodyAsString().contains("sk-secret"), is(true));
    }

    // --- Query-string redaction ---

    @Test
    public void shouldRedactSensitiveQueryParameterValuesByDefault() {
        // Gemini-style ?key=<API_KEY> and other credential-bearing query params are
        // redacted by the default redactor, while ordinary params are preserved
        Expectation expectation = Expectation.when(
            request().withMethod("POST").withPath("/v1beta/models/x:generateContent")
                .withQueryStringParameter("key", "AIza-super-secret")
                .withQueryStringParameter("alt", "sse")
        ).thenRespond(response().withStatusCode(200));

        Expectation[] redacted = redactor.redact(new Expectation[]{expectation});

        HttpRequest req = requestOf(redacted[0]);
        assertThat(req.getFirstQueryStringParameter("key"), is(REDACTED_PLACEHOLDER));
        assertThat(req.getFirstQueryStringParameter("alt"), is("sse"));
    }

    @Test
    public void shouldRedactAwsSigV4QueryCredentials() {
        Expectation expectation = Expectation.when(
            request().withMethod("GET").withPath("/object")
                .withQueryStringParameter("X-Amz-Signature", "deadbeef")
                .withQueryStringParameter("X-Amz-Security-Token", "FwoG-token")
                .withQueryStringParameter("X-Amz-Date", "20260629T000000Z")
        ).thenRespond(response().withStatusCode(200));

        Expectation[] redacted = redactor.redact(new Expectation[]{expectation});

        HttpRequest req = requestOf(redacted[0]);
        assertThat(req.getFirstQueryStringParameter("X-Amz-Signature"), is(REDACTED_PLACEHOLDER));
        assertThat(req.getFirstQueryStringParameter("X-Amz-Security-Token"), is(REDACTED_PLACEHOLDER));
        assertThat(req.getFirstQueryStringParameter("X-Amz-Date"), is("20260629T000000Z"));
    }
}
