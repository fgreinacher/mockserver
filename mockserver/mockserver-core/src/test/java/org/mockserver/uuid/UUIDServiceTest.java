package org.mockserver.uuid;

import org.junit.Test;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Verifies the fast, non-cryptographic {@link UUIDService#getNonSecureUUID()} path added for the
 * request hot path: it must produce well-formed, UNIQUE version-4 UUIDs (no collisions even across
 * many threads), and it must honour the same test-only fixed-UUID pinning as {@link
 * UUIDService#getUUID()} so that integration tests which pin the id are unaffected.
 *
 * <p>This class never pins the UUID GLOBALLY (only per-thread, always paired with an unpin in the
 * same method) so it is safe to run in the Surefire parallel phase alongside other classes.</p>
 */
public class UUIDServiceTest {

    // 8-4-4-4-12 lower-case hex with the version nibble fixed to 4 and the variant nibble in [89ab].
    private static final java.util.regex.Pattern VERSION_4_UUID =
        java.util.regex.Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

    @Test
    public void nonSecureUUIDIsAWellFormedVersion4UUID() {
        for (int i = 0; i < 10_000; i++) {
            String id = UUIDService.getNonSecureUUID();
            assertTrue("not a valid version-4 UUID: " + id, VERSION_4_UUID.matcher(id).matches());
            // round-trips through java.util.UUID with version 4 and IETF variant 2
            UUID parsed = UUID.fromString(id);
            assertEquals("version should be 4 for id " + id, 4, parsed.version());
            assertEquals("variant should be 2 (IETF) for id " + id, 2, parsed.variant());
        }
    }

    @Test
    public void nonSecureUUIDsAreUniqueSingleThreaded() {
        // 100k draws from a 122-bit space gives a birthday-paradox collision probability of ~5e-10 -
        // conclusive for uniqueness while keeping heap/runtime light; the multi-threaded test below
        // draws 3.2M and carries the stronger concurrency coverage.
        int count = 100_000;
        Set<String> seen = new java.util.HashSet<>(count * 2);
        for (int i = 0; i < count; i++) {
            assertTrue("collision after " + i + " draws", seen.add(UUIDService.getNonSecureUUID()));
        }
        assertEquals(count, seen.size());
    }

    @Test
    public void nonSecureUUIDsAreUniqueAcrossManyThreads() throws Exception {
        final int threads = 16;
        final int perThread = 200_000;
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        final Set<String> ids = ConcurrentHashMap.newKeySet(threads * perThread * 2);
        final AtomicInteger collisions = new AtomicInteger();
        final CountDownLatch ready = new CountDownLatch(threads);
        final CountDownLatch go = new CountDownLatch(1);
        try {
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int i = 0; i < perThread; i++) {
                        if (!ids.add(UUIDService.getNonSecureUUID())) {
                            collisions.incrementAndGet();
                        }
                    }
                });
            }
            ready.await();
            go.countDown(); // release all threads at once to maximise concurrency
            pool.shutdown();
            assertTrue("timed out generating ids", pool.awaitTermination(60, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals("expected no collisions across threads", 0, collisions.get());
        assertEquals(threads * perThread, ids.size());
    }

    @Test
    public void nonSecureUUIDHonoursPerThreadPinning() {
        assertThat("precondition: not pinned", UUIDService.fixedUUID(), is(false));
        UUIDService.fixedUUID(true);
        try {
            assertEquals(UUIDService.FIXED_UUID_FOR_TESTS, UUIDService.getNonSecureUUID());
            assertEquals(UUIDService.FIXED_UUID_FOR_TESTS, UUIDService.getNonSecureUUID());
            // the secure path must observe the same pin
            assertEquals(UUIDService.FIXED_UUID_FOR_TESTS, UUIDService.getUUID());
        } finally {
            UUIDService.fixedUUID(false);
        }
        // once unpinned, generation is random again
        assertNotEquals(UUIDService.FIXED_UUID_FOR_TESTS, UUIDService.getNonSecureUUID());
    }

    @Test
    public void nonSecureAndSecurePathsProduceTheSameFormat() {
        assertThat(VERSION_4_UUID.matcher(UUIDService.getUUID()).matches(), is(true));
        assertThat(VERSION_4_UUID.matcher(UUIDService.getNonSecureUUID()).matches(), is(true));
    }
}
