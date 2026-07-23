package org.mockserver.mock.action.http;

import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpForward;
import org.mockserver.model.HttpForwardValidateAction;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.util.concurrent.CompletableFuture;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;
import static org.mockserver.model.HttpForwardValidateAction.forwardValidate;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class HttpForwardValidateActionHandlerTest {

    private NettyHttpClient mockHttpClient;
    private HttpForwardValidateActionHandler handler;

    @Before
    public void setupMocks() {
        mockHttpClient = mock(NettyHttpClient.class);
        MockServerLogger mockLogFormatter = mock(MockServerLogger.class);
        handler = new HttpForwardValidateActionHandler(mockLogFormatter, new Configuration(), mockHttpClient);
        openMocks(this);
    }

    @Test
    public void shouldForwardRequestWhenNoSpec() throws Exception {
        CompletableFuture<HttpResponse> responseFuture = new CompletableFuture<>();
        responseFuture.complete(response().withStatusCode(200).withBody("upstream"));
        when(mockHttpClient.sendRequest(any(HttpRequest.class), any())).thenReturn(responseFuture);

        HttpForwardActionResult result = handler.handle(null, request("/somePath"));

        HttpResponse actualResponse = result.getHttpResponse().get();
        assertThat(actualResponse.getStatusCode(), is(200));
        assertThat(actualResponse.getBodyAsString(), is("upstream"));
    }

    @Test
    public void shouldForwardRequestWithHostAndPort() throws Exception {
        CompletableFuture<HttpResponse> responseFuture = new CompletableFuture<>();
        responseFuture.complete(response().withStatusCode(200).withBody("forwarded"));
        when(mockHttpClient.sendRequest(any(HttpRequest.class), any())).thenReturn(responseFuture);

        HttpForwardActionResult result = handler.handle(
            forwardValidate()
                .withSpecUrlOrPayload("org/mockserver/openapi/openapi_petstore_example.json")
                .withHost("localhost")
                .withPort(8080)
                .withValidateRequest(false)
                .withValidateResponse(false),
            request("/pets").withMethod("GET")
        );

        HttpResponse actualResponse = result.getHttpResponse().get();
        assertThat(actualResponse.getStatusCode(), is(200));
        assertThat(actualResponse.getBodyAsString(), is("forwarded"));
    }

    @Test
    public void shouldBlockForwardValidateToPrivateTargetWhenSsrfGuardEnabled() {
        // given - SSRF guard on, explicit forward target resolves to a loopback/private address
        Configuration configuration = new Configuration().forwardProxyBlockPrivateNetworks(true);
        HttpForwardValidateActionHandler guardedHandler = new HttpForwardValidateActionHandler(mock(MockServerLogger.class), configuration, mockHttpClient);

        // when
        HttpResponse response = guardedHandler.handle(
            forwardValidate()
                .withSpecUrlOrPayload("org/mockserver/openapi/openapi_petstore_example.json")
                .withHost("127.0.0.1")
                .withPort(8080)
                .withValidateRequest(false)
                .withValidateResponse(false),
            request("/pets").withMethod("GET")
        ).getHttpResponse().join();

        // then - request is rejected with a bad gateway and never forwarded
        assertThat(response.getStatusCode(), is(502));
        verify(mockHttpClient, never()).sendRequest(any(HttpRequest.class), any());
    }

    @Test
    public void shouldRejectNonConformantRequestWithoutForwardingInStrictMode() throws Exception {
        // given - STRICT validation and a request whose body violates the Pet schema (id must be an
        // integer, here it is a string). A valid POST body would pass, so the fixture genuinely
        // violates the schema rather than matching a permissive default.
        CompletableFuture<HttpResponse> upstreamFuture = new CompletableFuture<>();
        upstreamFuture.complete(response().withStatusCode(200).withBody("forwarded"));
        when(mockHttpClient.sendRequest(any(HttpRequest.class), any())).thenReturn(upstreamFuture);

        // when
        HttpForwardActionResult result = handler.handle(
            forwardValidate()
                .withSpecUrlOrPayload("org/mockserver/openapi/openapi_petstore_example.json")
                .withValidateRequest(true)
                .withValidateResponse(false)
                .withValidationMode(HttpForwardValidateAction.ValidationMode.STRICT),
            request("/pets")
                .withMethod("POST")
                .withHeader("content-type", "application/json")
                .withBody("{\"id\": \"not_a_number\", \"name\": \"Fido\"}")
        );

        // then - the request is rejected with a 400 and is never forwarded upstream
        HttpResponse actualResponse = result.getHttpResponse().get();
        assertThat(actualResponse.getStatusCode(), is(400));
        assertThat(actualResponse.getBodyAsString(), containsString("OpenAPI request validation failed"));
        verify(mockHttpClient, never()).sendRequest(any(HttpRequest.class), any());
    }

    @Test
    public void shouldRejectNonConformantUpstreamResponseWithBadGatewayInStrictMode() throws Exception {
        // given - STRICT validation, a valid request (so it is forwarded), and a stubbed upstream
        // response that violates the listPets schema (an object where an array of Pet is required).
        CompletableFuture<HttpResponse> upstreamFuture = new CompletableFuture<>();
        upstreamFuture.complete(
            response()
                .withStatusCode(200)
                .withHeader("content-type", "application/json")
                .withBody("{\"not\": \"an array\"}")
        );
        when(mockHttpClient.sendRequest(any(HttpRequest.class), any())).thenReturn(upstreamFuture);

        // when
        HttpForwardActionResult result = handler.handle(
            forwardValidate()
                .withSpecUrlOrPayload("org/mockserver/openapi/openapi_petstore_example.json")
                .withValidateRequest(false)
                .withValidateResponse(true)
                .withValidationMode(HttpForwardValidateAction.ValidationMode.STRICT),
            request("/pets").withMethod("GET")
        );

        // then - the non-conformant upstream response is rejected with a 502
        HttpResponse actualResponse = result.getHttpResponse().get();
        assertThat(actualResponse.getStatusCode(), is(502));
        assertThat(actualResponse.getBodyAsString(), containsString("OpenAPI response validation failed"));
    }

    @Test
    public void shouldForwardNonConformantRequestUnchangedInLogOnlyMode() throws Exception {
        // given - LOG_ONLY validation and the same request that violates the Pet schema as the STRICT
        // rejection test (id is a string, not an integer). In LOG_ONLY the violation is logged only, so
        // the request must still be forwarded and the upstream response returned unmodified.
        CompletableFuture<HttpResponse> upstreamFuture = new CompletableFuture<>();
        upstreamFuture.complete(response().withStatusCode(200).withBody("forwarded"));
        when(mockHttpClient.sendRequest(any(HttpRequest.class), any())).thenReturn(upstreamFuture);

        // when
        HttpForwardActionResult result = handler.handle(
            forwardValidate()
                .withSpecUrlOrPayload("org/mockserver/openapi/openapi_petstore_example.json")
                .withValidateRequest(true)
                .withValidateResponse(false)
                .withValidationMode(HttpForwardValidateAction.ValidationMode.LOG_ONLY),
            request("/pets")
                .withMethod("POST")
                .withHeader("content-type", "application/json")
                .withBody("{\"id\": \"not_a_number\", \"name\": \"Fido\"}")
        );

        // then - the bad request is still forwarded upstream and the 200 flows back unchanged (not a 400)
        HttpResponse actualResponse = result.getHttpResponse().get();
        assertThat(actualResponse.getStatusCode(), is(200));
        assertThat(actualResponse.getBodyAsString(), is("forwarded"));
        verify(mockHttpClient).sendRequest(any(HttpRequest.class), any());
    }

    @Test
    public void shouldReturnNonConformantUpstreamResponseUnchangedInLogOnlyMode() throws Exception {
        // given - LOG_ONLY validation and a stubbed upstream response that violates the listPets schema
        // (an object where an array of Pet is required) - the same fixture the STRICT 502 test uses.
        CompletableFuture<HttpResponse> upstreamFuture = new CompletableFuture<>();
        upstreamFuture.complete(
            response()
                .withStatusCode(200)
                .withHeader("content-type", "application/json")
                .withBody("{\"not\": \"an array\"}")
        );
        when(mockHttpClient.sendRequest(any(HttpRequest.class), any())).thenReturn(upstreamFuture);

        // when
        HttpForwardActionResult result = handler.handle(
            forwardValidate()
                .withSpecUrlOrPayload("org/mockserver/openapi/openapi_petstore_example.json")
                .withValidateRequest(false)
                .withValidateResponse(true)
                .withValidationMode(HttpForwardValidateAction.ValidationMode.LOG_ONLY),
            request("/pets").withMethod("GET")
        );

        // then - the non-conformant upstream response is logged only and returned unmodified (not a 502)
        HttpResponse actualResponse = result.getHttpResponse().get();
        assertThat(actualResponse.getStatusCode(), is(200));
        assertThat(actualResponse.getBodyAsString(), is("{\"not\": \"an array\"}"));
        verify(mockHttpClient).sendRequest(any(HttpRequest.class), any());
    }

    @Test
    public void shouldReturnActionType() {
        assertThat(forwardValidate().getType().name(), is("FORWARD_VALIDATE"));
    }

    @Test
    public void shouldSetAndGetAllProperties() {
        HttpForwardValidateAction action = forwardValidate()
            .withSpecUrlOrPayload("someSpec")
            .withHost("someHost")
            .withPort(9090)
            .withScheme(HttpForward.Scheme.HTTPS)
            .withValidateRequest(false)
            .withValidateResponse(true)
            .withValidationMode(HttpForwardValidateAction.ValidationMode.LOG_ONLY);

        assertThat(action.getSpecUrlOrPayload(), is("someSpec"));
        assertThat(action.getHost(), is("someHost"));
        assertThat(action.getPort(), is(9090));
        assertThat(action.getScheme(), is(HttpForward.Scheme.HTTPS));
        assertThat(action.getValidateRequest(), is(false));
        assertThat(action.getValidateResponse(), is(true));
        assertThat(action.getValidationMode(), is(HttpForwardValidateAction.ValidationMode.LOG_ONLY));
    }

    @Test
    public void shouldHaveDefaultValues() {
        HttpForwardValidateAction action = forwardValidate();

        assertThat(action.getPort(), is(80));
        assertThat(action.getScheme(), is(HttpForward.Scheme.HTTP));
        assertThat(action.getValidateRequest(), is(true));
        assertThat(action.getValidateResponse(), is(true));
        assertThat(action.getValidationMode(), is(HttpForwardValidateAction.ValidationMode.STRICT));
    }

    @Test
    public void shouldImplementEqualsAndHashCode() {
        HttpForwardValidateAction action1 = forwardValidate()
            .withSpecUrlOrPayload("spec1")
            .withHost("host1")
            .withPort(80);
        HttpForwardValidateAction action2 = forwardValidate()
            .withSpecUrlOrPayload("spec1")
            .withHost("host1")
            .withPort(80);
        HttpForwardValidateAction action3 = forwardValidate()
            .withSpecUrlOrPayload("spec2")
            .withHost("host2")
            .withPort(443);

        assertThat(action1.equals(action2), is(true));
        assertThat(action1.hashCode() == action2.hashCode(), is(true));
        assertThat(action1.equals(action3), is(false));
    }
}
