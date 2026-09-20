package org.mockserver.benchmark;

import com.sun.management.OperatingSystemMXBean;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.ReferenceCountUtil;

import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Programme item 21 — the <b>connection-scaling ceiling</b>: how many concurrent established
 * connections a MockServer holds before request latency degrades.
 *
 * <p><b>Why this needs its own driver.</b> k6 allocates a virtual user per connection and is not
 * built to park tens of thousands of idle ones, so the plan called for a purpose-built holder. This
 * is it: it opens N real connections, proves they are established <em>on the server</em>, parks them
 * idle, and then measures request latency on a <b>separate</b> connection. The held connections
 * generate no traffic — the axis under test is connection <em>state</em>, not offered load.
 *
 * <p><b>The rig is measured before the server is.</b> This programme's recurring failure is a number
 * that turns out to describe the client or the harness, so the client-side limits are established
 * first and every rung carries its own validity block:
 * <ul>
 *   <li><b>Two different client ceilings, and the first one is invisible.</b> {@code CEILING_MODE=calibrate}
 *       opens connections until the kernel refuses and reports <em>which</em> resource ran out, because
 *       file descriptors and source ports look alike in a stack trace and mean opposite things:
 *       <ul>
 *         <li><b>File descriptors.</b> The JDK does not simply inherit the shell's limit, and what it
 *             does instead is <b>opposite on the two platforms</b> — both measured, so do not carry the
 *             flag across:
 *             <ul>
 *               <li><b>macOS</b>: the JDK sets {@code RLIMIT_NOFILE} to {@code min(hard, OPEN_MAX)} =
 *                   <b>10,240</b> however high {@code ulimit -n} reads (a 60,000 shell still yields
 *                   10,240), so an un-flagged JVM stops at ~9,977 connections. That is the JDK, not the
 *                   server — and it binds the <b>server</b> JVM as hard as the driver, so <b>both</b>
 *                   need {@code -XX:-MaxFDLimit}, <em>paired with</em> a raised {@code ulimit -n}: the
 *                   flag leaves the inherited soft limit alone (2,048 stays 2,048).</li>
 *               <li><b>Linux</b>: the JDK already raises the soft limit to the <b>hard</b> limit
 *                   (1,024 to 1,048,576 measured), so no flag is needed. Passing
 *                   {@code -XX:-MaxFDLimit} there <b>disables</b> that raise and leaves 1,024.</li>
 *             </ul></li>
 *         <li><b>Source ports.</b> With the clamp lifted the wall moves to the ephemeral range
 *             (49152-65535 on macOS), measured here at <b>15,511</b> connections. Spreading across four
 *             destination ports reached 15,609 — a ratio of <b>1.01</b>, so on this kernel source ports
 *             come from <em>one global range</em> and giving the server more ports buys nothing. The
 *             multi-port argument is kept only because it costs nothing and does hold on kernels that
 *             key the range by destination.</li>
 *       </ul>
 *       {@link #ephemeralHeadroom} refuses a rung above the measured ceiling rather than letting it fail
 *       as connect errors that read like a server limit.</li>
 *   <li><b>Establishment is proven twice, client and server.</b> Client side: N distinct local ports.
 *       Server side: the event log recorded N received requests — every parked connection completed a
 *       real request before going idle, so the server accepted, read, matched and answered on it. A
 *       connection that only completed a TCP handshake would pass the first proof and fail the second.</li>
 *   <li><b>Client CPU headroom</b> is sampled across the probe window. A saturated driver measures
 *       itself, so a rung whose driver CPU has no headroom is marked {@code rig_valid:false} and its
 *       latency is not a server figure.</li>
 * </ul>
 *
 * <p><b>Every rung is paired with its own baseline.</b> A rung's latency means nothing in isolation on
 * a laptop, so each rung is preceded by a zero-connection sample and reported as a ratio to <em>that</em>
 * one. A single baseline at the start of the run is not enough: measured that way this box drifted
 * <b>35%</b> faster across one ladder — larger than any effect the ladder was looking for, and
 * monotonic, so every higher rung appeared to be an improvement. Pairing cancels drift slower than a
 * pair; the first-to-last baseline gap is still printed, as a measure of how much the box moved. The
 * discarded warm-up exists for the same reason at a shorter timescale: the first sample of an un-warmed
 * driver reads several times steady state.
 *
 * <p><b>Scope.</b> Plaintext HTTP/1.1 keep-alive ({@code h1}) and the same over TLS ({@code tls}),
 * where the extra axis is the server's per-connection session and {@code SSLEngine} state. HTTP/2 is
 * deliberately <b>not</b> covered here: its connection axis is streams-per-connection, which
 * {@link Http2ConnectionMemoryBenchmark} already measures on the memory axis, and mixing the two
 * would confuse "connections held" with "streams held".
 *
 * <p>Usage: {@code ConnectionCeilingBenchmark <host> <port[,port...]>}, configured by environment:
 * <pre>
 *   CEILING_MODE            h1 (default) | tls
 *   CEILING_LADDER          comma-separated connection counts, e.g. 250,1000,4000,8000
 *   CEILING_PROBE_REQUESTS  sequential requests per latency sample (default 300)
 *   CEILING_SETTLE_MS       pause after parking a rung before probing (default 500)
 *   CEILING_WARMUP_REQUESTS discarded requests run before the first rung (default 2000)
 * </pre>
 * Results are printed as one JSON document on stdout, after a human-readable table.
 */
public class ConnectionCeilingBenchmark {

    /** Path the parked connections and the probe both request; served by one expectation. */
    private static final String PATH = "/ceiling";

    /**
     * Ephemeral range size assumed when {@code CEILING_CLIENT_CEILING} is not set — macOS's default
     * 49152-65535. Deliberately NOT multiplied by the number of destination ports: whether the kernel
     * keeps a separate source-port space per destination is a property of the kernel, not something to
     * assume, and {@code CEILING_MODE=calibrate} measures it. Assuming the multiplier is what let a
     * ladder ask for more connections than the box could give and fail as a {@code BindException}
     * mid-rung, which reads like a server limit and is not one.
     */
    private static final int DEFAULT_EPHEMERAL_RANGE = 16_384;

    /**
     * Fraction of the ephemeral range a rung may claim. Well under 1.0 because the box has its own
     * connections and because closed sockets sit in TIME_WAIT holding their port for tens of seconds.
     */
    private static final double EPHEMERAL_SAFETY_FACTOR = 0.80;

    /** Below this fraction of one core free, the driver is too busy for its latency to be the server's. */
    private static final double MIN_CLIENT_CPU_HEADROOM = 0.15;

    /** Parked connections confirmed per control-plane read, keeping the event log (and the read) small. */
    private static final int CONFIRM_BATCH = 500;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: ConnectionCeilingBenchmark <host> <port[,port...]>");
            System.exit(2);
        }
        String host = args[0];
        int[] ports = parsePorts(args[1]);
        String mode = env("CEILING_MODE", "h1").toLowerCase(Locale.ROOT);
        if (!"h1".equals(mode) && !"tls".equals(mode) && !"calibrate".equals(mode)) {
            System.err.println("CEILING_MODE must be h1, tls or calibrate, was: " + mode);
            System.exit(2);
        }
        if ("calibrate".equals(mode)) {
            calibrate(host, ports);
            return;
        }
        int[] ladder = parseLadder(env("CEILING_LADDER", "250,1000,4000,8000"));
        int probeRequests = Integer.parseInt(env("CEILING_PROBE_REQUESTS", "300"));
        long settleMs = Long.parseLong(env("CEILING_SETTLE_MS", "500"));
        int warmupRequests = Integer.parseInt(env("CEILING_WARMUP_REQUESTS", "2000"));

        preloadFailureReportingClasses();

        int headroom = ephemeralHeadroom();
        for (int rung : ladder) {
            if (rung > headroom) {
                System.err.println("RIG REFUSES RUNG: " + rung + " connections exceeds this driver's usable"
                    + " headroom of " + headroom + " — " + (int) (EPHEMERAL_SAFETY_FACTOR * 100) + "% of a"
                    + " ceiling of " + (headroom / EPHEMERAL_SAFETY_FACTOR) + ", the remainder left for the"
                    + " previous rung's TIME_WAIT sockets. Run CEILING_MODE=calibrate to measure this box's"
                    + " ceiling and pass it as CEILING_CLIENT_CEILING, or lower the ladder — do not read the"
                    + " resulting connect failures as a server ceiling.");
                System.exit(2);
            }
        }

        // The control plane is spoken over raw HTTP rather than MockServerClient so the driver stays
        // usable against a server built from a different version of this repo.
        HttpClient controlPlane = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        controlPlanePut(controlPlane, host, ports[0], "/mockserver/expectation",
            "{\"httpRequest\":{\"path\":\"" + PATH + "\"},"
                + "\"httpResponse\":{\"statusCode\":200,\"body\":\"c\"},"
                + "\"times\":{\"unlimited\":true}}");

        SslContext sslContext = "tls".equals(mode)
            ? SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build()
            : null;

        NioEventLoopGroup group = new NioEventLoopGroup();
        List<Rung> results = new ArrayList<>();
        try {
            warmUp(group, sslContext, host, ports[0], warmupRequests);
            long drainMs = Long.parseLong(env("CEILING_TIMEWAIT_DRAIN_MS", "45000"));
            // Every rung is PAIRED with a zero-connection baseline measured immediately before it, and the
            // ratio reported is against that partner rather than against one baseline for the whole run.
            // A single run-start baseline is not good enough here: measured that way the box drifted 35%
            // across one ladder — larger than any effect the ladder was looking for, and monotonic, so it
            // made higher rungs look progressively FASTER. Pairing cancels drift that is slow relative to
            // a pair, and the first-versus-last baseline printed below still says how much drift there was.
            for (int rung : ladder) {
                // A rung's closed sockets hold their source ports in TIME_WAIT for 2*MSL, so the next rung
                // starts with less of the range than the last one had. Waiting keeps the ladder from
                // exhausting the rig part-way up and calling it a server ceiling.
                System.err.println("draining TIME_WAIT for " + drainMs + " ms before rung " + rung);
                Thread.sleep(drainMs);
                System.err.println("rung " + rung + ": baseline");
                Rung pairedBaseline = measureRung(group, sslContext, controlPlane, host, ports, 0, probeRequests, settleMs);
                results.add(pairedBaseline);
                System.err.println("rung " + rung + ": parking connections");
                Rung measured = measureRung(group, sslContext, controlPlane, host, ports, rung, probeRequests, settleMs);
                measured.pairedBaselineP50Micros = pairedBaseline.p50Micros;
                results.add(measured);
                System.err.println("rung " + rung + ": done");
            }
        } finally {
            group.shutdownGracefully(0, 2, TimeUnit.SECONDS).sync();
        }

        report(results, mode, ports.length, probeRequests);
    }

    /**
     * Discarded requests on a throwaway connection, so the first measured rung is not the one paying for
     * class loading and JIT. Without this the first rung-0 sample read ~2.5x the last one on the same box,
     * which would have presented as every held-connection rung being faster than no connections at all.
     */
    private static void warmUp(NioEventLoopGroup group, SslContext sslContext, String host, int port, int requests)
        throws Exception {
        if (requests <= 0) {
            return;
        }
        Channel channel = connect(group, sslContext, host, port);
        try {
            for (int i = 0; i < requests; i++) {
                requestOnce(channel, host, port).get(30, TimeUnit.SECONDS);
            }
        } finally {
            channel.close().sync();
        }
    }

    /** One ladder rung: park {@code connections} idle, then time {@code probeRequests} on a fresh connection. */
    private static Rung measureRung(NioEventLoopGroup group, SslContext sslContext, HttpClient controlPlane,
                                    String host, int[] ports, int connections, int probeRequests,
                                    long settleMs) throws Exception {
        List<Channel> parked = new ArrayList<>(connections);
        Set<Integer> distinctLocalPorts = new LinkedHashSet<>();
        long serverConfirmed;
        try {
            controlPlanePut(controlPlane, host, ports[0], "/mockserver/clear?type=LOG", "");
            // The server-side proof is accumulated in batches rather than read once at the end. The event
            // log is byte-bounded, so at a large rung a single read would silently be short by however much
            // the log had evicted — a harness that reports fewer connections than it opened and cannot tell
            // that from a server that dropped them.
            serverConfirmed = 0;
            int confirmedSinceClear = 0;
            for (int i = 0; i < connections; i++) {
                int port = ports[i % ports.length];
                Channel channel;
                try {
                    channel = connect(group, sslContext, host, port);
                } catch (Exception e) {
                    // The rig ran out, not the server. Recording where it ran out is a result; dying with
                    // a stack trace is not, and a reader of the crash cannot tell the two apart.
                    Wall.Kind kind = classify(e);
                    System.err.println("RIG EXHAUSTED at " + i + " of " + connections + " connections ("
                        + kind + ": " + describe(e) + "). This rung and every higher one are the driver's"
                        + " limit, not the server's — the previous rungs above are still valid."
                        + (kind == Wall.Kind.PORTS
                            ? " Source ports are held in TIME_WAIT for 2*MSL after a rung closes, so raise"
                              + " CEILING_TIMEWAIT_DRAIN_MS or shorten the ladder."
                            : " Raise the file-descriptor limit — on macOS `ulimit -n` PLUS"
                              + " -XX:-MaxFDLimit; on Linux raise the HARD limit and pass no flag,"
                              + " because there the flag lowers the limit instead of raising it."));
                    return Rung.rigExhausted(connections, i, kind);
                }
                // One completed request per parked connection. This is what makes it a CONNECTION the
                // server has actually accepted, read from and answered on — not a socket that only
                // finished a TCP (or TLS) handshake and may be sitting in an accept backlog.
                int status = requestOnce(channel, host, port).get(30, TimeUnit.SECONDS);
                if (status != 200) {
                    throw new IllegalStateException("parked connection " + i + " got status " + status);
                }
                parked.add(channel);
                distinctLocalPorts.add(((InetSocketAddress) channel.localAddress()).getPort());
                if (++confirmedSinceClear == CONFIRM_BATCH) {
                    serverConfirmed += drainRecordedRequests(controlPlane, host, ports[0], confirmedSinceClear);
                    confirmedSinceClear = 0;
                }
            }
            if (confirmedSinceClear > 0) {
                serverConfirmed += drainRecordedRequests(controlPlane, host, ports[0], confirmedSinceClear);
            }
            if (distinctLocalPorts.size() != connections) {
                throw new IllegalStateException("HARNESS VALIDATION FAILED: " + distinctLocalPorts.size()
                    + " distinct local ports for " + connections + " connections — sockets were reused");
            }
            if (serverConfirmed != connections) {
                throw new IllegalStateException("HARNESS VALIDATION FAILED: server recorded " + serverConfirmed
                    + " requests, expected " + connections + " — connections are not established server-side");
            }
            // Drop the log before probing so a large event log cannot be what the probe is measuring.
            controlPlanePut(controlPlane, host, ports[0], "/mockserver/clear?type=LOG", "");
            for (long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(settleMs);
                 System.nanoTime() < deadline; ) {
                Thread.sleep(10);
            }

            // The establishment proofs above are taken at PARK time. Nothing so far shows the parked
            // connections are still open when the probe runs — and a rung whose connections quietly
            // dropped in between would still report rig_valid:true, biasing toward the "no degradation"
            // conclusion this harness reached. Re-assert immediately before measuring.
            long stillParked = parked.stream().filter(Channel::isActive).count();
            if (stillParked != connections) {
                throw new IllegalStateException("HARNESS VALIDATION FAILED: " + stillParked + " of "
                    + connections + " parked connections were still open at probe time — the rung was not"
                    + " holding what it claims to measure");
            }

            Channel probe = connect(group, sslContext, host, ports[0]);
            try {
                OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
                long cpuBefore = os.getProcessCpuTime();
                long wallBefore = System.nanoTime();

                long[] latenciesNanos = new long[probeRequests];
                int errors = 0;
                for (int i = 0; i < probeRequests; i++) {
                    long start = System.nanoTime();
                    int status = requestOnce(probe, host, ports[0]).get(30, TimeUnit.SECONDS);
                    latenciesNanos[i] = System.nanoTime() - start;
                    if (status != 200) {
                        errors++;
                    }
                }

                long cpuUsed = os.getProcessCpuTime() - cpuBefore;
                long wallUsed = System.nanoTime() - wallBefore;
                int cores = Runtime.getRuntime().availableProcessors();
                // Headroom as a fraction of the whole machine: 1.0 means the driver used no CPU at all.
                double clientCpuHeadroom = 1.0 - ((double) cpuUsed / (double) (wallUsed * cores));

                Arrays.sort(latenciesNanos);
                return new Rung(connections, distinctLocalPorts.size(), serverConfirmed, errors,
                    percentileMicros(latenciesNanos, 0.50), percentileMicros(latenciesNanos, 0.99),
                    clientCpuHeadroom);
            } finally {
                probe.close().sync();
            }
        } finally {
            for (Channel channel : parked) {
                channel.close();
            }
        }
    }

    private static Channel connect(NioEventLoopGroup group, SslContext sslContext, String host, int port)
        throws InterruptedException {
        Bootstrap bootstrap = new Bootstrap()
            .group(group)
            .channel(NioSocketChannel.class)
            .option(ChannelOption.TCP_NODELAY, true)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    if (sslContext != null) {
                        ch.pipeline().addLast(sslContext.newHandler(ch.alloc(), host, port));
                    }
                    ch.pipeline().addLast(new HttpClientCodec());
                    ch.pipeline().addLast(new HttpObjectAggregator(65_536));
                    ch.pipeline().addLast(new ResponseHandler());
                }
            });
        Channel channel = bootstrap.connect(host, port).sync().channel();
        if (sslContext != null) {
            assertTlsNegotiated(channel);
        }
        return channel;
    }

    /**
     * Prove the TLS arm is actually TLS. MockServer detects TLS per connection, so a driver that failed
     * to install the handler would still get 200s over plaintext and report a full ladder of numbers for
     * the wrong protocol — the arm would measure h1 twice and nobody would see it. Waiting for the
     * handshake and rejecting the null cipher is the difference between the two.
     */
    private static void assertTlsNegotiated(Channel channel) throws InterruptedException {
        io.netty.handler.ssl.SslHandler handler = channel.pipeline().get(io.netty.handler.ssl.SslHandler.class);
        if (handler == null) {
            throw new IllegalStateException("HARNESS VALIDATION FAILED: TLS mode has no SslHandler in the pipeline");
        }
        handler.handshakeFuture().sync();
        String cipher = handler.engine().getSession().getCipherSuite();
        if (cipher == null || cipher.contains("NULL")) {
            throw new IllegalStateException("HARNESS VALIDATION FAILED: TLS mode negotiated " + cipher
                + " — the connection is not encrypted, so this arm is measuring plaintext");
        }
    }

    /** Send one keep-alive GET on an established channel; completes with the response status. */
    private static CompletableFuture<Integer> requestOnce(Channel channel, String host, int port) {
        ResponseHandler handler = channel.pipeline().get(ResponseHandler.class);
        CompletableFuture<Integer> pending = handler.expectOne();
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.GET, PATH, Unpooled.EMPTY_BUFFER);
        request.headers().set(HttpHeaderNames.HOST, host + ":" + port);
        request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        request.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
        channel.writeAndFlush(request).addListener(future -> {
            if (!future.isSuccess()) {
                pending.completeExceptionally(future.cause());
            }
        });
        return pending;
    }

    /** Completes the outstanding request future with each response status; one request in flight per channel. */
    private static final class ResponseHandler extends ChannelInboundHandlerAdapter {
        private volatile CompletableFuture<Integer> pending;

        CompletableFuture<Integer> expectOne() {
            CompletableFuture<Integer> future = new CompletableFuture<>();
            pending = future;
            return future;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            try {
                CompletableFuture<Integer> future = pending;
                if (msg instanceof FullHttpResponse && future != null) {
                    future.complete(((FullHttpResponse) msg).status().code());
                }
            } finally {
                ReferenceCountUtil.release(msg);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            CompletableFuture<Integer> future = pending;
            if (future != null) {
                future.completeExceptionally(cause);
            }
            ctx.close();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            CompletableFuture<Integer> future = pending;
            if (future != null && !future.isDone()) {
                future.completeExceptionally(new IllegalStateException("connection closed with a request in flight"));
            }
        }
    }

    /**
     * Link {@link Wall} and {@link Wall.Kind} while descriptors are still available.
     * <p>
     * Loading a class needs a descriptor to read the jar, so a JVM that exhausts them mid-loop cannot
     * then load the class it would use to report that: at {@code ulimit -n 512} this died with
     * {@code NoClassDefFoundError: ...$Wall} instead of naming the wall — the instrument failing in
     * precisely the condition it exists to diagnose. Called from {@code main} rather than from the
     * calibrate path alone, because the measuring path reaches {@code classify()} on the same failure.
     */
    private static void preloadFailureReportingClasses() {
        if (new Wall(0, Wall.Kind.OTHER, "preload").held != 0) {
            throw new IllegalStateException("unreachable — exists so the preload cannot be optimised away");
        }
    }

    /**
     * How many connections this driver may hold: {@code CEILING_CLIENT_CEILING} if set (take it from
     * what {@code CEILING_MODE=calibrate} measured on this box, not from a model of how the kernel
     * allocates ports), else the assumed ephemeral range — and in both cases reduced by
     * {@link #EPHEMERAL_SAFETY_FACTOR}, because a ladder must fit the previous rung's {@code TIME_WAIT}
     * sockets as well as its own. It takes no port count: whether destinations get separate source-port
     * spaces is a kernel property, and on macOS it measured as one global range.
     */
    private static int ephemeralHeadroom() {
        String measured = System.getenv("CEILING_CLIENT_CEILING");
        return (int) ((measured == null || measured.isEmpty()
            ? DEFAULT_EPHEMERAL_RANGE
            : Integer.parseInt(measured)) * EPHEMERAL_SAFETY_FACTOR);
    }

    /**
     * Measure what this driver can actually hold, instead of modelling it. Opens plain TCP connections
     * until the kernel refuses, first against one destination port and then spread across all of them.
     * If the two numbers match, the kernel draws source ports from one range regardless of destination,
     * and giving the server more ports buys nothing — the assumption this mode exists to test.
     */
    private static void calibrate(String host, int[] ports) throws Exception {
        long maxFd = maxFileDescriptors();
        boolean macOs = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
        System.out.printf("calibrate: this JVM's file-descriptor limit is %d%s%n", maxFd,
            maxFd > 10_240 ? ""
                : macOs
                    ? " — the JDK sets RLIMIT_NOFILE to min(hard, OPEN_MAX=10240) on macOS whatever the"
                      + " shell's ulimit says. Raise `ulimit -n` AND re-run both this driver and the"
                      + " server with -XX:-MaxFDLimit, or the ceiling you measure is the JDK's."
                    : " — low for a connection ladder. On Linux the JDK raises the soft limit to the HARD"
                      + " limit, so raise the HARD limit (systemd LimitNOFILE, docker --ulimit nofile)."
                      + " Do NOT pass -XX:-MaxFDLimit here: on Linux it disables that raise.");

        Wall one = holdUntilRefused(host, new int[]{ports[0]});
        System.out.printf("calibrate: 1 destination port  -> %d connections, refused by %s%n", one.held, one.kind);
        if (ports.length > 1) {
            // TIME_WAIT from the first arm would steal from the second, so wait it out rather than
            // reporting a second number that is really the first arm's residue.
            long drainMs = Long.parseLong(env("CEILING_TIMEWAIT_DRAIN_MS", "45000"));
            System.out.printf("calibrate: draining TIME_WAIT for %d ms before the second arm%n", drainMs);
            Thread.sleep(drainMs);
            Wall many = holdUntilRefused(host, ports);
            System.out.printf("calibrate: %d destination ports -> %d connections, refused by %s%n",
                ports.length, many.held, many.kind);
            // Only a PORT wall says anything about how source ports are allocated. Two arms that both
            // stopped at the same file-descriptor limit are equal for a reason that has nothing to do
            // with destinations, and reading a port conclusion out of that ratio is exactly the mistake
            // this mode exists to prevent.
            if (one.kind != Wall.Kind.PORTS || many.kind != Wall.Kind.PORTS) {
                System.out.println("calibrate: INCONCLUSIVE about source-port allocation — at least one arm"
                    + " stopped at a file-descriptor limit, not a port limit. Raise the fd limit (see the"
                    + " per-platform note above) and re-run before drawing any conclusion about destinations.");
            } else {
                double ratio = (double) many.held / one.held;
                System.out.printf("calibrate: ratio %.2f — %s%n", ratio,
                    ratio > 1.5
                        ? "source ports are per-destination, so more server ports raise the ceiling"
                        : "one global source-port range; more server ports do NOT raise the ceiling");
            }
        }
        System.out.printf("calibrate: pass CEILING_CLIENT_CEILING=%d to a measuring run — which will use"
            + " %d of it (%d%%), leaving the rest for the previous rung's TIME_WAIT sockets%n",
            one.held, (int) (one.held * EPHEMERAL_SAFETY_FACTOR), (int) (EPHEMERAL_SAFETY_FACTOR * 100));
    }

    /** Where a connect loop stopped, and what stopped it — the two are different findings. */
    private static final class Wall {
        enum Kind { FILE_DESCRIPTORS, PORTS, OTHER }

        final int held;
        final Kind kind;
        final String detail;

        Wall(int held, Kind kind, String detail) {
            this.held = held;
            this.kind = kind;
            this.detail = detail;
        }

        @Override
        public String toString() {
            return kind + " (" + detail + ")";
        }
    }

    /** Open connections round-robin across {@code ports} until one is refused; returns where and why it stopped. */
    private static Wall holdUntilRefused(String host, int[] ports) throws Exception {
        NioEventLoopGroup group = new NioEventLoopGroup();
        List<Channel> held = new ArrayList<>();
        try {
            for (int i = 0; ; i++) {
                try {
                    held.add(connect(group, null, host, ports[i % ports.length]));
                } catch (Exception e) {
                    return new Wall(held.size(), classify(e), describe(e));
                }
            }
        } finally {
            for (Channel channel : held) {
                channel.close();
            }
            group.shutdownGracefully(0, 2, TimeUnit.SECONDS).sync();
        }
    }

    /**
     * Which resource ran out. "Can't assign requested address" is the kernel refusing a source port;
     * a failure to create the channel at all is the process out of file descriptors. They look alike in
     * a stack trace and mean opposite things about what to fix.
     */
    private static Wall.Kind classify(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            String message = t.getMessage() == null ? "" : t.getMessage();
            if (t instanceof java.net.BindException || message.contains("Can't assign requested address")) {
                return Wall.Kind.PORTS;
            }
            // Only the message is evidence. `ChannelException` wraps many unrelated failures, so
            // matching on the type would report "out of file descriptors" for a refused connection and
            // send the reader to the wrong fix. Verified against a real exhaustion: the chain is
            // ChannelException -> InvocationTargetException -> ChannelException -> SocketException,
            // and the last carries "Too many open files", so walking causes finds it without the type.
            if (message.contains("Too many open files")) {
                return Wall.Kind.FILE_DESCRIPTORS;
            }
        }
        return Wall.Kind.OTHER;
    }

    private static String describe(Throwable error) {
        return error.getClass().getSimpleName() + (error.getMessage() == null ? "" : ": " + error.getMessage());
    }

    private static long maxFileDescriptors() {
        java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        return os instanceof com.sun.management.UnixOperatingSystemMXBean
            ? ((com.sun.management.UnixOperatingSystemMXBean) os).getMaxFileDescriptorCount()
            : -1;
    }

    private static double percentileMicros(long[] sortedNanos, double percentile) {
        int index = (int) Math.min(sortedNanos.length - 1L, Math.round(percentile * (sortedNanos.length - 1)));
        return sortedNanos[index] / 1_000.0;
    }

    private static void report(List<Rung> results, String mode, int ports, int probeRequests) {
        Rung firstBaseline = results.get(0);
        Rung lastBaseline = null;
        for (Rung rung : results) {
            if (rung.connections == 0 && rung.exhaustedBy == null) {
                lastBaseline = rung;
            }
        }
        double baselineDriftPct = lastBaseline == null ? 0.0
            : 100.0 * (lastBaseline.p50Micros - firstBaseline.p50Micros) / firstBaseline.p50Micros;

        System.out.println();
        System.out.printf("item 21 connection-scaling ceiling — mode=%s ports=%d probe_requests=%d%n",
            mode, ports, probeRequests);
        System.out.println("connections  established  server_confirmed  p50_us   p99_us   vs_paired_baseline  client_cpu_headroom  rig_valid");
        for (Rung rung : results) {
            if (rung.exhaustedBy != null) {
                System.out.printf("%11d  %11d  %16s  %7s  %7s  %18s  %19s  %s%n",
                    rung.connections, rung.established, "-", "-", "-", "-", "-",
                    "false (rig exhausted: " + rung.exhaustedBy + ")");
                continue;
            }
            System.out.printf("%11d  %11d  %16d  %7.1f  %7.1f  %18s  %19.3f  %s%n",
                rung.connections, rung.established, rung.serverConfirmed, rung.p50Micros, rung.p99Micros,
                Double.isNaN(rung.pairedBaselineP50Micros) ? "(baseline)"
                    : String.format(Locale.ROOT, "%.2fx", rung.p50Micros / rung.pairedBaselineP50Micros),
                rung.clientCpuHeadroom, rung.rigValid());
        }
        System.out.printf("%nbaseline drift first-to-last: %.1f%% — reported only as a measure of how much the box"
            + " moved; the ratios above are each against their OWN immediately-preceding baseline, so this"
            + " drift does not enter them.%n", baselineDriftPct);

        StringBuilder json = new StringBuilder("{\"mode\":\"").append(mode).append("\",\"ports\":").append(ports)
            .append(",\"probe_requests\":").append(probeRequests)
            .append(",\"baseline_drift_pct\":").append(String.format(Locale.ROOT, "%.2f", baselineDriftPct))
            .append(",\"rungs\":[");
        for (int i = 0; i < results.size(); i++) {
            json.append(i == 0 ? "" : ",").append(results.get(i).toJson());
        }
        System.out.println(json.append("]}"));
    }

    private static final class Rung {
        final int connections;
        final int established;
        final long serverConfirmed;
        final int probeErrors;
        final double p50Micros;
        final double p99Micros;
        final double clientCpuHeadroom;
        final Wall.Kind exhaustedBy;
        /** p50 of the zero-connection sample taken immediately before this rung; NaN for a baseline itself. */
        double pairedBaselineP50Micros = Double.NaN;

        Rung(int connections, int established, long serverConfirmed, int probeErrors,
             double p50Micros, double p99Micros, double clientCpuHeadroom) {
            this(connections, established, serverConfirmed, probeErrors, p50Micros, p99Micros,
                clientCpuHeadroom, null);
        }

        Rung(int connections, int established, long serverConfirmed, int probeErrors,
             double p50Micros, double p99Micros, double clientCpuHeadroom, Wall.Kind exhaustedBy) {
            this.connections = connections;
            this.established = established;
            this.serverConfirmed = serverConfirmed;
            this.probeErrors = probeErrors;
            this.p50Micros = p50Micros;
            this.p99Micros = p99Micros;
            this.clientCpuHeadroom = clientCpuHeadroom;
            this.exhaustedBy = exhaustedBy;
        }

        /** A rung the driver could not build. It carries no latency, and must never be read as one. */
        static Rung rigExhausted(int requested, int reached, Wall.Kind kind) {
            return new Rung(requested, reached, -1, 0, Double.NaN, Double.NaN, Double.NaN, kind);
        }

        /** Every rung carries its own validity: a latency measured on a saturated or lossy driver is not a server figure. */
        /**
         * Establishment is not re-checked here: a rung with fewer connections than requested never
         * reaches this object, because {@link #measureRung} throws on the distinct-local-port and
         * server-confirmed counts. Repeating it would read as a guard while being unreachable.
         */
        boolean rigValid() {
            return exhaustedBy == null && probeErrors == 0 && clientCpuHeadroom >= MIN_CLIENT_CPU_HEADROOM;
        }

        String toJson() {
            if (exhaustedBy != null) {
                return String.format(Locale.ROOT,
                    "{\"connections\":%d,\"reached\":%d,\"rig_valid\":false,\"exhausted_by\":\"%s\"}",
                    connections, established, exhaustedBy);
            }
            String ratio = Double.isNaN(pairedBaselineP50Micros) ? "null"
                : String.format(Locale.ROOT, "%.3f", p50Micros / pairedBaselineP50Micros);
            return String.format(Locale.ROOT,
                "{\"connections\":%d,\"established\":%d,\"server_confirmed\":%d,\"probe_errors\":%d,"
                    + "\"p50_us\":%.1f,\"p99_us\":%.1f,\"vs_paired_baseline\":%s,"
                    + "\"client_cpu_headroom\":%.3f,\"rig_valid\":%s}",
                connections, established, serverConfirmed, probeErrors, p50Micros, p99Micros,
                ratio, clientCpuHeadroom, rigValid());
        }
    }

    private static void controlPlanePut(HttpClient http, String host, int port, String path, String body) {
        try {
            HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create("http://" + host + ":" + port + path))
                    .PUT(HttpRequest.BodyPublishers.ofString(body))
                    .header("content-type", "application/json")
                    .build(),
                HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("control plane " + path + " returned " + response.statusCode()
                    + ": " + response.body());
            }
        } catch (Exception e) {
            throw new IllegalStateException("control plane " + path + " failed", e);
        }
    }

    /**
     * Read and then clear the server's recorded requests, returning how many it held. Failing loudly when
     * the count is not the expected batch size keeps a short read from being averaged away into a total
     * that still looks plausible.
     */
    private static long drainRecordedRequests(HttpClient http, String host, int port, int expected) {
        long recorded = retrieveRequestCount(http, host, port);
        if (recorded != expected) {
            throw new IllegalStateException("HARNESS VALIDATION FAILED: server recorded " + recorded
                + " requests in this batch, expected " + expected
                + " — the event log evicted entries, or a parked connection was not served");
        }
        controlPlanePut(http, host, port, "/mockserver/clear?type=LOG", "");
        return recorded;
    }

    /** Number of requests the server has recorded — the server-side half of the establishment proof. */
    private static long retrieveRequestCount(HttpClient http, String host, int port) {
        try {
            HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create("http://" + host + ":" + port + "/mockserver/retrieve?type=REQUESTS&format=JSON"))
                    .PUT(HttpRequest.BodyPublishers.ofString(""))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
            // Parse the array rather than counting pretty-printed "path" lines: the retrieve endpoint
            // happens to pretty-print today, and a compact response would silently count zero — which
            // this harness would then report as the server having accepted none of the connections.
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body()).size();
        } catch (Exception e) {
            throw new IllegalStateException("could not read the server's recorded request count", e);
        }
    }

    private static int[] parsePorts(String csv) {
        String[] parts = csv.split(",");
        int[] ports = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            ports[i] = Integer.parseInt(parts[i].trim());
        }
        return ports;
    }

    private static int[] parseLadder(String csv) {
        String[] parts = csv.split(",");
        int[] ladder = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            ladder[i] = Integer.parseInt(parts[i].trim());
        }
        return ladder;
    }

    private static String env(String key, String defaultValue) {
        String value = System.getenv(key);
        return value == null || value.isEmpty() ? defaultValue : value;
    }
}
