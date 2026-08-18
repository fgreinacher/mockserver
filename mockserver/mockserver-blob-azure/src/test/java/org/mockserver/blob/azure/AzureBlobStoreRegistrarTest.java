package org.mockserver.blob.azure;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.state.StateBackendFactory;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link AzureBlobStoreRegistrar} registration mechanics.
 */
public class AzureBlobStoreRegistrarTest {

    // StateBackendFactory's registry is JVM-global and this module runs all its test
    // classes in ONE reused fork (forkCount=1/reuseForks=true) with surefire's default
    // filesystem runOrder, which is not stable across machines. Scrubbing on ENTRY as
    // well as exit is what makes the "not registered yet" precondition below true
    // regardless of what ran first — a sibling that registers "azure" and forgets to reset
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
    public void shouldRegisterAzureBlobStoreFactory() {
        assertFalse("azure should not be registered before register()",
            StateBackendFactory.isBlobStoreFactoryRegistered("azure"));

        AzureBlobStoreRegistrar.register();

        assertTrue("azure should be registered after register()",
            StateBackendFactory.isBlobStoreFactoryRegistered("azure"));
    }

    @Test
    public void shouldBeIdempotent() {
        AzureBlobStoreRegistrar.register();
        AzureBlobStoreRegistrar.register();

        assertTrue("azure should still be registered after double register()",
            StateBackendFactory.isBlobStoreFactoryRegistered("azure"));
    }
}
