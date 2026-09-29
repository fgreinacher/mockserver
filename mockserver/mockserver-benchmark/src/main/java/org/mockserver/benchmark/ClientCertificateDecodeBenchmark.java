package org.mockserver.benchmark;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.util.LazyX509Certificate;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mappers.FullHttpRequestToMockServerHttpRequest;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.Protocol;
import org.mockserver.socket.tls.KeyAndCertificateFactory;
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
import org.openjdk.jmh.annotations.Warmup;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static org.mockserver.socket.tls.KeyAndCertificateFactoryFactory.createKeyAndCertificateFactory;

/**
 * {@link InboundDecodeBenchmark}'s request decode (1 KiB JSON body) on a connection whose client presented a
 * certificate chain (leaf + CA). The server accepts one by default ({@code ClientAuth.OPTIONAL}); the mapper
 * is built once per connection holding the same {@code Certificate[]}, exactly as here.
 * {@code jdk} presents the chain as the JDK {@code SSLEngine} does ({@code X509CertImpl}), {@code openssl}
 * as the default OpenSSL engine does (Netty {@code LazyX509Certificate}); {@code none} is the control.
 *
 * <p>A separate class, not a {@code @Param} on {@link InboundDecodeBenchmark}: that class's rows are pinned
 * by the per-merge allocation gate and the daily microbench row count and result keys.
 *
 * <pre>./run.sh -prof gc ClientCertificateDecodeBenchmark</pre>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class ClientCertificateDecodeBenchmark {

    @Param({"none", "jdk", "openssl"})
    public String clientCertificates;

    private FullHttpRequestToMockServerHttpRequest mapper;
    private byte[] payload;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        mapper = new FullHttpRequestToMockServerHttpRequest(
            Configuration.configuration(),
            new MockServerLogger(),
            true,
            clientCertificateChain(clientCertificates),
            1080
        );
        payload = "{\"id\":1,\"name\":\"record-1\",\"payload\":\"yyyyyyyyyyyyyyyy\"}".repeat(18).getBytes(StandardCharsets.UTF_8);
    }

    private static Certificate[] clientCertificateChain(String type) throws Exception {
        if ("none".equals(type)) {
            return null;
        }
        KeyAndCertificateFactory factory = createKeyAndCertificateFactory(Configuration.configuration(), new MockServerLogger());
        factory.buildAndSavePrivateKeyAndX509Certificate();
        byte[][] encoded = {factory.x509Certificate().getEncoded(), factory.certificateAuthorityX509Certificate().getEncoded()};
        Certificate[] chain = new Certificate[encoded.length];
        CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
        for (int i = 0; i < encoded.length; i++) {
            chain[i] = "openssl".equals(type)
                ? new LazyX509Certificate(encoded[i])
                : certificateFactory.generateCertificate(new ByteArrayInputStream(encoded[i]));
        }
        return chain;
    }

    @Benchmark
    public HttpRequest mapRequestOnConnection() {
        FullHttpRequest nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/api/orders", Unpooled.wrappedBuffer(payload));
        nettyRequest.headers().set(CONTENT_TYPE, "application/json");
        try {
            return mapper.mapFullHttpRequestToMockServerRequest(nettyRequest, null, null, null, Protocol.HTTP_1_1);
        } finally {
            nettyRequest.release();
        }
    }
}
