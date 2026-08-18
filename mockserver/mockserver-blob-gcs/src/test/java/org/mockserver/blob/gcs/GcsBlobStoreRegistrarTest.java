package org.mockserver.blob.gcs;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.state.StateBackendFactory;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link GcsBlobStoreRegistrar} registration mechanics.
 */
public class GcsBlobStoreRegistrarTest {

    // StateBackendFactory's registry is JVM-global and this module runs all its test
    // classes in ONE reused fork (forkCount=1/reuseForks=true) with surefire's default
    // filesystem runOrder, which is not stable across machines. Scrubbing on ENTRY as
    // well as exit is what makes the "not registered yet" precondition below true
    // regardless of what ran first — a sibling that registers "gcs" and forgets to reset
    // (as S3ExpectationPersistenceReloadTest did) must not be able to fail this test.
    @Before
    public void setUp() {
        StateBackendFactory.resetToDefault();
    }

    @After
    public void tearDown() {
        StateBackendFactory.resetToDefault();
    }

    @Test
    public void shouldRegisterGcsBlobStoreFactory() {
        assertFalse("gcs should not be registered before register()",
            StateBackendFactory.isBlobStoreFactoryRegistered("gcs"));

        GcsBlobStoreRegistrar.register();

        assertTrue("gcs should be registered after register()",
            StateBackendFactory.isBlobStoreFactoryRegistered("gcs"));
    }

    @Test
    public void shouldBeIdempotent() {
        GcsBlobStoreRegistrar.register();
        GcsBlobStoreRegistrar.register();

        assertTrue("gcs should still be registered after double register()",
            StateBackendFactory.isBlobStoreFactoryRegistered("gcs"));
    }
}
