// Makes a real HTTP/3 request to a MockServer container, using the JDK's own HTTP/3 client (JEP 517,
// JDK 26+) so the check does not share a QUIC stack with the Netty server it is testing.
// Run in a JDK sidecar that shares the server container's network namespace:
//     java Http3Probe.java <controlPort> <http3Port>
// Waits for the control plane, creates an expectation over HTTP/1.1, requests it over HTTP/3 only
// (no Alt-Svc fallback), trusting only the server's own CA, and exits non-zero unless the answer is
// that expectation, served over HTTP/3.

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpOption;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

public final class Http3Probe {

    private static final String BODY = "served over http3";

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: java Http3Probe.java <controlPort> <http3Port>");
            System.exit(2);
        }
        String control = "http://localhost:" + args[0];
        HttpClient http1 = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
        awaitReady(http1, control);

        String expectation = "{\"httpRequest\":{\"path\":\"/http3-smoke\"},\"httpResponse\":{\"statusCode\":200,\"body\":\"" + BODY + "\"}}";
        HttpResponse<String> created = http1.send(
            HttpRequest.newBuilder(URI.create(control + "/mockserver/expectation")).PUT(HttpRequest.BodyPublishers.ofString(expectation)).build(),
            HttpResponse.BodyHandlers.ofString());
        if (created.statusCode() != 201) {
            fail("could not create the expectation: HTTP " + created.statusCode() + " " + created.body());
        }

        HttpClient http3 = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_3)
            .sslContext(trusting(caCertificatePem(http1, control)))
            .connectTimeout(Duration.ofSeconds(10))
            .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://localhost:" + args[1] + "/http3-smoke"))
            .setOption(HttpOption.H3_DISCOVERY, HttpOption.Http3DiscoveryMode.HTTP_3_URI_ONLY)
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build();
        HttpResponse<String> response = http3.send(request, HttpResponse.BodyHandlers.ofString());
        System.out.println("version=" + response.version() + " status=" + response.statusCode() + " body=" + response.body());
        if (response.version() != HttpClient.Version.HTTP_3 || response.statusCode() != 200 || !BODY.equals(response.body())) {
            fail("expected HTTP_3 200 \"" + BODY + "\"");
        }
        System.out.println("PASS: MockServer answered an expectation over HTTP/3");
    }

    private static void awaitReady(HttpClient client, String control) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < deadline) {
            try {
                HttpRequest status = HttpRequest.newBuilder(URI.create(control + "/mockserver/status")).PUT(HttpRequest.BodyPublishers.noBody()).build();
                if (client.send(status, HttpResponse.BodyHandlers.discarding()).statusCode() == 200) {
                    return;
                }
            } catch (Exception notYet) {
                // still starting
            }
            Thread.sleep(500);
        }
        fail("control plane never became ready on " + control);
    }

    private static String caCertificatePem(HttpClient client, String control) throws Exception {
        String json = client.send(HttpRequest.newBuilder(URI.create(control + "/mockserver/proxyConfiguration")).GET().build(),
            HttpResponse.BodyHandlers.ofString()).body();
        Matcher pem = Pattern.compile("\"caCertificatePem\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        if (!pem.find()) {
            fail("no caCertificatePem in /mockserver/proxyConfiguration: " + json);
        }
        return pem.group(1).replace("\\n", "\n");
    }

    // A JDK-standard trust manager: the JDK's QUIC stack rejects an SSLContext built on a custom one.
    private static SSLContext trusting(String caCertificatePem) throws Exception {
        KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        trustStore.setCertificateEntry("mockserver-ca", CertificateFactory.getInstance("X.509")
            .generateCertificate(new ByteArrayInputStream(caCertificatePem.getBytes(StandardCharsets.US_ASCII))));
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(trustStore);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagers.getTrustManagers(), null);
        return context;
    }

    private static void fail(String message) {
        System.err.println("FAIL: " + message);
        System.exit(1);
    }
}
