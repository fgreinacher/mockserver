package org.mockserver.templates.engine.javascript;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Standalone measurement harness (NOT a JUnit test — no {@code @Test}, so the normal suite never runs it)
 * comparing the JavaScript-template engine strategy BEFORE and AFTER the shared-Engine + cached-Source fix.
 *
 * <ul>
 *   <li><b>OLD</b> — a brand-new {@link Context} per render with NO shared {@link Engine} and a freshly
 *       created {@link Source} every call. This is exactly what {@code PolyglotRunner} did before the fix.</li>
 *   <li><b>NEW</b> — one process-wide {@link Engine}, a cached {@link Source} per script, and a fresh
 *       {@link Context} per render built on the shared engine. This mirrors the fixed {@code PolyglotRunner}
 *       (fresh Context per request preserves per-request isolation; the engine+Source cache remove the cost).</li>
 * </ul>
 *
 * Both strategies run the SAME script, the SAME number of renders, on the SAME executor sizing, so the
 * before/after numbers are comparable. It reports (1) single-thread render latency and (2) a contagion
 * scenario: a small shared pool (sized like MockServer's action scheduler, {@code max(5, cores)}) runs a
 * burst of JS renders interleaved with trivial "unrelated" tasks (standing in for match/forward/etc.), and
 * we measure how long those unrelated tasks wait — the tail that collapsed in the live incident.
 *
 * Run with the test classpath, e.g.:
 * <pre>
 *   mvn -o -pl mockserver-core dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt -DincludeScope=test
 *   java -cp "mockserver-core/target/test-classes:mockserver-core/target/classes:$(cat /tmp/cp.txt)" \
 *        org.mockserver.templates.engine.javascript.PolyglotRunnerConcurrencyBenchmark
 * </pre>
 */
public final class PolyglotRunnerConcurrencyBenchmark {

    // A representative response template in the same wrapped shape PolyglotRunner builds: a handle()
    // function plus a serialise() wrapper that JSON.parses the request and JSON.stringifies the result.
    private static final String SCRIPT =
        "function handle(request) {" +
        "  return { statusCode: 200, headers: { 'Content-Type': ['application/json'] }," +
        "           body: JSON.stringify({ path: request.path, method: request.method," +
        "                                  n: (request.path || '').length * 3 }) };" +
        "}" +
        " function serialise(request) { return JSON.stringify(handle(JSON.parse(request)), null, 2); }";

    private static final String REQUEST_JSON = "{\"path\":\"/some/path\",\"method\":\"GET\"}";

    private static final HostAccess HOST_ACCESS = HostAccess
        .newBuilder(HostAccess.ALL)
        .denyAccess(Class.class)
        .denyAccess(ClassLoader.class)
        .build();

    private static final Engine SHARED_ENGINE = Engine.newBuilder("js").build();
    private static final ConcurrentHashMap<String, Source> SOURCE_CACHE = new ConcurrentHashMap<>();

    private PolyglotRunnerConcurrencyBenchmark() {
    }

    // ---- the two strategies -------------------------------------------------------------------------

    private static String renderOld() {
        try (Context context = Context.newBuilder("js")
            .allowHostAccess(HOST_ACCESS)
            .allowHostClassLookup(className -> false)
            .build()) {
            context.eval(Source.create("js", SCRIPT));
            Value serialise = context.getBindings("js").getMember("serialise");
            return serialise.execute(REQUEST_JSON).asString();
        }
    }

    private static String renderNew() {
        try (Context context = Context.newBuilder("js")
            .engine(SHARED_ENGINE)
            .allowHostAccess(HOST_ACCESS)
            .allowHostClassLookup(className -> false)
            .build()) {
            Source source = SOURCE_CACHE.computeIfAbsent(SCRIPT, s -> Source.create("js", s));
            context.eval(source);
            Value serialise = context.getBindings("js").getMember("serialise");
            return serialise.execute(REQUEST_JSON).asString();
        }
    }

    // ---- measurement --------------------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        int cores = Runtime.getRuntime().availableProcessors();
        // Model the incident box: a 6-core server → actionHandlerThreadCount = max(5, cores) = 6. Override
        // with arg[0]. Fixing the pool size (rather than using this dev box's core count) is what makes the
        // starvation reproducible off the constrained server it was first seen on.
        int poolSize = args.length > 0 ? Integer.parseInt(args[0]) : 6;
        int renderLoad = args.length > 1 ? Integer.parseInt(args[1]) : 2000;
        System.out.println("devCores=" + cores + " modelledSchedulerPoolSize=" + poolSize + " renderLoad=" + renderLoad);

        // sanity: both strategies produce identical output
        String oldOut = renderOld();
        String newOut = renderNew();
        if (!oldOut.equals(newOut)) {
            throw new IllegalStateException("strategies disagree!\nOLD=" + oldOut + "\nNEW=" + newOut);
        }
        System.out.println("output parity OK, sample:\n" + newOut);

        // warm up both paths (interpreter-only, but let class-loading / first-parse settle)
        for (int i = 0; i < 50; i++) {
            renderOld();
            renderNew();
        }

        System.out.println("\n=== single-thread render latency (1000 renders) ===");
        latency("OLD", PolyglotRunnerConcurrencyBenchmark::renderOld, 1000);
        latency("NEW", PolyglotRunnerConcurrencyBenchmark::renderNew, 1000);

        System.out.println("\n=== contagion: " + renderLoad + " JS renders saturate a " + poolSize
            + "-thread pool; 200 unrelated probe tasks are submitted into the same pool and we measure how long they wait ===");
        contagion("OLD", PolyglotRunnerConcurrencyBenchmark::renderOld, poolSize, renderLoad, 200);
        contagion("NEW", PolyglotRunnerConcurrencyBenchmark::renderNew, poolSize, renderLoad, 200);
    }

    private static void latency(String label, Runnable render, int iterations) {
        long[] nanos = new long[iterations];
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            long t0 = System.nanoTime();
            render.run();
            nanos[i] = System.nanoTime() - t0;
        }
        long wall = System.nanoTime() - start;
        Arrays.sort(nanos);
        double throughput = iterations / (wall / 1_000_000_000.0);
        System.out.printf("%-4s renders=%d  p50=%.2fms  p99=%.2fms  max=%.2fms  single-thread-throughput=%.0f/s%n",
            label, iterations, ms(nanos[(int) (iterations * 0.50)]), ms(nanos[(int) (iterations * 0.99)]),
            ms(nanos[iterations - 1]), throughput);
    }

    /**
     * Saturate a {@code poolSize}-thread pool with {@code renderLoad} JS renders, then submit {@code probes}
     * trivial unrelated tasks (standing in for a plain match/forward dispatch) into the SAME pool and report
     * how long each waits from submit to start. This is the metric that collapsed in the live incident: an
     * unrelated request that arrives while the small shared pool is monopolised by slow JS renders waits
     * behind them. Faster renders drain the queue sooner, so the unrelated tail shrinks.
     */
    private static void contagion(String label, Runnable render, int poolSize, int renderLoad, int probes) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(poolSize);
        try {
            CountDownLatch startGate = new CountDownLatch(1);
            List<Future<?>> renders = new ArrayList<>(renderLoad);
            List<Future<Long>> probeWaits = new ArrayList<>(probes);
            long[] probeSubmit = new long[probes];
            // Interleave the unrelated probes evenly THROUGH the render backlog (not all behind it), so a
            // probe stands in for an unrelated request arriving while JS renders are in flight on the shared
            // pool. Each probe records its own submit→start wait, giving a real latency distribution.
            int probeEvery = Math.max(1, renderLoad / probes);
            int probeIdx = 0;
            long wallStart = System.nanoTime();
            for (int i = 0; i < renderLoad; i++) {
                renders.add(pool.submit(() -> {
                    await(startGate);
                    render.run();
                }));
                if (i % probeEvery == 0 && probeIdx < probes) {
                    final int p = probeIdx++;
                    probeSubmit[p] = System.nanoTime();
                    probeWaits.add(pool.submit(() -> System.nanoTime() - probeSubmit[p]));
                }
            }
            startGate.countDown();
            for (Future<?> f : renders) {
                f.get();
            }
            long renderWall = System.nanoTime() - wallStart;

            int n = probeWaits.size();
            long[] wait = new long[n];
            for (int i = 0; i < n; i++) {
                wait[i] = probeWaits.get(i).get();
            }
            Arrays.sort(wait);
            System.out.printf("%-4s unrelated-probe wait: p50=%.2fms  p99=%.2fms  max=%.2fms   |  %d renders drained in %.0fms  render-throughput=%.0f/s%n",
                label, ms(wait[(int) (n * 0.50)]), ms(wait[(int) (n * 0.99)]), ms(wait[n - 1]),
                renderLoad, renderWall / 1_000_000.0, renderLoad / (renderWall / 1_000_000_000.0));
        } finally {
            pool.shutdownNow();
        }
    }

    private static void await(CountDownLatch gate) {
        try {
            gate.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static double ms(long nanos) {
        return nanos / 1_000_000.0;
    }
}
