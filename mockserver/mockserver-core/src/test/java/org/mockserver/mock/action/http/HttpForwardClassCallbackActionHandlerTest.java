package org.mockserver.mock.action.http;

import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.action.ExpectationForwardCallback;
import org.mockserver.model.HttpClassCallback;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.util.concurrent.CompletableFuture;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;
import static org.mockserver.model.HttpClassCallback.callback;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * @author jamesdbloom
 */
public class HttpForwardClassCallbackActionHandlerTest {

    private NettyHttpClient mockHttpClient;
    private HttpForwardClassCallbackActionHandler httpForwardClassCallbackActionHandler;

    @Before
    public void setupFixture() {
        mockHttpClient = mock(NettyHttpClient.class);
        httpForwardClassCallbackActionHandler = new HttpForwardClassCallbackActionHandler(new MockServerLogger(), Configuration.configuration(), mockHttpClient);

        openMocks(this);
    }

    @Test
    public void shouldHandleInvalidClass() throws Exception {
        // given
        CompletableFuture<HttpResponse> httpResponse = new CompletableFuture<>();
        httpResponse.complete(response("some_response_body"));
        when(mockHttpClient.sendRequest(any(HttpRequest.class), isNull())).thenReturn(httpResponse);

        HttpClassCallback httpClassCallback = callback("org.mockserver.mock.action.FooBar");

        // when
        CompletableFuture<HttpResponse> actualHttpResponse = httpForwardClassCallbackActionHandler
            .handle(httpClassCallback, request().withBody("some_body"))
            .getHttpResponse();

        // then
        assertThat(actualHttpResponse.get(), is(httpResponse.get()));
        verify(mockHttpClient).sendRequest(request().withBody("some_body"), null);
    }

    @Test
    public void shouldHandleValidLocalClass() throws Exception {
        // given
        CompletableFuture<HttpResponse> httpResponse = new CompletableFuture<>();
        httpResponse.complete(response("some_response_body"));
        when(mockHttpClient.sendRequest(any(HttpRequest.class), isNull())).thenReturn(httpResponse);

        HttpClassCallback httpClassCallback = HttpClassCallback.callback(HttpForwardClassCallbackActionHandlerTest.TestCallback.class);

        // when
        CompletableFuture<HttpResponse> actualHttpResponse = httpForwardClassCallbackActionHandler
            .handle(httpClassCallback, request().withBody("some_body"))
            .getHttpResponse();

        // then
        assertThat(actualHttpResponse.get(), is(httpResponse.get()));
        verify(mockHttpClient).sendRequest(request("some_path"), null);
    }

    @Test
    public void shouldHonourContextClassLoaderOverrideWhenThreadContextClassLoaderCannotLoadCallback() throws Exception {
        // given
        CompletableFuture<HttpResponse> httpResponse = new CompletableFuture<>();
        httpResponse.complete(response("some_response_body"));
        when(mockHttpClient.sendRequest(any(HttpRequest.class), isNull())).thenReturn(httpResponse);

        String callbackClassName = TestCallback.class.getName();
        ClassLoader originalThreadContextClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            // the thread-context classloader can NOT load the callback class
            Thread.currentThread().setContextClassLoader(new BlockingClassLoader(callbackClassName, ClassLoader.getSystemClassLoader()));
            // but the explicit override CAN (mirrors the Maven plugin / Spring Boot setup)
            CallbackClassLoaderResolver.setContextClassLoader(getClass().getClassLoader());

            HttpClassCallback httpClassCallback = callback(callbackClassName);

            // when
            CompletableFuture<HttpResponse> actualHttpResponse = httpForwardClassCallbackActionHandler
                .handle(httpClassCallback, request().withBody("some_body"))
                .getHttpResponse();

            // then - the callback was loaded via the override and ran, so the forwarded request is the transformed one
            assertThat(actualHttpResponse.get(), is(httpResponse.get()));
            verify(mockHttpClient).sendRequest(request("some_path"), null);
        } finally {
            Thread.currentThread().setContextClassLoader(originalThreadContextClassLoader);
            CallbackClassLoaderResolver.setContextClassLoader(null);
        }
    }

    public static class TestCallback implements ExpectationForwardCallback {

        @Override
        public HttpRequest handle(HttpRequest httpRequest) {
            return request("some_path");
        }
    }

    private static class BlockingClassLoader extends ClassLoader {

        private final String blockedClassName;

        BlockingClassLoader(String blockedClassName, ClassLoader parent) {
            super(parent);
            this.blockedClassName = blockedClassName;
        }

        @Override
        public Class<?> loadClass(String name) throws ClassNotFoundException {
            if (blockedClassName.equals(name)) {
                throw new ClassNotFoundException("blocked for test: " + name);
            }
            return super.loadClass(name);
        }
    }
}
