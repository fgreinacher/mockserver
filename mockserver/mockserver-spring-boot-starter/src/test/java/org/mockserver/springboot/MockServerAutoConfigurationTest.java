package org.mockserver.springboot;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.integration.ClientAndServer;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

class MockServerAutoConfigurationTest {

    private AnnotationConfigApplicationContext context;

    @AfterEach
    void closeContext() {
        if (context != null) {
            context.close();
            context = null;
        }
    }

    @Test
    void doesNotStartWhenDisabledByDefault() {
        context = run(new HashMap<>());
        assertEquals(0, context.getBeanNamesForType(ClientAndServer.class).length,
            "MockServer must not start unless mockserver.enabled=true");
        assertEquals(0, context.getBeanNamesForType(MockServerClient.class).length);
    }

    @Test
    void startsMockServerAndExposesClientBeanThatResponds() throws Exception {
        context = run(Map.of("mockserver.enabled", "true"));

        // the single ClientAndServer bean is injectable as a MockServerClient
        MockServerClient client = context.getBean(MockServerClient.class);
        assertNotNull(client, "MockServerClient bean must be present");
        assertSame(context.getBean(ClientAndServer.class), client,
            "MockServerClient and ClientAndServer must resolve to the same bean instance");

        ClientAndServer server = (ClientAndServer) client;
        assertTrue(server.isRunning(), "MockServer should be running");
        int port = server.getPort();
        assertTrue(port > 0, "MockServer should bind an ephemeral port by default");

        // register an expectation and prove the server actually responds over HTTP
        client
            .when(request().withMethod("GET").withPath("/ping"))
            .respond(response().withStatusCode(200).withBody("pong"));

        assertEquals("pong", httpGet("http://127.0.0.1:" + port + "/ping"));
    }

    @Test
    void bindsPortProperty() {
        int fixedPort = freePort();
        context = run(Map.of("mockserver.enabled", "true", "mockserver.port", String.valueOf(fixedPort)));

        ClientAndServer server = context.getBean(ClientAndServer.class);
        assertEquals(fixedPort, server.getPort().intValue());
    }

    private static AnnotationConfigApplicationContext run(Map<String, Object> properties) {
        AnnotationConfigApplicationContext ctx = new AnnotationConfigApplicationContext();
        ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
        ctx.register(MockServerAutoConfiguration.class);
        ctx.refresh();
        return ctx;
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        assertEquals(200, connection.getResponseCode());
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining("\n"));
        } finally {
            connection.disconnect();
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
