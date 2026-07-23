package org.mockserver.blob.s3;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.state.StateBackend;
import org.mockserver.state.StateBackendFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Proves the <em>reflective auto-discovery</em> path of
 * {@link StateBackendFactory#createBlobStore(Configuration)} —
 * {@code discoverBlobStoreBackend("s3")} → {@code Class.forName} of the
 * {@link S3BlobStoreRegistrar} listed in the factory's {@code BLOB_STORE_REGISTRARS}
 * map → reflective {@code register()} → factory {@code create()}.
 * <p>
 * Unlike {@link S3BlobStoreRegistrarTest} (which calls {@link S3BlobStoreRegistrar#register()}
 * by hand), this test performs <strong>no manual registration</strong>: it configures
 * {@code blobStoreType=s3} and calls {@link StateBackendFactory#create(Configuration)}
 * directly, so the only way the resulting backend can hold an {@link S3BlobStore} is if
 * the factory discovered and loaded this module reflectively from the classpath.
 * <p>
 * No network, no Docker: {@code createS3BlobStore} builds a lazy {@link software.amazon.awssdk.services.s3.S3Client}
 * that performs no I/O at build time.
 */
public class S3BlobStoreDiscoveryTest {

    @Before
    public void setUp() {
        // ensure a pristine registry so we exercise discovery, not a leftover registration
        StateBackendFactory.resetToDefault();
    }

    @After
    public void tearDown() {
        StateBackendFactory.resetToDefault();
    }

    @Test
    public void shouldDiscoverS3BlobStoreReflectivelyWithoutManualRegistration() {
        assertFalse("precondition: s3 must NOT be registered before create()",
            StateBackendFactory.isBlobStoreFactoryRegistered("s3"));

        Configuration config = Configuration.configuration()
            .maxExpectations(50)
            .blobStoreType("s3")
            .blobStoreBucket("some-bucket");

        StateBackend backend = StateBackendFactory.create(config);
        try {
            // the load-bearing assertion: reflective discovery loaded THIS module's
            // registrar from the classpath and wired an S3BlobStore into the backend
            assertThat(backend.blobs(), instanceOf(S3BlobStore.class));
            // discovery side-effect: the factory is now registered for subsequent calls
            assertTrue("s3 factory should be registered as a side-effect of discovery",
                StateBackendFactory.isBlobStoreFactoryRegistered("s3"));
        } finally {
            backend.close();
        }
    }
}
