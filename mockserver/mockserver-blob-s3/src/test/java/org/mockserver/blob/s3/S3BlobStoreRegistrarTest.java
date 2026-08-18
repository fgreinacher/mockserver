package org.mockserver.blob.s3;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.state.StateBackendFactory;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link S3BlobStoreRegistrar} registration mechanics.
 */
public class S3BlobStoreRegistrarTest {

    // StateBackendFactory's registry is JVM-global and this module runs all its test
    // classes in ONE reused fork (forkCount=1/reuseForks=true) with surefire's default
    // filesystem runOrder, which is not stable across machines. Scrubbing on ENTRY as
    // well as exit is what makes the "not registered yet" precondition below true
    // regardless of what ran first — a sibling that registers "s3" and forgets to reset
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
    public void shouldRegisterS3BlobStoreFactory() {
        assertFalse("s3 should not be registered before register()",
            StateBackendFactory.isBlobStoreFactoryRegistered("s3"));

        S3BlobStoreRegistrar.register();

        assertTrue("s3 should be registered after register()",
            StateBackendFactory.isBlobStoreFactoryRegistered("s3"));
    }

    @Test
    public void shouldBeIdempotent() {
        S3BlobStoreRegistrar.register();
        S3BlobStoreRegistrar.register();

        assertTrue("s3 should still be registered after double register()",
            StateBackendFactory.isBlobStoreFactoryRegistered("s3"));
    }
}
