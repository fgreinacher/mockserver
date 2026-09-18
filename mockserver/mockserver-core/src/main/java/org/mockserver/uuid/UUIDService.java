package org.mockserver.uuid;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.RandomBasedGenerator;

import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class UUIDService {

    private static final RandomBasedGenerator RANDOM_BASED_GENERATOR = Generators.randomBasedGenerator(new SecureRandom());
    public static final String FIXED_UUID_FOR_TESTS = RANDOM_BASED_GENERATOR.generate().toString();

    // Single gate covering BOTH ways a test can pin the UUID: per-thread (fixedUUID) and across all
    // threads (fixedUUIDGlobally). Production never pins the UUID, so this stays 0 and getUUID() pays
    // only a single volatile read before generating a random UUID.
    private static volatile int fixedActiveCount = 0;
    // Per-thread pin: lets one parallel test class fix the UUID without other parallel classes seeing it.
    private static final ThreadLocal<Boolean> FIXED_ON_THREAD = ThreadLocal.withInitial(() -> Boolean.FALSE);
    // Global pin: needed by integration tests whose ids are generated on the in-process server's own
    // threads (not the test thread), which a per-thread pin cannot reach. JVM-global, so such tests
    // must NOT run under parallel=classes.
    private static volatile boolean fixedGlobally = false;

    /**
     * Cryptographically-secure, unguessable UUID. Use this for any value whose unguessability is a
     * SECURITY property - session ids, client-registration ids, callback correlation ids, keystore
     * file names, cluster node ids, or anything a client can observe and an attacker must not be able
     * to predict. This is the default; when in doubt use this one.
     *
     * <p>Each call draws from a process-wide {@link SecureRandom}, whose underlying native PRNG is
     * {@code synchronized}. Under sustained multi-threaded load that monitor becomes a contention
     * point, so callers on the request hot path that need only UNIQUENESS (not unguessability) should
     * use {@link #getNonSecureUUID()} instead.</p>
     */
    public static String getUUID() {
        if (fixedActiveCount == 0) {
            return RANDOM_BASED_GENERATOR.generate().toString();
        }
        return (fixedGlobally || FIXED_ON_THREAD.get()) ? FIXED_UUID_FOR_TESTS : RANDOM_BASED_GENERATOR.generate().toString();
    }

    /**
     * Fast, contention-free, UNIQUE-but-GUESSABLE UUID for internal identifiers on the hot path -
     * log-entry ids, per-request log-correlation ids, internal stream ids. It is a standard random
     * (version 4) UUID with 122 bits of randomness drawn from {@link ThreadLocalRandom}, so collision
     * is astronomically unlikely, but it is NOT cryptographically secure.
     *
     * <p><strong>NEVER use this for a value whose unguessability is a security property</strong>
     * (session ids, client ids, callback/breakpoint correlation ids exposed to clients, keystore
     * names). Use {@link #getUUID()} for those. The point of splitting the two is that the SECURE
     * generator stays the default and this opt-in fast path is only taken where uniqueness alone is
     * required.</p>
     *
     * <p>Because {@link ThreadLocalRandom} keeps a per-thread generator, concurrent callers never
     * share a lock - which is exactly why this removes the {@code SecureRandom} monitor contention
     * that serialised the worker event loops under load. It honours the test-only fixed-UUID pinning
     * ({@link #fixedUUID(boolean)} / {@link #fixedUUIDGlobally(boolean)}) identically to
     * {@link #getUUID()}, so tests that pin the id see the same {@link #FIXED_UUID_FOR_TESTS}.</p>
     */
    public static String getNonSecureUUID() {
        if (fixedActiveCount != 0 && (fixedGlobally || FIXED_ON_THREAD.get())) {
            return FIXED_UUID_FOR_TESTS;
        }
        return randomVersion4UUID();
    }

    private static String randomVersion4UUID() {
        final ThreadLocalRandom random = ThreadLocalRandom.current();
        long mostSigBits = random.nextLong();
        long leastSigBits = random.nextLong();
        // set the version (4) and IETF variant bits, matching java.util.UUID.randomUUID()'s layout
        mostSigBits &= 0xffffffffffff0fffL;
        mostSigBits |= 0x0000000000004000L;
        leastSigBits &= 0x3fffffffffffffffL;
        leastSigBits |= 0x8000000000000000L;
        return new UUID(mostSigBits, leastSigBits).toString();
    }

    /**
     * Test-only: pin (or unpin) {@link #getUUID()} to {@link #FIXED_UUID_FOR_TESTS} for the CURRENT
     * thread only, so parallel test classes do not generate colliding ids. Every {@code fixedUUID(true)}
     * MUST be paired with a {@code fixedUUID(false)} on the same thread - prefer the
     * {@code org.mockserver.uuid.FixedUUID} JUnit rule, which guarantees the reset.
     */
    public static synchronized void fixedUUID(boolean fixed) {
        boolean current = FIXED_ON_THREAD.get();
        if (fixed && !current) {
            FIXED_ON_THREAD.set(Boolean.TRUE);
            fixedActiveCount++;
        } else if (!fixed && current) {
            FIXED_ON_THREAD.set(Boolean.FALSE);
            fixedActiveCount--;
        }
    }

    /**
     * Test-only: pin (or unpin) {@link #getUUID()} for ALL threads. Required by integration tests that
     * assert on ids generated on the in-process server's threads rather than the test thread. Because
     * it is JVM-global it is NOT parallel-safe - use only from tests that run sequentially.
     */
    public static synchronized void fixedUUIDGlobally(boolean fixed) {
        if (fixed && !fixedGlobally) {
            fixedGlobally = true;
            fixedActiveCount++;
        } else if (!fixed && fixedGlobally) {
            fixedGlobally = false;
            fixedActiveCount--;
        }
    }

    public static boolean fixedUUID() {
        return fixedGlobally || FIXED_ON_THREAD.get();
    }

}
