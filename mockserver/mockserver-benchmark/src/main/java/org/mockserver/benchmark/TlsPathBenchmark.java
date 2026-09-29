package org.mockserver.benchmark;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.util.concurrent.Future;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.socket.tls.HostSubjectAlternativeNames;
import org.mockserver.socket.tls.NettySslContextFactory;
import org.mockserver.socket.tls.SniHandler;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

import java.util.concurrent.TimeUnit;

/**
 * Per-handshake and per-request TLS bookkeeping, with the server context already built (the steady state):
 * <ul>
 *   <li>{@link #sniLookupCachedContext()} — {@code SniHandler.lookup} (on a new handler) for a host whose SAN is
 *       already on the certificate: what every TLS handshake pays before the {@code SslHandler} is installed.</li>
 *   <li>{@link #createServerSslContextCached()} — the cache-validity check inside
 *       {@code NettySslContextFactory.createServerSslContext}.</li>
 *   <li>{@link #hostHeaderSubjectAlternativeName()} — the per-request SAN bookkeeping
 *       ({@code Configuration.addSubjectAlternativeName}) that {@code HttpRequestHandler} runs for every
 *       request's {@code Host} header, TLS or not, before it was memoised per connection;</li>
 *   <li>{@link #hostHeaderSubjectAlternativeNameMemoised()} — the same bookkeeping as
 *       {@code HttpRequestHandler} now runs it, via {@link HostSubjectAlternativeNames}.</li>
 * </ul>
 *
 * <pre>./run.sh -prof gc TlsPathBenchmark</pre>
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class TlsPathBenchmark {

    private static final String HOST = "localhost";

    private Configuration configuration;
    private NettySslContextFactory nettySslContextFactory;
    private ExposedSniHandler sniHandler;
    private EmbeddedChannel channel;
    private ChannelHandlerContext ctx;
    private EmbeddedChannel connection;

    /** Exposes the protected lookup so the benchmark drives the production method itself. */
    static final class ExposedSniHandler extends SniHandler {
        ExposedSniHandler(Configuration configuration, NettySslContextFactory nettySslContextFactory) {
            super(configuration, nettySslContextFactory);
        }

        Future<SslContext> lookupHost(ChannelHandlerContext ctx, String hostname) {
            return lookup(ctx, hostname);
        }
    }

    @Setup(Level.Trial)
    public void setup() {
        configuration = Configuration.configuration();
        nettySslContextFactory = new NettySslContextFactory(configuration, new MockServerLogger(), true);
        configuration.addSubjectAlternativeName(HOST);
        nettySslContextFactory.createServerSslContext();
        sniHandler = new ExposedSniHandler(configuration, nettySslContextFactory);
        channel = new EmbeddedChannel(sniHandler);
        ctx = channel.pipeline().context(sniHandler);
        connection = new EmbeddedChannel();
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        channel.finishAndReleaseAll();
        connection.finishAndReleaseAll();
    }

    @Benchmark
    public SslContext sniLookupCachedContext() {
        // a fresh handler per op, as in production (one SniHandler, one lookup, per connection): a reused
        // handler keeps its first lookup's completed future in its in-flight map and returns it forever
        Future<SslContext> future = new ExposedSniHandler(configuration, nettySslContextFactory).lookupHost(ctx, HOST);
        // the EmbeddedChannel's executor is the calling thread, so await() would throw; spin instead
        while (!future.isDone()) {
            Thread.onSpinWait();
        }
        return future.getNow();
    }

    @Benchmark
    public SslContext createServerSslContextCached() {
        return nettySslContextFactory.createServerSslContext();
    }

    @Benchmark
    public Configuration hostHeaderSubjectAlternativeName() {
        configuration.addSubjectAlternativeName("localhost:1080");
        return configuration;
    }

    @Benchmark
    public Configuration hostHeaderSubjectAlternativeNameMemoised() {
        HostSubjectAlternativeNames.record(configuration, connection, "localhost:1080");
        return configuration;
    }
}
