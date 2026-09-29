package org.mockserver.benchmark;

import org.mockserver.netty.MockServer;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.mockserver.stop.Stop.stopQuietly;

/**
 * End-to-end TLS handshakes per second against a real in-process {@link MockServer}: every op opens a new
 * TCP connection, completes a TLS handshake presenting SNI {@code localhost}, and closes — the
 * {@code noConnectionReuse} shape. {@link #resumption} {@code false} invalidates each client session so
 * every handshake is a full one; {@code true} lets the JDK client resume, which shrinks the crypto and so
 * makes per-handshake server bookkeeping (SNI lookup, context-cache check) a larger share.
 *
 * <p>Client and server share the machine, so this is a relative before/after figure, not a capacity number.
 *
 * <pre>./run.sh TlsHandshakeBenchmark</pre>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@Threads(4)
@Fork(1)
public class TlsHandshakeBenchmark {

    @Param({"false", "true"})
    public boolean resumption;

    private MockServer mockServer;
    private int port;
    private SSLContext sslContext;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        mockServer = new MockServer();
        port = mockServer.getLocalPort();
        sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new TrustManager[]{new TrustAllManager()}, new SecureRandom());
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        stopQuietly(mockServer);
    }

    @Benchmark
    public boolean handshake() throws Exception {
        try (SSLSocket socket = (SSLSocket) sslContext.getSocketFactory().createSocket("127.0.0.1", port)) {
            SSLParameters parameters = socket.getSSLParameters();
            parameters.setServerNames(List.of(new SNIHostName("localhost")));
            socket.setSSLParameters(parameters);
            socket.startHandshake();
            boolean valid = socket.getSession().isValid();
            if (!resumption) {
                socket.getSession().invalidate();
            }
            return valid;
        }
    }

    private static final class TrustAllManager implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
