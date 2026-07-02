package org.mockserver.netty;

import io.netty.channel.EventLoopGroup;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.model.Header;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Guards that the dedicated forward/proxy event-loop group ({@code forwardClientGroup}) is created
 * LAZILY — only on the first forward/proxy use — rather than eagerly in the {@link LifeCycle}
 * constructor. A pure-mock deployment that never forwards must never allocate it (saving
 * {@code clientNioEventLoopThreadCount} selectors/threads at startup), and a never-forwarding server
 * must stop cleanly without ever creating the group.
 * <p>
 * The authoritative, per-instance (parallel-safe) assertion is the reflective {@code forwardClientGroup}
 * field: {@code null} until the first forward, non-{@code null} afterwards. Thread-name enumeration is
 * used on the positive (post-forward) path where at least one such thread is guaranteed to exist, so it
 * is robust even when other MockServer instances run concurrently in the same JVM.
 * <p>
 * Complements {@link ForwardClientEventLoopIsolationTest} (disjointness invariant) and
 * {@link ForwardClientEventLoopLifecycleTest} (shutdown/leak invariant).
 */
public class ForwardClientLazyCreationTest {

    private static final String FORWARD_THREAD_TOKEN = "forwardClientEventLoop";

    /**
     * A plain mock server that only serves {@code respond(...)} expectations never forwards, so the
     * forward-client group must remain {@code null} after handling a mock request — proving it was not
     * created at startup.
     */
    @Test(timeout = 30_000)
    public void plainMockServerNeverCreatesForwardClientGroup() throws Exception {
        MockServer mockServer = new MockServer();
        try {
            int port = mockServer.getLocalPort();
            new MockServerClient("localhost", port)
                .when(request().withPath("/mock"))
                .respond(response().withBody("mock_body"));

            // group must not exist merely from starting the server
            assertThat("forward-client group must not be created at startup",
                readForwardClientGroup(mockServer), is(nullValue()));

            // drive a pure-mock request (no forwarding)
            assertThat(blockingGet(port, "/mock"), is("mock_body"));

            // still not created — a mock response never touches the forward client
            assertThat("forward-client group must stay uncreated after a pure-mock request",
                readForwardClientGroup(mockServer), is(nullValue()));
        } finally {
            mockServer.stop();
        }
    }

    /**
     * A forwarded request lazily creates the group on first use, its threads appear, and forwarding
     * works end-to-end.
     */
    @Test(timeout = 30_000)
    public void forwardCreatesForwardClientGroupLazilyAndForwardingWorks() throws Exception {
        MockServer upstream = new MockServer();
        MockServer proxy = new MockServer();
        try {
            int upstreamPort = upstream.getLocalPort();
            new MockServerClient("localhost", upstreamPort)
                .when(request().withPath("/upstream"))
                .respond(response().withBody("upstream_body"));

            int proxyPort = proxy.getLocalPort();
            new MockServerClient("localhost", proxyPort)
                .when(request().withPath("/proxy"))
                .forward(httpRequest -> httpRequest.clone()
                    .withPath("/upstream")
                    .replaceHeader(new Header("Host", "127.0.0.1:" + upstreamPort)));

            // before any forward the group is not yet created
            assertThat("forward-client group must not exist before the first forward",
                readForwardClientGroup(proxy), is(nullValue()));

            // when - a forwarded request
            assertThat("forwarding returns the upstream body", blockingGet(proxyPort, "/proxy"), is("upstream_body"));

            // then - the group is now created (lazily, on first forward)
            EventLoopGroup forwardGroup = readForwardClientGroup(proxy);
            assertThat("forward-client group must be created lazily by the first forward",
                forwardGroup, is(notNullValue()));

            // and its distinctly-named threads now exist (at least one — forwarding just ran on it)
            Set<String> forwardThreadNames = Thread.getAllStackTraces().keySet().stream()
                .map(Thread::getName)
                .filter(name -> name.contains(FORWARD_THREAD_TOKEN))
                .collect(Collectors.toSet());
            assertThat("a forwardClientEventLoop thread exists once forwarding has run",
                forwardThreadNames.isEmpty(), is(false));
        } finally {
            proxy.stop();
            upstream.stop();
        }
    }

    /**
     * Stopping a server that never forwarded must complete cleanly and must NOT create the group during
     * shutdown (the {@code stopAsync()} teardown handles the never-created case).
     */
    @Test(timeout = 30_000)
    public void stopAfterNeverForwardingCompletesCleanlyWithoutCreatingGroup() throws Exception {
        MockServer mockServer = new MockServer();
        assertThat("forward-client group must not be created at startup",
            readForwardClientGroup(mockServer), is(nullValue()));

        // stop() blocks until stopAsync() has fully torn down; it must not throw or hang
        mockServer.stop();

        // shutdown must not have created the group
        assertThat("stop() must not create the forward-client group when it was never used",
            readForwardClientGroup(mockServer), is(nullValue()));
    }

    private static EventLoopGroup readForwardClientGroup(LifeCycle lifeCycle) throws Exception {
        Field field = LifeCycle.class.getDeclaredField("forwardClientGroup");
        field.setAccessible(true);
        return (EventLoopGroup) field.get(lifeCycle);
    }

    private static String blockingGet(int port, String path) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL("http://localhost:" + port + path);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new RuntimeException("unexpected status " + status + " for " + path);
            }
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                int c;
                while ((c = reader.read()) != -1) {
                    body.append((char) c);
                }
            }
            return body.toString();
        } catch (Exception e) {
            throw new RuntimeException("GET " + path + " failed: " + e.getMessage(), e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
