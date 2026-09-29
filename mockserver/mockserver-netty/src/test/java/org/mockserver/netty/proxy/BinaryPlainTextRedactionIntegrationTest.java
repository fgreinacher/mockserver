package org.mockserver.netty.proxy;

import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.netty.MockServer;
import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.*;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * A plain-text HTTP request with a method MockServer's HTTP decoder does not recognise (PROPFIND, REPORT, PURGE, QUERY)
 * goes down the binary path, which logs the payload as hex and as UTF-8 text at INFO: its headers must be masked there
 * when redactSecretsInLog is on. The TRACE wire dump (LoggingHandler) writes raw bytes to slf4j and is not redacted.
 */
public class BinaryPlainTextRedactionIntegrationTest {

    private static final String SECRET = "PROPFIND-SECRET-TOKEN-999";
    private static final String HEX_SECRET = hex(SECRET);

    @Test
    public void shouldMaskAnUnrecognisedMethodRequestLoggedAsBinary() throws Exception {
        Configuration configuration = configuration().redactSecretsInLog(true).logLevel("INFO");
        List<LogEntry> logged = new CopyOnWriteArrayList<>();
        // a clone: the event log clears the entry it is handed once it has copied it
        MockServerLogger.setGlobalLogEventListener(logEntry -> logged.add(logEntry.clone()));
        MockServer mockServer = new MockServer(configuration, 0);
        try {
            send(mockServer.getLocalPort(), "PROPFIND /dav HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer " + SECRET + "\r\n\r\n");
            waitForBinaryEntry(logged);

            String logs = send(mockServer.getLocalPort(), "PUT /mockserver/retrieve?type=LOGS HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n");
            assertThat(logs, containsString("binary request"));
            assertThat("LOGS leaked the credential", logs, not(containsString(SECRET)));
            assertThat("LOGS leaked the credential as hex", logs.toLowerCase(), not(containsString(HEX_SECRET)));
            for (LogEntry entry : logged) {
                String console = console(entry, configuration);
                assertThat("console leaked the credential:\n" + console, console, not(containsString(SECRET)));
                assertThat("console leaked the credential as hex:\n" + console, console.toLowerCase(), not(containsString(HEX_SECRET)));
            }
            LogEntry binary = logged.stream().filter(entry -> String.valueOf(entry.getMessageFormat()).contains("binary")).findFirst().orElseThrow(AssertionError::new);
            assertThat("unchanged with redaction off", binary.getMessage(configuration().redactSecretsInLog(false)).toLowerCase(), containsString(HEX_SECRET));
        } finally {
            MockServerLogger.setGlobalLogEventListener(null);
            mockServer.stop();
        }
    }

    private static void waitForBinaryEntry(List<LogEntry> logged) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (logged.stream().noneMatch(entry -> String.valueOf(entry.getMessageFormat()).contains("binary")) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
    }

    private static String console(LogEntry entry, Configuration configuration) {
        Logger logger = mock(Logger.class);
        when(logger.isInfoEnabled()).thenReturn(true);
        MockServerLogger.writeToSystemOut(logger, entry, configuration);
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(logger, atMost(1)).info(message.capture(), nullable(Throwable.class));
        verify(logger, atMost(1)).warn(message.capture(), nullable(Throwable.class));
        verify(logger, atMost(1)).error(message.capture(), nullable(Throwable.class));
        return String.join("\n", message.getAllValues());
    }

    private static String send(int port, String raw) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(3_000);
            socket.getOutputStream().write(raw.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            InputStream in = socket.getInputStream();
            byte[] buffer = new byte[8192];
            try {
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                    String text = out.toString(StandardCharsets.UTF_8.name());
                    int head = text.indexOf("\r\n\r\n");
                    int contentLength = text.toLowerCase().indexOf("content-length: ");
                    if (head > 0 && contentLength > 0 && contentLength < head) {
                        int length = Integer.parseInt(text.substring(contentLength + 16, text.indexOf("\r\n", contentLength)).trim());
                        if (out.size() >= head + 4 + length) {
                            break;
                        }
                    }
                }
            } catch (SocketTimeoutException | java.net.SocketException ignored) {
                // the binary path closes the connection, or the server stops writing
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String hex(String text) {
        StringBuilder hex = new StringBuilder();
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
