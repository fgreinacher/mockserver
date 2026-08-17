package org.mockserver.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.util.Base64;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link WebhookServer}.
 * <p>
 * Starts a real HTTPS server on an ephemeral port with a self-signed certificate,
 * sends real HTTP requests, and asserts the responses.
 */
class WebhookServerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static WebhookServer webhookServer;
    private static SSLContext clientSslContext;
    private static int port;

    @TempDir
    static Path tempDir;

    @BeforeAll
    static void startServer() throws Exception {
        // Generate self-signed certificate for testing using keytool
        Path ksPath = tempDir.resolve("test-keystore.p12");
        char[] password = "changeit".toCharArray();

        ProcessBuilder keytool = new ProcessBuilder(
            "keytool", "-genkeypair",
            "-alias", "server",
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-validity", "1",
            "-dname", "CN=localhost",
            "-storetype", "PKCS12",
            "-keystore", ksPath.toString(),
            "-storepass", new String(password),
            "-keypass", new String(password)
        );
        keytool.inheritIO();
        Process proc = keytool.start();
        int exitCode = proc.waitFor();
        assertEquals(0, exitCode, "keytool should succeed");

        // Load the generated keystore
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (var is = java.nio.file.Files.newInputStream(ksPath)) {
            ks.load(is, password);
        }

        // Server SSLContext
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, password);

        SSLContext serverSslContext = SSLContext.getInstance("TLS");
        serverSslContext.init(kmf.getKeyManagers(), null, null);

        // Client SSLContext (trusts the self-signed cert)
        Certificate cert = ks.getCertificate("server");
        KeyStore trustKs = KeyStore.getInstance("PKCS12");
        trustKs.load(null, null);
        trustKs.setCertificateEntry("server", cert);

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustKs);

        clientSslContext = SSLContext.getInstance("TLS");
        clientSslContext.init(null, tmf.getTrustManagers(), null);

        // Start server on ephemeral port
        SidecarInjectionConfig config = new SidecarInjectionConfig();
        webhookServer = new WebhookServer(
            new InetSocketAddress("127.0.0.1", 0), serverSslContext, config
        );
        webhookServer.start();
        port = webhookServer.getPort();
    }

    @AfterAll
    static void stopServer() {
        if (webhookServer != null) {
            webhookServer.stop(0);
        }
    }

    @Nested
    class HealthEndpoint {

        @Test
        void healthzReturns200() throws Exception {
            HttpsURLConnection conn = createConnection("/healthz", "GET");
            try {
                assertThat(conn.getResponseCode(), is(200));
                String body = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertThat(body, is("ok"));
            } finally {
                conn.disconnect();
            }
        }
    }

    @Nested
    class InjectEndpoint {

        @Test
        void optedInPodGetsInjected() throws Exception {
            String reviewJson = buildAdmissionReview("test-uid-inject", true, false);
            HttpsURLConnection conn = createConnection("/inject", "POST");
            try {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(reviewJson.getBytes(StandardCharsets.UTF_8));
                }

                assertThat(conn.getResponseCode(), is(200));

                byte[] responseBytes = conn.getInputStream().readAllBytes();
                JsonNode response = MAPPER.readTree(responseBytes);

                assertThat(response.path("apiVersion").asText(), is("admission.k8s.io/v1"));
                assertThat(response.path("kind").asText(), is("AdmissionReview"));

                JsonNode admissionResponse = response.path("response");
                assertThat(admissionResponse.path("uid").asText(), is("test-uid-inject"));
                assertTrue(admissionResponse.path("allowed").asBoolean());
                assertTrue(admissionResponse.has("patch"), "should have patch");
                assertThat(admissionResponse.path("patchType").asText(), is("JSONPatch"));

                // Decode and verify patch
                String patchBase64 = admissionResponse.get("patch").asText();
                byte[] patchBytes = Base64.getDecoder().decode(patchBase64);
                JsonNode patch = MAPPER.readTree(patchBytes);
                assertTrue(patch.isArray());
                assertTrue(patch.size() > 0);
            } finally {
                conn.disconnect();
            }
        }

        @Test
        void nonOptedPodIsAllowed() throws Exception {
            String reviewJson = buildAdmissionReview("test-uid-noopt", false, false);
            HttpsURLConnection conn = createConnection("/inject", "POST");
            try {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(reviewJson.getBytes(StandardCharsets.UTF_8));
                }

                assertThat(conn.getResponseCode(), is(200));

                JsonNode response = MAPPER.readTree(conn.getInputStream().readAllBytes());
                JsonNode admissionResponse = response.path("response");
                assertTrue(admissionResponse.path("allowed").asBoolean());
                assertFalse(admissionResponse.has("patch"));
            } finally {
                conn.disconnect();
            }
        }

        @Test
        void emptyBodyReturnsAllowWithMessage() throws Exception {
            HttpsURLConnection conn = createConnection("/inject", "POST");
            try {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(new byte[0]);
                }

                assertThat(conn.getResponseCode(), is(200));

                JsonNode response = MAPPER.readTree(conn.getInputStream().readAllBytes());
                JsonNode admissionResponse = response.path("response");
                assertTrue(admissionResponse.path("allowed").asBoolean());
                assertThat(admissionResponse.path("status").path("message").asText(),
                    containsString("empty request body"));
            } finally {
                conn.disconnect();
            }
        }

        @Test
        void malformedJsonReturnsAllowWithError() throws Exception {
            HttpsURLConnection conn = createConnection("/inject", "POST");
            try {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = conn.getOutputStream()) {
                    os.write("{not valid json".getBytes(StandardCharsets.UTF_8));
                }

                assertThat(conn.getResponseCode(), is(200));

                JsonNode response = MAPPER.readTree(conn.getInputStream().readAllBytes());
                JsonNode admissionResponse = response.path("response");
                assertTrue(admissionResponse.path("allowed").asBoolean(),
                    "malformed body should still allow pod (fail-open)");
                assertTrue(admissionResponse.path("status").has("message"));
            } finally {
                conn.disconnect();
            }
        }

        @Test
        void getMethodReturns405() throws Exception {
            HttpsURLConnection conn = createConnection("/inject", "GET");
            try {
                assertThat(conn.getResponseCode(), is(405));
            } finally {
                conn.disconnect();
            }
        }
    }

    @Nested
    class ConfigFromEnv {

        @Test
        void defaultConfigValues() {
            // With no env vars set, should use defaults from SidecarInjectionConfig
            SidecarInjectionConfig config = new SidecarInjectionConfig();
            assertThat(config.getServerPort(), is(1080));
            assertThat(config.getRedirectPorts(), is("80,443"));
            assertThat(config.getRunAsUser(), is(65534));
            assertThat(config.getLogLevel(), is("INFO"));
        }
    }

    @Nested
    class PrivateKeyLoading {

        // Throwaway keys generated with openssl, one per standard unencrypted PEM
        // form. The webhook must accept all of them so the chart's TLS bootstrap
        // tool can change without breaking the handler.

        // openssl genpkey -algorithm RSA  (the original self-signed Job's format)
        private static final String PKCS8_RSA =
            "-----BEGIN PRIVATE KEY-----\n"
                + "MIIEvwIBADANBgkqhkiG9w0BAQEFAASCBKkwggSlAgEAAoIBAQDF0C1/qG+p8swV\n"
                + "/iZyzEI4/x7/CfEvcrawiVcwSCN3yM/BNMO5IWNi5aV0z3RpnordHCMkRAeIalXh\n"
                + "Hwm1tQw1teHBsX4pEETp1VEYzdjIySLwhOBZj/abg50gfWwHi3X1AbwZ7lJ7sWi8\n"
                + "SST8fCsYcaU5rYXdm5MNONYMOvI8gTu4TaXJFqBwCSI5e8bjwzXzt5l9G038CM5w\n"
                + "E6saieDjxtF1JS02C96DTg11kYeAhYLFhIZGjl/JnWTdk0j4xeAySWVp9TMSRqLf\n"
                + "4hZCsgxK5iM8KCPQ3rqveS4b03dzoxiBWftdfWvocP+s8IoCVhdUb3bEVLogh025\n"
                + "dIEjldNFAgMBAAECggEAKwTZiDwswJRtXtENMoUKV9PfvU4/tfZoFZ9gpz7g+8Ff\n"
                + "sSBU+lNxBkZ0A6HEKt4QTAK8/7uNudSKRbGWzn4HoDykUpfTnIGNwx6hitflb9ES\n"
                + "OKVlFwUwd+SZDMJJ9qAVMInGiwshxAWyhdQQZ5pnUuMQMCl1Bds6ETONlU5SdOax\n"
                + "0Yv9sAMPMElT4uF2DX6XPXYJ8RxxBITcq4k8LfmzkTRP6p336LH4QXzWyk5SKjvA\n"
                + "4RQ/EKIOFsXGDxyqI/ouSaoh32yPIqEsS6ADYihueuPxAdQElJXRGcXWIqwPzUZK\n"
                + "NI+KjfpFkGGl/qe4Q4r9YZF6Vb2RR6pmEUdu6obwMQKBgQDzw1sxNIJNV+/3ZXZX\n"
                + "0Km4vpXCcXp3dVFIGax2SCUIBAJdm85LXdCq4VR//+IRsWQ1uVCpv9D3CACcokTy\n"
                + "DyFzcJ15nagDw8vZRhfRdjaIfuvUJmtretCwoigLo9k1E9JblG5AhVFYKLVmptnt\n"
                + "dWT9o2KEUOM79Z8TErls/B3eqQKBgQDPvk+S7T5B2DnaxyLrWAZ2n6Y/M/aoL4PD\n"
                + "uUHlWKRsC6QjHLUAWYpHezGUTO/oUsX2oK5k27b+LJ9BQpOWJ0pOUGpFKnC4mAlX\n"
                + "pV6yEzXSGrJUOeKBF8iD0nmvWgE85h0jgf1ycv8nbqrlQcxghINfkh9581eAdmf8\n"
                + "pzBUYI29PQKBgQCZZgTLMDoXphEy5LzWgk9sHTNtS7A/4Kon71Ail0AGjU9XzSbD\n"
                + "MuSPxIFCk6qWa8WeMWJbkIRWEMkhyNQOaAsq9GGFGPuUcvCjaIKwo+2pdAXAWfUb\n"
                + "jAwsO79ro86aokCstPm0zLDmA6g0UyetUUUegGUM00JMh0N140ChHv9FEQKBgQDG\n"
                + "cjl5VP+/zlmVz9xfjDrAXklk3rKkfp8T/IgiGccXHxewIuAUcXRSTDBURhp2h3tr\n"
                + "2Jo+5lOsAdwvbvWk3etxXAfoAl6jNzjVbLdEzG0BQ1dOde0U/C8jHY/4HbZJAlib\n"
                + "brU4+vkaJfFCBtTA7lTAmslOqVHQ+UrkYqEcOQ+s0QKBgQC06krsihf2tJCHPLoM\n"
                + "1hFcYeVsDngoX1rAsPdA/p5Q6qWEEIwvD0ZbufjBM6E0p0lRaBdLj6Xb3tGfziZo\n"
                + "s4iCEoQY1bFF+3MSF3t0n7M4CQ6C4sOxzYCBIbHBHt9tI5LQyKkh8jAuaSW8K4c9\n"
                + "IsZmglTNjl7gu/U7vRzW3UdWbg==\n"
                + "-----END PRIVATE KEY-----\n";

        // cert-manager's default RSA encoding (privateKey with no encoding: PKCS8)
        private static final String PKCS1_RSA =
            "-----BEGIN RSA PRIVATE KEY-----\n"
                + "MIIEpAIBAAKCAQEA6lmaTCvEn2VWSxb1ftqKt2JtpbwxEI+s8Ln7ASLcLl0LCfzY\n"
                + "i2KcItj7PO/6MfEGdU/uxoUoDqPo5OHx6vaxAY8K4bK+WcJc1lijw3b3yJmQ9qWA\n"
                + "QPaB0CU56aL5nbMWb0AT/sh/JCqf9jgncgkBpWoMKFinuADuYUvFz6/jQTycYRk4\n"
                + "qS4SSDJ63KeV46nvSqEI9bM2BiPFrWA1pFQmrbBLbHjVIjM1DxYJtTYw7L0kbQkI\n"
                + "u3xhNy62VmWCC/Yj2hKNXYGoCam3WVXTzKpOdFw8Y7wcVxX/MwOLpKnXfAgsNING\n"
                + "uIZeJs6+D1mk0wx0FrT1vyFmBJE3YSjz7zSMFQIDAQABAoIBAB7c9BQqA3gWiXnU\n"
                + "KTqun2wtW1FjanbK5TTC2Yq5w5Obj1OeaApbT0LLnrLUy/d9zaLvhvvAF5lt/sL9\n"
                + "+rU+Deutofo9ZxI9JarY+6BHb7SMfOnuu+hSTqBR9sGNRCB/sGmwX7HDR/NEZdKw\n"
                + "bIl5JC1bvQQnQNdb0AkiSIfkmyJBD7N0NC1XPyvhAEO5vJsyfQVipHdu91byQ6Tk\n"
                + "ZPnboR8eV7gQf3yedzBeZD0HSKHBUpl/xdiKHgKQTJSy4vFmcmsxKV30a/aBRlz9\n"
                + "IEySqVtp1UFj7A39OQ+ffUF7r01Ci9npTNSVlltEiaMUoA6oIlDfbioWa8Yo9R5h\n"
                + "XluYP4ECgYEA+kWlqD2dUFGQyQRh62vY9vWz7XJ2M25asmDiG0Hq6HRAKwDPTk81\n"
                + "zItP1RhgO7zE+A4tre/ph8E55yhDC0KK6GMFmhdDI12AHiRMeQky9Y+Ms13rTOpP\n"
                + "RIDJg20o93CtHWTQ0Tg7NS9CVrQ3oR2fVRuA+XHs5UpwtQp4Kjln9aECgYEA77aq\n"
                + "84eNa6XFm/480TTmOuxiAQYHRaDX+iRcWnGUT424LUf5YtetepU6IWjqE+KG8tWb\n"
                + "RxdjFGfbver4Jik196RiYrw13B0pe9Xe/aNvGouOeEmdRtc/1OuxRWCsirZxLmp2\n"
                + "E+vjvEuM2fm/5LSASFPs28c9pHNlBEV5F93h2fUCgYEA5VCFjhcOmnZyFE3Irt49\n"
                + "iWLuPwXe2hcmUUVGR7VpWR6TYRO330fiwo1vU5CnNHUtgR/0qOgncTUSKgSREbMh\n"
                + "9fYtPthLsw7MAlI+I7TTFX83a24F2I7knJ7ohVyy6a47YLBsSRed4Ihx32H3is/K\n"
                + "mz+9OFIzvpArnyZ9nirFX6ECgYEA5aEaygcEFibK0dAN+mquUau3hjt8I9sciebj\n"
                + "AVDkPgEIeXgFEgaBjHf/I5oZActycpTlFoj0xMto2NmJtSStKfkytlqNTboxzwrl\n"
                + "fhtdhxRA+kGqg/4Wi6TsQAWHw6lZaplZW2QQ2IOW/ggdJr0yVhbvQuntxucz0Y+r\n"
                + "nI1UmTECgYAutbgN5BDD+JJvzh/x6iHTiRKH4unbOvVlTNCjdcgNynwmQykNMl3G\n"
                + "T1gKHfFoSGbJ+oAXLqZ9EC6nzhdTVdmdjmdJilvJsn2lxq3YYq7kS/jvRsrcrgsz\n"
                + "f2TSztlxQyRi0xT6s/f/7wueFbuD+Bvr4aUEsoQssM12VX6r99bN6Q==\n"
                + "-----END RSA PRIVATE KEY-----\n";

        // openssl genpkey -algorithm EC  (PKCS#8 EC)
        private static final String PKCS8_EC =
            "-----BEGIN PRIVATE KEY-----\n"
                + "MIGHAgEAMBMGByqGSM49AgEGCCqGSM49AwEHBG0wawIBAQQgb/8uKvlcw3gdsnxF\n"
                + "bqOpdGeZoUnu3lkNeK/kCn30LaChRANCAATUG8vWtNU83xr4cGEcSqQlIWjJkehC\n"
                + "jjrV2JVIQKkZg36F1bFkG/ZHW7rUulizCgBottUq3cY4nKaRrd5bv2kr\n"
                + "-----END PRIVATE KEY-----\n";

        // kube-webhook-certgen's ECDSA serving key (SEC1) — the format that broke
        // the original PKCS#8-only loader and motivated this fix.
        private static final String SEC1_EC =
            "-----BEGIN EC PRIVATE KEY-----\n"
                + "MHcCAQEEIFLFm37VkhZvEMzRpv/RBtP4pwYpNtiyucmjZV11kwVIoAoGCCqGSM49\n"
                + "AwEHoUQDQgAENY2vl7hRGfgerolj9DND2mR2VZRQv9YrwzbL1rgmsyUX6uk4uS56\n"
                + "uP+IKIruIAn2pMo5w/xeiHcYwFcwVXgC2w==\n"
                + "-----END EC PRIVATE KEY-----\n";

        @Test
        void pkcs8RsaKeyIsAccepted() throws Exception {
            PrivateKey key = WebhookServer.loadPrivateKey(PKCS8_RSA);
            assertThat(key, is(notNullValue()));
            assertThat(key.getAlgorithm(), is("RSA"));
        }

        @Test
        void pkcs1RsaKeyIsAccepted() throws Exception {
            PrivateKey key = WebhookServer.loadPrivateKey(PKCS1_RSA);
            assertThat(key, is(notNullValue()));
            assertThat(key.getAlgorithm(), is("RSA"));
        }

        @Test
        void pkcs8EcKeyIsAccepted() throws Exception {
            PrivateKey key = WebhookServer.loadPrivateKey(PKCS8_EC);
            assertThat(key, is(notNullValue()));
            assertThat(key.getAlgorithm(), is("EC"));
        }

        @Test
        void sec1EcKeyIsAccepted() throws Exception {
            PrivateKey key = WebhookServer.loadPrivateKey(SEC1_EC);
            assertThat(key, is(notNullValue()));
            assertThat(key.getAlgorithm(), is("EC"));
        }

        @Test
        void nonKeyPemIsRejectedWithClearMessage() {
            // No PEM object at all — PEMParser returns null and the loader must
            // reject it with a clear message rather than a NullPointerException.
            String notAKey = "this file contains no PEM private key\n";
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> WebhookServer.loadPrivateKey(notAKey));
            assertThat(ex.getMessage(), containsString("no supported PEM private key"));
        }
    }

    // ============================================================
    // Helpers
    // ============================================================

    private HttpsURLConnection createConnection(String path, String method) throws Exception {
        URL url = URI.create("https://127.0.0.1:" + port + path).toURL();
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.setSSLSocketFactory(clientSslContext.getSocketFactory());
        // TEST-ONLY: accept any hostname for this in-process test client. It connects to the
        // webhook test server on 127.0.0.1 using an ephemeral self-signed certificate, where
        // hostname verification is not meaningful. This is not production code — the real
        // webhook serves TLS normally and the kube-apiserver verifies it via the configured
        // caBundle. The corresponding CodeQL alert (java/unsafe-hostname-verification) is
        // dismissed as "used in tests".
        conn.setHostnameVerifier((hostname, session) -> true);
        conn.setRequestMethod(method);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        return conn;
    }

    private static String buildAdmissionReview(String uid, boolean optIn, boolean alreadyInjected) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("  \"apiVersion\": \"admission.k8s.io/v1\",");
        sb.append("  \"kind\": \"AdmissionReview\",");
        sb.append("  \"request\": {");
        sb.append("    \"uid\": \"").append(uid).append("\",");
        sb.append("    \"kind\": { \"group\": \"\", \"version\": \"v1\", \"kind\": \"Pod\" },");
        sb.append("    \"resource\": { \"group\": \"\", \"version\": \"v1\", \"resource\": \"pods\" },");
        sb.append("    \"operation\": \"CREATE\",");
        sb.append("    \"object\": {");
        sb.append("      \"apiVersion\": \"v1\",");
        sb.append("      \"kind\": \"Pod\",");
        sb.append("      \"metadata\": {");
        sb.append("        \"name\": \"test-pod\",");
        sb.append("        \"namespace\": \"default\"");
        if (optIn || alreadyInjected) {
            sb.append(",        \"annotations\": {");
            sb.append("          \"mockserver.org/inject\": \"true\"");
            if (alreadyInjected) {
                sb.append(",          \"mockserver.org/injected\": \"true\"");
            }
            sb.append("        }");
        }
        sb.append("      },");
        sb.append("      \"spec\": {");
        sb.append("        \"containers\": [");
        sb.append("          { \"name\": \"app\", \"image\": \"myapp:latest\" }");
        sb.append("        ]");
        sb.append("      }");
        sb.append("    }");
        sb.append("  }");
        sb.append("}");
        return sb.toString();
    }

}
