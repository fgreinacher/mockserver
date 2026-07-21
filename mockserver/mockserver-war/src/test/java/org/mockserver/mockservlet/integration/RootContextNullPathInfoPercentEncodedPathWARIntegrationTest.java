package org.mockserver.mockservlet.integration;

import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.mockservlet.MockServerServlet;
import org.mockserver.socket.PortFactory;

import java.io.File;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.HttpStatusCode.OK_200;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * End-to-end regression guard for the WAR / servlet percent-decode fix in
 * {@link org.mockserver.mappers.HttpServletRequestToMockServerHttpRequestDecoder}.
 * <p>
 * The decoder prefers the container-decoded {@code getPathInfo()} but falls back to the raw,
 * still-percent-encoded {@code getRequestURI()} when {@code getPathInfo()} is {@code null}. This test
 * deploys the servlet in a configuration that demonstrably drives a real container down that fallback:
 * a ROOT context ({@code ""}) with a default-servlet ({@code "/"}) mapping. For a raw
 * {@code GET /ab%40c.de}, Tomcat 11.0.24 / jakarta.servlet-api 6.1.0 reports
 * {@code pathInfo=null}, {@code servletPath=/ab@c.de} and {@code requestURI=/ab%40c.de}, so the
 * decoder must percent-decode the {@code %40} back to {@code @} itself for the request to match an
 * expectation registered for {@code /ab@c.de} (otherwise 404 rather than the mocked response).
 * <p>
 * It is the mapping rather than the ROOT context on its own that produces the {@code null} path-info:
 * on the same container a {@code "/*"} mapping — used by the other WAR integration tests and by the
 * shipped WAR's {@code web.xml} — reports a non-null, already-decoded {@code pathInfo=/ab@c.de} under
 * a ROOT context too, and so never reaches this fallback branch.
 * <p>
 * The request is written as a RAW HTTP GET straight down a socket so the literal {@code %40} reaches
 * the container verbatim, bypassing any client that would decode/normalise the path first.
 */
public class RootContextNullPathInfoPercentEncodedPathWARIntegrationTest {

    private static final int SERVER_HTTP_PORT = PortFactory.findFreePort();
    private static Tomcat tomcat;
    private static MockServerClient mockServerClient;

    @BeforeClass
    @SuppressWarnings("deprecation")
    public static void startServer() throws Exception {
        tomcat = new Tomcat();
        tomcat.setBaseDir(new File(".").getCanonicalPath() + File.separatorChar + "tomcat_root_default_servlet");

        // http connector
        tomcat.setPort(SERVER_HTTP_PORT);
        tomcat.getConnector();

        // deploy the servlet at ROOT context ("") with a DEFAULT-servlet ("/") mapping, which drives
        // the container to report a null path-info for a data-plane request (unlike a "/*" mapping)
        Context ctx = tomcat.addContext("", new File(".").getAbsolutePath());
        tomcat.addServlet("", "mockServerServlet", new MockServerServlet());
        ctx.addServletMappingDecoded("/", "mockServerServlet");
        ctx.addApplicationListener(MockServerServlet.class.getName());

        tomcat.start();

        mockServerClient = new MockServerClient("localhost", SERVER_HTTP_PORT, "");
    }

    @AfterClass
    public static void stopServer() throws Exception {
        stopQuietly(mockServerClient);
        if (tomcat != null) {
            tomcat.stop();
            tomcat.getServer().await();
        }
        TimeUnit.MILLISECONDS.sleep(500);
    }

    @Test
    public void shouldMatchPercentEncodedPathUnderNullPathInfoFallback() throws Exception {
        // given - an expectation registered for the DECODED path
        mockServerClient
            .when(
                request()
                    .withMethod("GET")
                    .withPath("/ab@c.de")
            )
            .respond(
                response()
                    .withStatusCode(OK_200.code())
                    .withReasonPhrase(OK_200.reasonPhrase())
                    .withBody("raw_socket_percent_decoded_body")
            );

        // when - a RAW HTTP GET for the still-percent-encoded path is written straight down a socket
        // so the container receives the literal "%40"; with a null path-info the decoder falls back
        // to getRequestURI() and must percent-decode "%40" back to "@" itself for the match to hit
        String rawResponse = sendRawHttpGet("/ab%40c.de", SERVER_HTTP_PORT);
        String statusLine = rawResponse.contains("\r\n") ? rawResponse.substring(0, rawResponse.indexOf("\r\n")) : rawResponse;

        // then - the raw "%40" was decoded to "@" and matched the expectation (200), not a 404 not-matched
        assertThat("status line of raw response was:\n" + rawResponse, statusLine, startsWith("HTTP/1.1 200"));
        assertThat("raw response was:\n" + rawResponse, rawResponse, containsString("raw_socket_percent_decoded_body"));
    }

    /**
     * Send a raw, un-normalised HTTP/1.1 GET straight down a socket so a percent-encoded request
     * target reaches the server verbatim (no client-side decoding). Reads the full response until
     * the server closes the connection (Connection: close).
     */
    private String sendRawHttpGet(String rawPath, int port) throws Exception {
        try (java.net.Socket socket = new java.net.Socket("localhost", port)) {
            socket.setSoTimeout(30000);
            String httpRequest = "GET " + rawPath + " HTTP/1.1\r\n" +
                "Host: localhost:" + port + "\r\n" +
                "Connection: close\r\n" +
                "\r\n";
            socket.getOutputStream().write(httpRequest.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            return org.apache.commons.io.IOUtils.toString(socket.getInputStream(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

}
