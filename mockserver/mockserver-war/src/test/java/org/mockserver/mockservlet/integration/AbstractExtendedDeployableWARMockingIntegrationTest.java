package org.mockserver.mockservlet.integration;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslProvider;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockserver.client.ClientException;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpStatusCode;
import org.mockserver.model.MediaType;
import org.mockserver.serialization.PortBindingSerializer;
import org.mockserver.testing.integration.mock.AbstractExtendedSameJVMMockingIntegrationTest;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static org.hamcrest.Matchers.containsString;
import static org.mockserver.matchers.Times.once;
import static org.mockserver.model.ConnectionOptions.connectionOptions;
import static org.mockserver.model.Cookie.cookie;
import static org.mockserver.model.Header.header;
import static org.mockserver.model.HttpClassCallback.callback;
import static org.mockserver.model.HttpError.error;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.HttpStatusCode.ACCEPTED_202;
import static org.mockserver.model.HttpStatusCode.OK_200;
import static org.mockserver.model.JsonBody.json;
import static org.mockserver.model.Parameter.param;
import static org.mockserver.model.PortBinding.portBinding;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

/**
 * @author jamesdbloom
 */
public abstract class AbstractExtendedDeployableWARMockingIntegrationTest extends AbstractExtendedSameJVMMockingIntegrationTest {

    @Rule
    public final ExpectedException exception = ExpectedException.none();

    protected boolean supportsHTTP2() {
        return false;
    }

    @Override
    protected boolean supportsChunkedResponses() {
        // ConnectionOptions (which carries chunkSize) is rejected outright by the servlet
        // transport: "ConnectionOptions is not supported by MockServer deployed as a WAR due to
        // limitations in the JEE specification"
        return false;
    }

    @Override
    protected boolean supportsRequestBodyDecompression() {
        // Tomcat / servlet containers do not decompress request bodies
        return false;
    }

    @Test
    public void shouldReturnResponseByMatchingUrlEncodedPath() {
        // when
        mockServerClient
            .when(
                request()
                    .withPath(calculatePath("ab@c.de"))
            )
            .respond(
                response()
                    .withStatusCode(HttpStatusCode.ACCEPTED_202.code())
                    .withReasonPhrase(HttpStatusCode.ACCEPTED_202.reasonPhrase())
                    .withBody("some_body_response")
            );

        // then
        // - in http
        assertThat(makeRequest(
                request()
                    .withMethod("GET")
                    .withPath(calculatePath("ab%40c.de"))
                    .withQueryStringParameters(
                        param("queryStringParameterOneName", "queryStringParameterOneValue"),
                        param("queryStringParameterTwoName", "queryStringParameterTwoValue")
                    )
                    .withHeaders(header("headerNameRequest", "headerValueRequest"))
                    .withCookies(cookie("cookieNameRequest", "cookieValueRequest")),
                getHeadersToRemove()
            ), is(response()
                .withStatusCode(HttpStatusCode.ACCEPTED_202.code())
                .withReasonPhrase(HttpStatusCode.ACCEPTED_202.reasonPhrase())
                .withBody("some_body_response")));
        // - in https
        assertThat(makeRequest(
                request()
                    .withMethod("GET")
                    .withSecure(true)
                    .withPath(calculatePath("ab%40c.de"))
                    .withQueryStringParameters(
                        param("queryStringParameterOneName", "queryStringParameterOneValue"),
                        param("queryStringParameterTwoName", "queryStringParameterTwoValue")
                    )
                    .withHeaders(header("headerNameRequest", "headerValueRequest"))
                    .withCookies(cookie("cookieNameRequest", "cookieValueRequest")),
                getHeadersToRemove()
            ), is(response()
                .withStatusCode(HttpStatusCode.ACCEPTED_202.code())
                .withReasonPhrase(HttpStatusCode.ACCEPTED_202.reasonPhrase())
                .withBody("some_body_response")));
    }

    @Test
    public void shouldReturnErrorResponseForExpectationWithConnectionOptions() {
        // given
        exception.expect(ClientException.class);
        exception.expectMessage(containsString("ConnectionOptions is not supported by MockServer deployed as a WAR"));

        // when
        mockServerClient
            .when(
                request()
            )
            .respond(
                response()
                    .withBody("some_long_body")
                    .withConnectionOptions(
                        connectionOptions()
                            .withKeepAliveOverride(true)
                            .withContentLengthHeaderOverride(10)
                    )
            );
    }

    @Test
    public void shouldReturnErrorResponseForExpectationWithHttpError() {
        // given
        exception.expect(ClientException.class);
        exception.expectMessage(containsString("HttpError is not supported by MockServer deployed as a WAR"));

        // when
        mockServerClient
            .when(
                request()
            )
            .error(
                error()
                    .withDropConnection(true)
            );
    }

    @Test
    public void shouldReturnErrorResponseForRespondByObjectCallback() {
        // given
        exception.expect(ClientException.class);
        exception.expectMessage(containsString("ExpectationResponseCallback, ExpectationForwardCallback or ExpectationForwardAndResponseCallback is not supported by MockServer deployed as a WAR"));

        // when
        mockServerClient
            .when(
                request()
                    .withPath(calculatePath("object_callback")),
                once()
            )
            .respond(
                httpRequest -> response()
                    .withStatusCode(ACCEPTED_202.code())
                    .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                    .withHeaders(
                        header("x-object-callback", "test_object_callback_header")
                    )
                    .withBody("an_object_callback_response")
            );
    }

    @Test
    public void shouldReturnErrorResponseForForwardByObjectCallback() {
        // given
        exception.expect(ClientException.class);
        exception.expectMessage(containsString("ExpectationResponseCallback, ExpectationForwardCallback or ExpectationForwardAndResponseCallback is not supported by MockServer deployed as a WAR"));

        // when
        mockServerClient
            .when(
                request()
                    .withPath(calculatePath("echo"))
            )
            .forward(
                httpRequest -> request()
                    .withBody("some_overridden_body")
                    .withSecure(httpRequest.isSecure())
            );
    }

    @Test
    public void shouldReturnErrorResponseForForwardAndResponseByObjectCallback() {
        // given
        exception.expect(ClientException.class);
        exception.expectMessage(containsString("ExpectationResponseCallback, ExpectationForwardCallback or ExpectationForwardAndResponseCallback is not supported by MockServer deployed as a WAR"));

        // when
        mockServerClient
            .when(
                request()
                    .withPath(calculatePath("echo"))
            )
            .forward(
                httpRequest ->
                    request()
                        .withBody("some_overridden_body")
                        .withSecure(httpRequest.isSecure()),
                (httpRequest, httpResponse) ->
                    httpResponse
                        .withHeader("x-response-test", "x-response-test")
            );
    }

    @Test
    public void shouldCallbackForResponseToSpecifiedClassInTestClasspath() {
        // given
        TestClasspathTestExpectationResponseCallback.httpRequests.clear();
        TestClasspathTestExpectationResponseCallback.httpResponse = response()
            .withStatusCode(ACCEPTED_202.code())
            .withReasonPhrase(ACCEPTED_202.reasonPhrase())
            .withHeaders(
                header("x-callback", "test_callback_header")
            )
            .withBody("a_callback_response");

        // when
        mockServerClient
            .when(
                request()
                    .withPath(calculatePath("callback"))
            )
            .respond(
                callback()
                    .withCallbackClass(TestClasspathTestExpectationResponseCallback.class)
            );

        // then
        // - in http
        assertThat(makeRequest(
                request()
                    .withPath(calculatePath("callback"))
                    .withMethod("POST")
                    .withHeaders(
                        header("X-Test", "test_headers_and_body")
                    )
                    .withBody("an_example_body_http"),
                getHeadersToRemove()
            ), is(response()
                .withStatusCode(ACCEPTED_202.code())
                .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                .withHeaders(
                    header("x-callback", "test_callback_header")
                )
                .withBody("a_callback_response")));
        assertThat("an_example_body_http", is(TestClasspathTestExpectationResponseCallback.httpRequests.get(0).getBody().getValue()));
        assertThat(calculatePath("callback"), is(TestClasspathTestExpectationResponseCallback.httpRequests.get(0).getPath().getValue()));

        // - in https
        assertThat(makeRequest(
                request()
                    .withSecure(true)
                    .withPath(calculatePath("callback"))
                    .withMethod("POST")
                    .withHeaders(
                        header("X-Test", "test_headers_and_body")
                    )
                    .withBody("an_example_body_https"),
                getHeadersToRemove()
            ), is(response()
                .withStatusCode(ACCEPTED_202.code())
                .withReasonPhrase(ACCEPTED_202.reasonPhrase())
                .withHeaders(
                    header("x-callback", "test_callback_header")
                )
                .withBody("a_callback_response")));
        assertThat("an_example_body_https", is(TestClasspathTestExpectationResponseCallback.httpRequests.get(1).getBody().getValue()));
        assertThat(calculatePath("callback"), is(TestClasspathTestExpectationResponseCallback.httpRequests.get(1).getPath().getValue()));
    }

    @Test
    public void shouldCallbackForwardCallbackToOverrideRequestInTestClasspath() {
        // given
        TestClasspathTestExpectationForwardCallback.httpRequests.clear();
        TestClasspathTestExpectationForwardCallback.httpRequestToReturn = request()
            .withHeaders(
                header("x-callback", "test_callback_header"),
                header("Host", "localhost:" + insecureEchoServer.getPort())
            )
            .withBody("a_callback_forward");

        // when
        mockServerClient
            .when(
                request()
                    .withPath(calculatePath("callback"))
            )
            .forward(
                callback()
                    .withCallbackClass(TestClasspathTestExpectationForwardCallback.class)
            );

        // then
        // - in http
        assertThat(makeRequest(
                request()
                    .withPath(calculatePath("callback"))
                    .withMethod("POST")
                    .withHeaders(
                        header("X-Test", "test_headers_and_body")
                    )
                    .withBody("an_example_body_http"),
                getHeadersToRemove()
            ), is(response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withHeaders(
                    header("x-callback", "test_callback_header")
                )
                .withBody("a_callback_forward")));
        assertThat("an_example_body_http", is(TestClasspathTestExpectationForwardCallback.httpRequests.get(0).getBody().getValue()));
        assertThat(calculatePath("callback"), is(TestClasspathTestExpectationForwardCallback.httpRequests.get(0).getPath().getValue()));

        // - in https
        assertThat(makeRequest(
                request()
                    .withSecure(true)
                    .withPath(calculatePath("callback"))
                    .withMethod("POST")
                    .withHeaders(
                        header("X-Test", "test_headers_and_body")
                    )
                    .withBody("an_example_body_https"),
                getHeadersToRemove()
            ), is(response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withHeaders(
                    header("x-callback", "test_callback_header")
                )
                .withBody("a_callback_forward")));
        assertThat("an_example_body_https", is(TestClasspathTestExpectationForwardCallback.httpRequests.get(1).getBody().getValue()));
        assertThat(calculatePath("callback"), is(TestClasspathTestExpectationForwardCallback.httpRequests.get(1).getPath().getValue()));
    }

    @Test
    public void shouldCallbackForwardCallbackToOverrideRequestAndResponseInTestClasspath() {
        // given
        TestClasspathTestExpectationForwardCallbackWithResponseOverride.httpRequests.clear();
        TestClasspathTestExpectationForwardCallbackWithResponseOverride.httpRequestToReturn = request()
            .withHeaders(
                header("x-callback", "test_callback_header_request"),
                header("Host", "localhost:" + insecureEchoServer.getPort())
            )
            .withBody("a_callback_forward_request");
        TestClasspathTestExpectationForwardCallbackWithResponseOverride.httpResponseToReturn = response()
            .withHeaders(
                header("x-callback", "test_callback_header_response")
            )
            .withBody("a_callback_forward_response");

        // when
        mockServerClient
            .when(
                request()
                    .withPath(calculatePath("callback"))
            )
            .forward(
                callback()
                    .withCallbackClass(TestClasspathTestExpectationForwardCallbackWithResponseOverride.class)
            );

        // then
        // - in http
        assertThat(makeRequest(
                request()
                    .withPath(calculatePath("callback"))
                    .withMethod("POST")
                    .withHeaders(
                        header("X-Test", "test_headers_and_body")
                    )
                    .withBody("an_example_body_http"),
                getHeadersToRemove()
            ), is(response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withHeaders(
                    header("x-callback", "test_callback_header_response")
                )
                .withBody("a_callback_forward_response")));
        assertThat(calculatePath("callback"), is(TestClasspathTestExpectationForwardCallbackWithResponseOverride.httpRequests.get(0).getPath().getValue()));
        assertThat("an_example_body_http", is(TestClasspathTestExpectationForwardCallbackWithResponseOverride.httpRequests.get(0).getBody().getValue()));
        assertThat("a_callback_forward_request", is(TestClasspathTestExpectationForwardCallbackWithResponseOverride.httpResponses.get(0).getBody().getValue()));

        // - in https
        assertThat(makeRequest(
                request()
                    .withSecure(true)
                    .withPath(calculatePath("callback"))
                    .withMethod("POST")
                    .withHeaders(
                        header("X-Test", "test_headers_and_body")
                    )
                    .withBody("an_example_body_http"),
                getHeadersToRemove()
            ), is(response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withHeaders(
                    header("x-callback", "test_callback_header_response")
                )
                .withBody("a_callback_forward_response")));
        assertThat(calculatePath("callback"), is(TestClasspathTestExpectationForwardCallbackWithResponseOverride.httpRequests.get(0).getPath().getValue()));
        assertThat("an_example_body_http", is(TestClasspathTestExpectationForwardCallbackWithResponseOverride.httpRequests.get(0).getBody().getValue()));
        assertThat("a_callback_forward_request", is(TestClasspathTestExpectationForwardCallbackWithResponseOverride.httpResponses.get(0).getBody().getValue()));
    }

    @Test
    public void shouldReturnStatus() {
        // given
        PortBindingSerializer portBindingSerializer = new PortBindingSerializer(new MockServerLogger());

        // then
        // - in http
        assertThat(makeRequest(
                request()
                    .withPath(calculatePath("mockserver/status"))
                    .withMethod("PUT"),
                getHeadersToRemove()
            ), is(response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withHeader(CONTENT_TYPE.toString(), "application/json; charset=utf-8")
                .withBody(json(portBindingSerializer.serialize(
                    portBinding(getServerPort())
                ), MediaType.JSON_UTF_8))));
        // - in https
        assertThat(makeRequest(
                request()
                    .withSecure(true)
                    .withPath(calculatePath("mockserver/status"))
                    .withMethod("PUT"),
                getHeadersToRemove()
            ), is(response()
                .withStatusCode(OK_200.code())
                .withReasonPhrase(OK_200.reasonPhrase())
                .withHeader(CONTENT_TYPE.toString(), "application/json; charset=utf-8")
                .withBody(json(portBindingSerializer.serialize(
                    portBinding(getServerSecurePort())
                ), MediaType.JSON_UTF_8))));
    }


    @Test
    public void shouldReturnStatusOnCustomPath() {
        String originalStatusPath = ConfigurationProperties.livenessHttpGetPath();
        try {
            // given
            ConfigurationProperties.livenessHttpGetPath("/livenessProbe");
            PortBindingSerializer portBindingSerializer = new PortBindingSerializer(new MockServerLogger());
            // then
            // - in http
            assertThat(makeRequest(
                    request()
                        .withPath(calculatePath("livenessProbe"))
                        .withMethod("GET"),
                    getHeadersToRemove()
                ), is(response()
                    .withStatusCode(OK_200.code())
                    .withReasonPhrase(OK_200.reasonPhrase())
                    .withHeader(CONTENT_TYPE.toString(), "application/json; charset=utf-8")
                    .withBody(json(portBindingSerializer.serialize(
                        portBinding(getServerPort())
                    ), MediaType.JSON_UTF_8))));
            // - in https
            assertThat(makeRequest(
                    request()
                        .withSecure(true)
                        .withPath(calculatePath("livenessProbe"))
                        .withMethod("GET"),
                    getHeadersToRemove()
                ), is(response()
                    .withStatusCode(OK_200.code())
                    .withReasonPhrase(OK_200.reasonPhrase())
                    .withHeader(CONTENT_TYPE.toString(), "application/json; charset=utf-8")
                    .withBody(json(portBindingSerializer.serialize(
                        portBinding(getServerSecurePort())
                    ), MediaType.JSON_UTF_8))));
        } finally {
            ConfigurationProperties.livenessHttpGetPath(originalStatusPath);
        }
    }

    // --- decode-gap overrides: tests moved to L3 that fail on the Tomcat servlet codec ---

    @Override
    @Test
    @Ignore("decode-gap: Tomcat rejects pipe '|' characters in query strings per RFC 7230/3986 — see test-coverage audit")
    public void shouldReturnResponseByMatchingQueryParametersWithPipeDelimitedParameters() {
        super.shouldReturnResponseByMatchingQueryParametersWithPipeDelimitedParameters();
    }

    @Override
    @Test
    @Ignore("decode-gap: Tomcat servlet codec does not decode matrix-style path parameters — see test-coverage audit")
    public void shouldReturnResponseByMatchingPathParametersWithMatrixStyleParameters() {
        super.shouldReturnResponseByMatchingPathParametersWithMatrixStyleParameters();
    }

    @Override
    @Test
    @Ignore("decode-gap: Tomcat default max header size (8KB) rejects the 16KB test header — see test-coverage audit")
    public void shouldReturnResponseByMatchingVeryLargeHeader() {
        super.shouldReturnResponseByMatchingVeryLargeHeader();
    }
}
