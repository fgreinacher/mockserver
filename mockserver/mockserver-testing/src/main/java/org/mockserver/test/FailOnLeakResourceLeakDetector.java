package org.mockserver.test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.util.ResourceLeakDetector;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A Netty {@link ResourceLeakDetector} that turns a detected reference-counted resource leak
 * (a {@code ByteBuf} that was allocated and never released) into something the BUILD can fail
 * on, instead of an ERROR line buried in the log that a green run happily ignores.
 * <p>
 * Install it in the test JVM with:
 * <pre>
 *   -Dio.netty.leakDetection.level=paranoid
 *   -Dio.netty.customResourceLeakDetector=org.mockserver.test.FailOnLeakResourceLeakDetector
 *   -Dmockserver.leakReportDir=&lt;a directory under target/&gt;
 * </pre>
 * Netty's {@code ResourceLeakDetectorFactory} instantiates one instance per tracked resource
 * type, preferring the {@code (Class,int,long)} constructor and falling back to
 * {@code (Class,int)} — both are provided below.
 * <p>
 * <b>How it lets the build fail.</b> Every leak Netty reports to this detector is (a) counted,
 * (b) still logged by Netty at ERROR via {@code super}, and (c) <em>written to a file</em>
 * {@code <leakReportDir>/leak-<pid>.txt}. A Maven {@code antrun} step (see mockserver-netty's
 * pom) then fails the build if that directory contains any non-empty file. A file survives the
 * fork exiting, so this does not depend on JUnit/surefire notification semantics (throwing from
 * a RunListener does NOT fail surefire — it is caught and reported as a listener warning).
 * <p>
 * <b>Flushing.</b> Netty only reports a leak once the leaked object has been GC-collected AND a
 * subsequent {@code track()} polls the reference queue. During a busy suite that happens
 * naturally as later allocations poll the queue. To also catch buffers leaked late in a fork,
 * this class registers (once) a JVM shutdown hook that forces a GC and allocates/releases a few
 * buffers so the queue is polled and any straggler is written before the fork exits.
 * <p>
 * <b>What it cannot catch.</b> A buffer that is never GC-collected before the JVM exits, or one
 * whose leak is reported on a background thread after the shutdown hook has run, will not reach
 * the file. Netty still logs it at ERROR with a {@code LEAK:} prefix; that log is the only
 * remaining signal for such a tail case.
 */
public class FailOnLeakResourceLeakDetector<T> extends ResourceLeakDetector<T> {

    /**
     * The fully-qualified name of this class, as it must appear in
     * {@code -Dio.netty.customResourceLeakDetector}.
     */
    public static final String CLASS_NAME = "org.mockserver.test.FailOnLeakResourceLeakDetector";

    /** System property naming the directory that per-fork leak report files are written to. */
    public static final String LEAK_REPORT_DIR_PROPERTY = "mockserver.leakReportDir";

    private static final AtomicInteger LEAK_COUNT = new AtomicInteger();
    private static final Queue<String> LEAK_RECORDS = new ConcurrentLinkedQueue<>();
    // Cap retained in-memory records so a runaway leak cannot exhaust the heap; the count is exact.
    private static final int MAX_RECORDS = 200;
    private static final Object FILE_LOCK = new Object();

    private static final File REPORT_FILE = resolveReportFile();
    private static final boolean SHUTDOWN_FLUSH_REGISTERED = registerShutdownFlush();

    public FailOnLeakResourceLeakDetector(Class<?> resourceType, int samplingInterval, long maxActive) {
        super(resourceType, samplingInterval, maxActive);
    }

    public FailOnLeakResourceLeakDetector(Class<?> resourceType, int samplingInterval) {
        super(resourceType, samplingInterval);
    }

    @Override
    protected void reportTracedLeak(String resourceType, String records) {
        record(resourceType, records);
        super.reportTracedLeak(resourceType, records);
    }

    @Override
    protected void reportUntracedLeak(String resourceType) {
        record(resourceType, " (no access records captured - raise io.netty.leakDetection.level to advanced/paranoid for the allocation site)");
        super.reportUntracedLeak(resourceType);
    }

    private static void record(String resourceType, String records) {
        LEAK_COUNT.incrementAndGet();
        String entry = "LEAK: " + resourceType + (records == null ? "" : records);
        if (LEAK_RECORDS.size() < MAX_RECORDS) {
            LEAK_RECORDS.add(entry);
        }
        writeToFile(entry);
    }

    private static void writeToFile(String entry) {
        if (REPORT_FILE == null) {
            return;
        }
        synchronized (FILE_LOCK) {
            try (Writer writer = new FileWriter(REPORT_FILE, true)) {
                writer.write("========================================" + System.lineSeparator());
                writer.write(entry + System.lineSeparator());
            } catch (IOException e) {
                // Best-effort: the in-memory count and Netty's own ERROR log remain as signals.
                System.err.println("WARNING: could not write Netty leak report to " + REPORT_FILE + ": " + e);
            }
        }
    }

    private static File resolveReportFile() {
        String dir = System.getProperty(LEAK_REPORT_DIR_PROPERTY);
        if (dir == null || dir.isEmpty()) {
            return null;
        }
        File reportDir = new File(dir);
        //noinspection ResultOfMethodCallIgnored
        reportDir.mkdirs();
        return new File(reportDir, "leak-" + ProcessHandle.current().pid() + ".txt");
    }

    /**
     * Registers a single JVM shutdown hook that forces the outstanding leaks to surface (GC +
     * allocate/release so the detector polls its reference queue) before the fork exits.
     */
    private static boolean registerShutdownFlush() {
        Runtime.getRuntime().addShutdownHook(new Thread(FailOnLeakResourceLeakDetector::flush, "netty-leak-flush"));
        return true;
    }

    private static void flush() {
        for (int round = 0; round < 5; round++) {
            System.gc();
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            for (int poll = 0; poll < 10; poll++) {
                ByteBuf direct = PooledByteBufAllocator.DEFAULT.directBuffer(16);
                ByteBuf heap = PooledByteBufAllocator.DEFAULT.heapBuffer(16);
                direct.release();
                heap.release();
            }
        }
    }

    /**
     * @return the number of leaks reported to this detector since the JVM started.
     */
    public static int leakCount() {
        return LEAK_COUNT.get();
    }

    /**
     * @return a snapshot of the recorded leak reports (capped at {@value #MAX_RECORDS}).
     */
    public static List<String> recordedLeaks() {
        return new ArrayList<>(LEAK_RECORDS);
    }

    /**
     * Clears the recorded leaks. Intended for tests that assert on this detector itself.
     */
    public static void reset() {
        LEAK_COUNT.set(0);
        LEAK_RECORDS.clear();
    }
}
