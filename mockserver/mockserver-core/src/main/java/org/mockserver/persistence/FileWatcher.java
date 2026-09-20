package org.mockserver.persistence;

import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.scheduler.Scheduler;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.slf4j.event.Level.INFO;

public class FileWatcher {

    private static ScheduledExecutorService scheduler;

    public synchronized static ScheduledExecutorService getScheduler() {
        if (scheduler == null) {
            scheduler = new ScheduledThreadPoolExecutor(
                2,
                new Scheduler.SchedulerThreadFactory("FileWatcher"),
                new ThreadPoolExecutor.CallerRunsPolicy()
            );
            Runtime.getRuntime().addShutdownHook(new Thread(() -> scheduler.shutdown()));
        }
        return scheduler;
    }

    /**
     * Read size for the fingerprint. Any value comfortably below G1's humongous threshold (half a
     * region, so 512 KB on a small heap) keeps the per-poll allocation in the young generation.
     */
    private static final int HASH_BUFFER_BYTES = 64 * 1024;

    private volatile boolean running = true;
    private final ScheduledFuture<?> scheduledFuture;

    /**
     * @param pollPeriodMillis the interval, in milliseconds, between successive polls of the watched
     *                         file. Captured here when {@code scheduleAtFixedRate} is set up, so the
     *                         first poll lands one full period after construction. The poll period is
     *                         supplied per-watcher (via the {@code watchInitializationJsonPollPeriodMillis}
     *                         configuration property, see {@link org.mockserver.configuration.Configuration})
     *                         rather than held in shared mutable static state, so concurrent watchers /
     *                         tests can use different periods without racing each other. A non-positive
     *                         value is clamped up to {@code 1ms} to satisfy {@code scheduleAtFixedRate}.
     */
    public FileWatcher(Path filePath, Runnable updatedHandler, Consumer<Throwable> errorHandler, MockServerLogger mockServerLogger, long pollPeriodMillis) {
        final long pollPeriod = Math.max(1L, pollPeriodMillis);
        final Path path = filePath.getParent() != null ? filePath : Paths.get(new File(".").getAbsolutePath(), filePath.toString());
        final AtomicReference<Integer> fileHash = new AtomicReference<>(getFileHash(path));
        mockServerLogger.logEvent(
            new LogEntry()
                .setLogLevel(INFO)
                .setMessageFormat("watching file:{}with file fingerprint:{}")
                .setArguments(path, fileHash)
        );
        scheduledFuture = getScheduler().scheduleAtFixedRate(() -> {
            // Skip the iteration entirely if this watcher has been stopped but
            // the already-scheduled task has not yet been cancelled.
            if (!running) {
                return;
            }
            try {
                Integer currentHash = getFileHash(path);
                // A null hash means the file could not be read (missing, being
                // rewritten, or otherwise unreadable). Do NOT treat that as a
                // content change: skip this poll iteration and keep the previous
                // hash so the next successful read decides whether to reload.
                // Conflating "unreadable" with "changed" (the old return-0
                // behaviour) caused spurious reloads of partial/empty content
                // mid-rewrite and could mask a real subsequent change.
                if (currentHash == null) {
                    return;
                }
                if (!currentHash.equals(fileHash.get())) {
                    updatedHandler.run();
                    // Re-read after the handler ran; only update the stored hash
                    // when the re-read succeeds, otherwise keep the value we just
                    // acted on so a transient read failure does not lose state.
                    Integer afterHash = getFileHash(path);
                    fileHash.set(afterHash != null ? afterHash : currentHash);
                }
            } catch (Throwable throwable) {
                errorHandler.accept(throwable);
            }
        }, pollPeriod, pollPeriod, TimeUnit.MILLISECONDS);
    }

    /**
     * Computes a content fingerprint of the watched file, or returns
     * {@code null} when the file cannot be read (missing, mid-rewrite, or
     * otherwise unreadable). Callers must treat {@code null} as "no reliable
     * reading this iteration" rather than as a content change.
     * <p>
     * Streamed through a small fixed buffer rather than {@code Files.readAllBytes}, because this runs
     * on every poll whether or not the file changed. Materialising the whole file allocated a byte[]
     * the size of the file each time: for a 10 MB initialization file at the default 5 s period that
     * is ~364 MB of garbage every three minutes, and in G1 each of those arrays is a <em>humongous</em>
     * allocation. Measured on an idle server with no traffic at all, watching turned that from
     * <b>zero</b> collections into a heap repeatedly filling to ~230 MB of a 256 MB heap and being
     * collected. Buffered, the same poll allocates 64 KB.
     * <p>
     * The arithmetic is deliberately {@link Arrays#hashCode(byte[])}'s, computed incrementally, so the
     * fingerprint is unchanged from the previous implementation — though nothing depends on that, since
     * the value is only ever compared against another reading of the same file. What the contract
     * actually requires is that it is stable for unchanged content and differs for changed content,
     * including a change in the final byte of a multi-chunk file and a change confined to a byte above
     * 0x7F, both of which a chunked loop can get wrong and are pinned by tests.
     */
    private Integer getFileHash(Path path) {
        byte[] buffer = new byte[HASH_BUFFER_BYTES];
        try (InputStream inputStream = Files.newInputStream(path)) {
            int hash = 1;
            int read;
            while ((read = inputStream.read(buffer)) != -1) {
                for (int i = 0; i < read; i++) {
                    hash = 31 * hash + buffer[i];
                }
            }
            return hash;
        } catch (IOException ioe) {
            return null;
        }
    }

    public boolean isRunning() {
        return running;
    }

    public FileWatcher setRunning(boolean running) {
        this.running = running;
        if (!running && this.scheduledFuture != null) {
            this.scheduledFuture.cancel(true);
        }
        return this;
    }
}
