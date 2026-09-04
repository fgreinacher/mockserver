package org.mockserver.netty.integration.mock;

import org.apache.commons.io.IOUtils;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.netty.MockServer;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.core.Is.is;
import static org.mockserver.stop.Stop.stopQuietly;

public class SseStreamingIntegrationTest {

    private static MockServerClient mockServerClient;
    private static int mockServerPort;

    @BeforeClass
    public static void startServer() {
        mockServerPort = new MockServer().getLocalPort();
        mockServerClient = new MockServerClient("localhost", mockServerPort);
    }

    @AfterClass
    public static void stopServer() {
        stopQuietly(mockServerClient);
    }

    @Before
    public void resetServer() {
        mockServerClient.reset();
    }

    private void createExpectation(String json) throws Exception {
        try (Socket socket = new Socket("localhost", mockServerPort)) {
            socket.setSoTimeout(5000);
            OutputStream output = socket.getOutputStream();
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            output.write(("PUT /mockserver/expectation HTTP/1.1\r\n" +
                "Host: localhost:" + mockServerPort + "\r\n" +
                "Connection: close\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: " + body.length + "\r\n" +
                "\r\n").getBytes(StandardCharsets.UTF_8));
            output.write(body);
            output.flush();
            IOUtils.toString(socket.getInputStream(), StandardCharsets.UTF_8);
        }
    }

    private String readResponse(Socket socket) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder response = new StringBuilder();
        String line;
        int contentLength = -1;
        boolean chunked = false;

        while ((line = reader.readLine()) != null) {
            response.append(line).append("\n");
            if (line.toLowerCase().startsWith("content-length:")) {
                contentLength = Integer.parseInt(line.split(":")[1].trim());
            }
            if (line.toLowerCase().contains("transfer-encoding: chunked")) {
                chunked = true;
            }
            if (line.isEmpty()) {
                break;
            }
        }

        if (chunked) {
            while ((line = reader.readLine()) != null) {
                response.append(line).append("\n");
            }
        } else if (contentLength > 0) {
            char[] body = new char[contentLength];
            int totalRead = 0;
            while (totalRead < contentLength) {
                int read = reader.read(body, totalRead, contentLength - totalRead);
                if (read == -1) break;
                totalRead += read;
            }
            response.append(new String(body, 0, totalRead));
        }

        return response.toString();
    }

    private String sendSseRequest(String path) throws Exception {
        try (Socket socket = new Socket("localhost", mockServerPort)) {
            socket.setSoTimeout(5000);
            OutputStream output = socket.getOutputStream();
            output.write(("GET " + path + " HTTP/1.1\r\n" +
                "Host: localhost:" + mockServerPort + "\r\n" +
                "Content-Length: 0\r\n" +
                "\r\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
            return readResponse(socket);
        }
    }

    private String sendHttpRequest(String method, String path, String requestBody) throws Exception {
        try (Socket socket = new Socket("localhost", mockServerPort)) {
            socket.setSoTimeout(5000);
            OutputStream output = socket.getOutputStream();
            StringBuilder request = new StringBuilder();
            request.append(method).append(" ").append(path).append(" HTTP/1.1\r\n");
            request.append("Host: localhost:").append(mockServerPort).append("\r\n");
            request.append("Connection: close\r\n");
            if (requestBody != null) {
                byte[] bodyBytes = requestBody.getBytes(StandardCharsets.UTF_8);
                request.append("Content-Type: application/json\r\n");
                request.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
                request.append("\r\n");
                output.write(request.toString().getBytes(StandardCharsets.UTF_8));
                output.write(bodyBytes);
            } else {
                request.append("Content-Length: 0\r\n");
                request.append("\r\n");
                output.write(request.toString().getBytes(StandardCharsets.UTF_8));
            }
            output.flush();
            return IOUtils.toString(socket.getInputStream(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Read exactly one HTTP/1.1 chunked response off a persistent socket without over-reading into
     * the next response - so a second request can be sent on the SAME connection. The reader used by
     * the other tests drains until EOF, which only works because those responses close the socket.
     */
    private String readOneChunkedResponse(InputStream in) throws IOException {
        StringBuilder response = new StringBuilder();
        String line;
        boolean chunked = false;
        int contentLength = -1;
        while (!(line = readRawLine(in)).isEmpty()) {
            response.append(line).append("\n");
            String lower = line.toLowerCase();
            if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) {
                chunked = true;
            } else if (lower.startsWith("content-length:")) {
                contentLength = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
            }
        }
        response.append("\n");
        if (!chunked) {
            if (contentLength > 0) {
                byte[] body = new byte[contentLength];
                int offset = 0;
                while (offset < contentLength) {
                    int read = in.read(body, offset, contentLength - offset);
                    if (read == -1) {
                        throw new EOFException("connection closed before the fixed-length body was read");
                    }
                    offset += read;
                }
                response.append(new String(body, StandardCharsets.UTF_8));
            }
            return response.toString();
        }
        while (true) {
            String sizeLine = readRawLine(in);
            int semicolon = sizeLine.indexOf(';');
            String hex = (semicolon >= 0 ? sizeLine.substring(0, semicolon) : sizeLine).trim();
            int size = Integer.parseInt(hex, 16);
            if (size == 0) {
                readRawLine(in); // trailing CRLF terminating the final (empty) chunk
                break;
            }
            byte[] chunk = new byte[size];
            int offset = 0;
            while (offset < size) {
                int read = in.read(chunk, offset, size - offset);
                if (read == -1) {
                    throw new EOFException("connection closed mid-chunk - the stream was not kept alive");
                }
                offset += read;
            }
            response.append(new String(chunk, StandardCharsets.UTF_8));
            readRawLine(in); // trailing CRLF after the chunk data
        }
        return response.toString();
    }

    private String readRawLine(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                buffer.write(c);
            }
        }
        return buffer.toString(StandardCharsets.UTF_8.name());
    }

    private void writeKeepAliveGet(OutputStream output, String path) throws IOException {
        output.write(("GET " + path + " HTTP/1.1\r\n" +
            "Host: localhost:" + mockServerPort + "\r\n" +
            "Connection: keep-alive\r\n" +
            "Content-Length: 0\r\n" +
            "\r\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    @Test
    public void shouldKeepHttp1ConnectionAliveForSseStreamAndAllowReuse() throws Exception {
        // GitHub issue #2641. An SSE expectation with NO closeConnection (the buggy default that used
        // to always close) must not drop the HTTP/1.1 connection it advertised as keep-alive.
        createExpectation("{" +
            "\"httpRequest\": {\"method\": \"GET\", \"path\": \"/keepalive-events\"}," +
            "\"httpSseResponse\": {" +
            "  \"statusCode\": 200," +
            "  \"events\": [" +
            "    {\"event\": \"message\", \"data\": \"first stream\", \"id\": \"1\"}" +
            "  ]" +
            "}" +
            "}");

        try (Socket socket = new Socket("localhost", mockServerPort)) {
            socket.setSoTimeout(5000);
            OutputStream output = socket.getOutputStream();
            InputStream input = socket.getInputStream();

            // when - a keep-alive client reads the whole first stream
            writeKeepAliveGet(output, "/keepalive-events");
            String first = readOneChunkedResponse(input);

            // then - the stream completed and the Connection header told the truth (keep-alive)
            assertThat(first, containsString("HTTP/1.1 200 OK"));
            assertThat(first, containsString("connection: keep-alive"));
            assertThat(first, containsString("data: first stream"));

            // when - a SECOND request is sent on the SAME socket (the connection was NOT dropped)
            writeKeepAliveGet(output, "/keepalive-events");
            String second = readOneChunkedResponse(input);

            // then - it is served, proving the connection survived the first stream. Before the fix
            // finishStream always closed here, so this second read hit EOF / RemoteDisconnected.
            assertThat(second, containsString("HTTP/1.1 200 OK"));
            assertThat(second, containsString("data: first stream"));
        }
    }

    @Test
    public void shouldReturnSseStreamWithMultipleEvents() throws Exception {
        createExpectation("{" +
            "\"httpRequest\": {\"method\": \"GET\", \"path\": \"/events\"}," +
            "\"httpSseResponse\": {" +
            "  \"statusCode\": 200," +
            "  \"events\": [" +
            "    {\"event\": \"message\", \"data\": \"hello world\", \"id\": \"1\"}," +
            "    {\"event\": \"update\", \"data\": \"second event\", \"id\": \"2\"}" +
            "  ]," +
            "  \"closeConnection\": true" +
            "}" +
            "}");

        String response = sendSseRequest("/events");

        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("content-type: text/event-stream"));
        assertThat(response, containsString("transfer-encoding: chunked"));
        assertThat(response, containsString("id: 1"));
        assertThat(response, containsString("event: message"));
        assertThat(response, containsString("data: hello world"));
        assertThat(response, containsString("id: 2"));
        assertThat(response, containsString("event: update"));
        assertThat(response, containsString("data: second event"));
    }

    @Test
    public void shouldReturnSseStreamWithMultiLineData() throws Exception {
        createExpectation("{" +
            "\"httpRequest\": {\"method\": \"GET\", \"path\": \"/multiline\"}," +
            "\"httpSseResponse\": {" +
            "  \"statusCode\": 200," +
            "  \"events\": [" +
            "    {\"event\": \"message\", \"data\": \"line1\\nline2\\nline3\", \"id\": \"1\"}" +
            "  ]," +
            "  \"closeConnection\": true" +
            "}" +
            "}");

        String response = sendSseRequest("/multiline");

        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("data: line1"));
        assertThat(response, containsString("data: line2"));
        assertThat(response, containsString("data: line3"));
    }

    @Test
    public void shouldReturnSseStreamWithRetryField() throws Exception {
        createExpectation("{" +
            "\"httpRequest\": {\"method\": \"GET\", \"path\": \"/retry\"}," +
            "\"httpSseResponse\": {" +
            "  \"statusCode\": 200," +
            "  \"events\": [" +
            "    {\"event\": \"message\", \"data\": \"retry test\", \"id\": \"1\", \"retry\": 5000}" +
            "  ]," +
            "  \"closeConnection\": true" +
            "}" +
            "}");

        String response = sendSseRequest("/retry");

        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("retry: 5000"));
        assertThat(response, containsString("data: retry test"));
    }

    @Test
    public void shouldReturnSseStreamWithCustomHeaders() throws Exception {
        createExpectation("{" +
            "\"httpRequest\": {\"method\": \"GET\", \"path\": \"/custom-headers\"}," +
            "\"httpSseResponse\": {" +
            "  \"statusCode\": 200," +
            "  \"headers\": {\"X-Custom\": [\"value\"]}," +
            "  \"events\": [" +
            "    {\"event\": \"message\", \"data\": \"with custom header\", \"id\": \"1\"}" +
            "  ]," +
            "  \"closeConnection\": true" +
            "}" +
            "}");

        String response = sendSseRequest("/custom-headers");

        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("content-type: text/event-stream"));
        assertThat(response, containsString("X-Custom: value"));
        assertThat(response, containsString("data: with custom header"));
    }

    @Test
    public void shouldReturnSseStreamWithDelayBetweenEvents() throws Exception {
        createExpectation("{" +
            "\"httpRequest\": {\"method\": \"GET\", \"path\": \"/delayed\"}," +
            "\"httpSseResponse\": {" +
            "  \"statusCode\": 200," +
            "  \"events\": [" +
            "    {\"event\": \"first\", \"data\": \"immediate\", \"id\": \"1\"}," +
            "    {\"event\": \"second\", \"data\": \"delayed\", \"id\": \"2\", \"delay\": {\"timeUnit\": \"MILLISECONDS\", \"value\": 200}}" +
            "  ]," +
            "  \"closeConnection\": true" +
            "}" +
            "}");

        long startTime = System.currentTimeMillis();
        String response = sendSseRequest("/delayed");
        long elapsed = System.currentTimeMillis() - startTime;

        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("data: immediate"));
        assertThat(response, containsString("data: delayed"));
        assertThat(elapsed, is(greaterThanOrEqualTo(150L)));
    }

    @Test
    public void shouldMatchJsonRpcRequestAndReturnResponse() throws Exception {
        createExpectation("{" +
            "\"httpRequest\": {" +
            "  \"method\": \"POST\"," +
            "  \"path\": \"/rpc\"," +
            "  \"body\": {\"type\": \"JSON_RPC\", \"method\": \"tools/list\"}" +
            "}," +
            "\"httpResponse\": {" +
            "  \"statusCode\": 200," +
            "  \"body\": \"{\\\"jsonrpc\\\": \\\"2.0\\\", \\\"result\\\": {\\\"tools\\\": []}, \\\"id\\\": 1}\"" +
            "}" +
            "}");

        String response = sendHttpRequest("POST", "/rpc",
            "{\"jsonrpc\": \"2.0\", \"method\": \"tools/list\", \"id\": 1}");

        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("\"jsonrpc\": \"2.0\""));
        assertThat(response, containsString("\"tools\": []"));
    }

    @Test
    public void shouldNotMatchJsonRpcRequestWithWrongMethod() throws Exception {
        createExpectation("{" +
            "\"httpRequest\": {" +
            "  \"method\": \"POST\"," +
            "  \"path\": \"/rpc\"," +
            "  \"body\": {\"type\": \"JSON_RPC\", \"method\": \"tools/list\"}" +
            "}," +
            "\"httpResponse\": {" +
            "  \"statusCode\": 200," +
            "  \"body\": \"{\\\"jsonrpc\\\": \\\"2.0\\\", \\\"result\\\": {\\\"tools\\\": []}, \\\"id\\\": 1}\"" +
            "}" +
            "}");

        String response = sendHttpRequest("POST", "/rpc",
            "{\"jsonrpc\": \"2.0\", \"method\": \"resources/list\", \"id\": 1}");

        assertThat(response, containsString("HTTP/1.1 404"));
    }

    @Test
    public void shouldMatchJsonRpcBatchRequest() throws Exception {
        createExpectation("{" +
            "\"httpRequest\": {" +
            "  \"method\": \"POST\"," +
            "  \"path\": \"/rpc\"," +
            "  \"body\": {\"type\": \"JSON_RPC\", \"method\": \"tools/call\"}" +
            "}," +
            "\"httpResponse\": {" +
            "  \"statusCode\": 200," +
            "  \"body\": \"{\\\"jsonrpc\\\": \\\"2.0\\\", \\\"result\\\": {}, \\\"id\\\": 2}\"" +
            "}" +
            "}");

        String response = sendHttpRequest("POST", "/rpc",
            "[{\"jsonrpc\": \"2.0\", \"method\": \"resources/list\", \"id\": 1}," +
                "{\"jsonrpc\": \"2.0\", \"method\": \"tools/call\", \"id\": 2}]");

        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("\"result\": {}"));
    }

    @Test
    public void shouldCreateSseExpectationViaApiAndReturnEvents() throws Exception {
        createExpectation("{" +
            "\"httpRequest\": {\"method\": \"GET\", \"path\": \"/api-events\"}," +
            "\"httpSseResponse\": {" +
            "  \"statusCode\": 200," +
            "  \"events\": [" +
            "    {\"event\": \"init\", \"data\": \"connected\", \"id\": \"100\"}," +
            "    {\"event\": \"data\", \"data\": \"{\\\"key\\\": \\\"value\\\"}\", \"id\": \"101\"}" +
            "  ]," +
            "  \"closeConnection\": true" +
            "}" +
            "}");

        String response = sendSseRequest("/api-events");

        assertThat(response, containsString("HTTP/1.1 200 OK"));
        assertThat(response, containsString("content-type: text/event-stream"));
        assertThat(response, containsString("id: 100"));
        assertThat(response, containsString("event: init"));
        assertThat(response, containsString("data: connected"));
        assertThat(response, containsString("id: 101"));
        assertThat(response, containsString("event: data"));
        assertThat(response, containsString("data: {\"key\": \"value\"}"));
    }
}
