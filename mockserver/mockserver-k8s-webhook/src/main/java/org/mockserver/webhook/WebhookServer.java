package org.mockserver.webhook;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Collection;
import java.util.concurrent.Executors;

/**
 * Kubernetes MutatingAdmissionWebhook HTTPS server.
 * <p>
 * Starts an HTTPS server that:
 * <ul>
 *   <li>{@code POST /inject} — handles AdmissionReview requests (delegates to {@link AdmissionReviewHandler})</li>
 *   <li>{@code GET /healthz} — returns 200 for readiness/liveness probes</li>
 * </ul>
 * <p>
 * TLS cert + key are loaded from file paths (env-configurable). Sidecar injection config
 * is read from environment variables so Helm values flow through.
 */
public class WebhookServer {

    private static final Logger LOG = LoggerFactory.getLogger(WebhookServer.class);

    // Environment variable names (matching the Helm webhook-deployment.yaml)
    static final String ENV_PORT = "WEBHOOK_PORT";
    static final String ENV_TLS_CERT = "WEBHOOK_TLS_CERT_FILE";
    static final String ENV_TLS_KEY = "WEBHOOK_TLS_KEY_FILE";
    static final String ENV_MOCKSERVER_IMAGE = "MOCKSERVER_IMAGE";
    static final String ENV_IPTABLES_IMAGE = "IPTABLES_IMAGE";
    static final String ENV_MOCKSERVER_PORT = "MOCKSERVER_PORT";
    static final String ENV_REDIRECT_PORTS = "REDIRECT_PORTS";
    static final String ENV_EXCLUDE_UID = "EXCLUDE_UID";
    static final String ENV_LOG_LEVEL = "LOG_LEVEL";

    // Defaults
    static final int DEFAULT_PORT = 8443;
    static final String DEFAULT_CERT_PATH = "/etc/webhook/tls/tls.crt";
    static final String DEFAULT_KEY_PATH = "/etc/webhook/tls/tls.key";

    private final HttpsServer server;
    private final AdmissionReviewHandler handler;

    /**
     * Creates a WebhookServer with an already-configured HttpsServer and handler.
     * Primarily for testing.
     */
    WebhookServer(HttpsServer server, AdmissionReviewHandler handler) {
        this.server = server;
        this.handler = handler;
        configureRoutes();
    }

    /**
     * Creates a WebhookServer that binds to the given address using the provided SSLContext.
     *
     * @param bindAddress the address/port to bind to
     * @param sslContext  the SSL context for HTTPS
     * @param config      the sidecar injection configuration
     * @throws IOException if the server cannot bind
     */
    public WebhookServer(InetSocketAddress bindAddress, SSLContext sslContext, SidecarInjectionConfig config) throws IOException {
        this.server = HttpsServer.create(bindAddress, 0);
        this.server.setHttpsConfigurator(new HttpsConfigurator(sslContext));
        this.server.setExecutor(Executors.newFixedThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors())
        ));
        this.handler = new AdmissionReviewHandler(config);
        configureRoutes();
    }

    private void configureRoutes() {
        server.createContext("/inject", this::handleInject);
        server.createContext("/healthz", this::handleHealthz);
    }

    /**
     * Starts the HTTPS server.
     */
    public void start() {
        server.start();
        InetSocketAddress addr = server.getAddress();
        LOG.info("webhook server started on {}:{}", addr.getHostString(), addr.getPort());
    }

    /**
     * Stops the HTTPS server gracefully.
     *
     * @param delaySeconds seconds to wait for in-flight requests
     */
    public void stop(int delaySeconds) {
        LOG.info("stopping webhook server");
        server.stop(delaySeconds);
    }

    /**
     * Returns the port the server is listening on. Useful for tests with ephemeral ports.
     */
    public int getPort() {
        return server.getAddress().getPort();
    }

    void handleInject(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, "Method Not Allowed");
                return;
            }

            byte[] requestBody;
            try (InputStream is = exchange.getRequestBody()) {
                requestBody = is.readAllBytes();
            }

            if (requestBody.length == 0) {
                byte[] errorResponse = handler.createAllowWithMessage("",
                    "empty request body");
                sendJsonResponse(exchange, 200, errorResponse);
                return;
            }

            byte[] response;
            try {
                response = handler.handleAdmissionReview(requestBody);
            } catch (Exception e) {
                LOG.warn("failed to process admission review, allowing pod", e);
                // On error, allow the pod through (fail-open) with a message
                response = handler.createAllowWithMessage("",
                    "webhook error: " + e.getMessage());
            }

            sendJsonResponse(exchange, 200, response);

        } catch (Exception e) {
            LOG.error("unexpected error handling /inject", e);
            sendResponse(exchange, 500, "Internal Server Error");
        }
    }

    void handleHealthz(HttpExchange exchange) throws IOException {
        sendResponse(exchange, 200, "ok");
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendJsonResponse(HttpExchange exchange, int statusCode, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    // ================================================================
    // TLS certificate loading
    // ================================================================

    /**
     * Builds an SSLContext from PEM-encoded certificate and private key files.
     *
     * @param certPath path to the PEM certificate file (may contain a chain)
     * @param keyPath  path to the PEM private key file (PKCS#8, PKCS#1 or SEC1 EC)
     * @return a configured SSLContext
     */
    public static SSLContext buildSslContext(Path certPath, Path keyPath) throws Exception {
        // Load certificates
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Collection<? extends Certificate> certs;
        try (InputStream certIn = Files.newInputStream(certPath)) {
            certs = cf.generateCertificates(certIn);
        }
        if (certs.isEmpty()) {
            throw new IllegalArgumentException("no certificates found in " + certPath);
        }

        // Load private key
        String keyPem = Files.readString(keyPath, StandardCharsets.UTF_8);
        PrivateKey privateKey = loadPrivateKey(keyPem);

        // Build KeyStore
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        Certificate[] certChain = certs.toArray(new Certificate[0]);
        ks.setKeyEntry("webhook", privateKey, new char[0], certChain);

        // Build SSLContext
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, new char[0]);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), null, null);
        return sslContext;
    }

    /**
     * Parses a PEM-encoded private key in any of the standard unencrypted forms,
     * for both RSA and EC keys:
     * <ul>
     *   <li>PKCS#8  — {@code -----BEGIN PRIVATE KEY-----} (e.g. {@code openssl genpkey})</li>
     *   <li>PKCS#1  — {@code -----BEGIN RSA PRIVATE KEY-----} (e.g. cert-manager's default RSA encoding)</li>
     *   <li>SEC1 EC — {@code -----BEGIN EC PRIVATE KEY-----} (e.g. kube-webhook-certgen's ECDSA key)</li>
     * </ul>
     * Accepting all three decouples the webhook server from the exact tool that
     * generates its serving key, so the Helm chart's TLS bootstrap image can
     * change without breaking the handler. Encrypted keys are not supported.
     */
    static PrivateKey loadPrivateKey(String pem) throws Exception {
        final Object parsed;
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            parsed = parser.readObject();
        }
        JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
        if (parsed instanceof PEMKeyPair) {
            // PKCS#1 (BEGIN RSA PRIVATE KEY) and SEC1 (BEGIN EC PRIVATE KEY)
            return converter.getKeyPair((PEMKeyPair) parsed).getPrivate();
        }
        if (parsed instanceof PrivateKeyInfo) {
            // PKCS#8 (BEGIN PRIVATE KEY)
            return converter.getPrivateKey((PrivateKeyInfo) parsed);
        }
        throw new IllegalArgumentException(
            "no supported PEM private key found (expected an unencrypted PKCS#8, "
                + "PKCS#1 or SEC1 key); parsed object was "
                + (parsed == null ? "null" : parsed.getClass().getName())
        );
    }

    // ================================================================
    // Configuration from environment
    // ================================================================

    /**
     * Reads sidecar injection configuration from environment variables.
     */
    static SidecarInjectionConfig configFromEnv() {
        SidecarInjectionConfig config = new SidecarInjectionConfig();

        String image = System.getenv(ENV_MOCKSERVER_IMAGE);
        if (image != null && !image.isEmpty()) {
            config.setMockserverImage(image);
        }

        String iptablesImage = System.getenv(ENV_IPTABLES_IMAGE);
        if (iptablesImage != null && !iptablesImage.isEmpty()) {
            config.setIptablesImage(iptablesImage);
        }

        String port = System.getenv(ENV_MOCKSERVER_PORT);
        if (port != null && !port.isEmpty()) {
            try {
                config.setServerPort(Integer.parseInt(port));
            } catch (NumberFormatException e) {
                LOG.warn("invalid integer for {}={}, using default {}", ENV_MOCKSERVER_PORT, port, config.getServerPort(), e);
            }
        }

        String redirectPorts = System.getenv(ENV_REDIRECT_PORTS);
        if (redirectPorts != null && !redirectPorts.isEmpty()) {
            config.setRedirectPorts(redirectPorts);
        }

        String uid = System.getenv(ENV_EXCLUDE_UID);
        if (uid != null && !uid.isEmpty()) {
            try {
                config.setRunAsUser(Integer.parseInt(uid));
            } catch (NumberFormatException e) {
                LOG.warn("invalid integer for {}={}, using default {}", ENV_EXCLUDE_UID, uid, config.getRunAsUser(), e);
            }
        }

        String logLevel = System.getenv(ENV_LOG_LEVEL);
        if (logLevel != null && !logLevel.isEmpty()) {
            config.setLogLevel(logLevel);
        }

        return config;
    }

    // ================================================================
    // Main entry point
    // ================================================================

    public static void main(String[] args) throws Exception {
        int port = DEFAULT_PORT;
        String portEnv = System.getenv(ENV_PORT);
        if (portEnv != null && !portEnv.isEmpty()) {
            try {
                port = Integer.parseInt(portEnv);
            } catch (NumberFormatException e) {
                LOG.warn("invalid integer for {}={}, using default {}", ENV_PORT, portEnv, DEFAULT_PORT, e);
            }
        }

        String certPath = System.getenv(ENV_TLS_CERT);
        if (certPath == null || certPath.isEmpty()) {
            certPath = DEFAULT_CERT_PATH;
        }

        String keyPath = System.getenv(ENV_TLS_KEY);
        if (keyPath == null || keyPath.isEmpty()) {
            keyPath = DEFAULT_KEY_PATH;
        }

        LOG.info("loading TLS certificate from {} and key from {}", certPath, keyPath);
        SSLContext sslContext = buildSslContext(Path.of(certPath), Path.of(keyPath));

        SidecarInjectionConfig config = configFromEnv();
        LOG.info("sidecar config: image={}, port={}, redirectPorts={}, uid={}, logLevel={}",
            config.getMockserverImage(), config.getServerPort(),
            config.getRedirectPorts(), config.getRunAsUser(), config.getLogLevel());

        WebhookServer webhookServer = new WebhookServer(
            new InetSocketAddress("0.0.0.0", port), sslContext, config
        );

        // Shutdown hook for graceful stop
        Runtime.getRuntime().addShutdownHook(new Thread(() -> webhookServer.stop(3)));

        webhookServer.start();
    }
}
