package org.mockserver.state;

import org.junit.After;
import org.junit.Test;
import org.mockserver.configuration.Configuration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

public class StateBackendFactoryTest {

    @After
    public void tearDown() {
        StateBackendFactory.resetToDefault();
    }

    @Test
    public void shouldCreateDefaultInMemoryBackend() {
        Configuration config = Configuration.configuration().maxExpectations(50);
        StateBackend backend = StateBackendFactory.create(config);

        assertNotNull(backend);
        assertThat(backend, instanceOf(InMemoryStateBackend.class));
        backend.close();
    }

    @Test
    public void shouldReportNoCustomFactoryByDefault() {
        assertFalse(StateBackendFactory.isCustomFactoryRegistered());
    }

    @Test
    public void shouldRegisterCustomFactory() {
        StateBackendFactory.register(config -> new InMemoryStateBackend(10));
        assertTrue(StateBackendFactory.isCustomFactoryRegistered());
    }

    @Test
    public void shouldResetToDefault() {
        StateBackendFactory.register(config -> new InMemoryStateBackend(10));
        StateBackendFactory.resetToDefault();
        assertFalse(StateBackendFactory.isCustomFactoryRegistered());
    }

    @Test
    public void shouldResetToDefaultWhenRegisteringNull() {
        StateBackendFactory.register(config -> new InMemoryStateBackend(10));
        StateBackendFactory.register(null);
        assertFalse(StateBackendFactory.isCustomFactoryRegistered());
    }

    @Test
    public void shouldThrowWhenInfinispanConfiguredButModuleAbsent() {
        // In mockserver-core's test classpath, the Infinispan registrar class
        // is NOT present, so this exercises the fail-hard path.
        Configuration config = Configuration.configuration()
            .maxExpectations(50)
            .stateBackend("infinispan");

        try {
            StateBackendFactory.create(config);
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertThat(e.getMessage(), containsString("stateBackend=infinispan configured but"));
            assertThat(e.getMessage(), containsString("is not on the classpath"));
            assertThat(e.getMessage(), containsString("mockserver-state-infinispan"));
            assertThat(e.getCause(), instanceOf(ClassNotFoundException.class));
        }
    }

    @Test
    public void shouldCreateInMemoryBackendWhenStateBackendIsMemory() {
        Configuration config = Configuration.configuration()
            .maxExpectations(50)
            .stateBackend("memory");
        StateBackend backend = StateBackendFactory.create(config);

        assertNotNull(backend);
        assertThat(backend, instanceOf(InMemoryStateBackend.class));
        backend.close();
    }

    @Test
    public void shouldCreateInMemoryBackendWhenStateBackendIsDefault() {
        // stateBackend not explicitly set => defaults to "memory"
        Configuration config = Configuration.configuration().maxExpectations(50);
        StateBackend backend = StateBackendFactory.create(config);

        assertNotNull(backend);
        assertThat(backend, instanceOf(InMemoryStateBackend.class));
        backend.close();
    }

    @Test
    public void shouldCreateFilesystemBlobStoreWhenBlobStoreTypeIsFilesystem() {
        Configuration config = Configuration.configuration()
            .maxExpectations(50)
            .blobStoreType("filesystem");
        StateBackend backend = StateBackendFactory.create(config);

        assertNotNull(backend);
        assertThat(backend.blobs(), instanceOf(FilesystemBlobStore.class));
        backend.close();
    }

    @Test
    public void shouldCreateInMemoryBlobStoreWhenBlobStoreTypeIsMemory() {
        Configuration config = Configuration.configuration()
            .maxExpectations(50)
            .blobStoreType("memory");
        StateBackend backend = StateBackendFactory.create(config);

        assertNotNull(backend);
        assertThat(backend.blobs(), instanceOf(InMemoryBlobStore.class));
        backend.close();
    }

    @Test
    public void shouldDefaultToFilesystemBlobStore() {
        // blobStoreType not explicitly set => defaults to "filesystem"
        Configuration config = Configuration.configuration().maxExpectations(50);
        StateBackend backend = StateBackendFactory.create(config);

        assertNotNull(backend);
        assertThat(backend.blobs(), instanceOf(FilesystemBlobStore.class));
        backend.close();
    }

    @Test
    public void shouldThrowWhenCloudBlobStoreConfiguredButModuleAbsent() {
        // In mockserver-core's test classpath the S3 registrar class
        // (org.mockserver.blob.s3.S3BlobStoreRegistrar) is NOT present, so
        // reflective discovery hits ClassNotFoundException and must fail hard
        // with guidance to add the module dependency -- rather than silently
        // falling back to the filesystem blob store.
        Configuration config = Configuration.configuration()
            .maxExpectations(50)
            .blobStoreType("s3");

        try {
            StateBackendFactory.create(config);
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertThat(e.getMessage(), containsString("blobStoreType=s3 configured but"));
            assertThat(e.getMessage(), containsString("is not on the classpath"));
            assertThat(e.getMessage(), containsString("mockserver-blob-s3"));
            assertThat(e.getCause(), instanceOf(ClassNotFoundException.class));
        }
    }

    @Test
    public void shouldWarnWhenClusteredAndDynamicCertificateAuthorityEnabled() {
        // The broken combination: a clustered (multi-node) deployment where every
        // node dynamically mints its OWN Certificate Authority. Clients that trust
        // one node's CA fail TLS validation against the others.
        Configuration config = Configuration.configuration()
            .clusterEnabled(true)
            .dynamicallyCreateCertificateAuthorityCertificate(true);

        assertTrue(StateBackendFactory.isClusteredWithDynamicCertificateAuthority(config));
    }

    @Test
    public void shouldNotWarnWhenClusteredWithSharedCertificateAuthority() {
        // Clustered but NOT dynamically generating the CA (the supported fix:
        // one shared CA supplied to every node) -> no warning.
        Configuration config = Configuration.configuration()
            .clusterEnabled(true)
            .dynamicallyCreateCertificateAuthorityCertificate(false);

        assertFalse(StateBackendFactory.isClusteredWithDynamicCertificateAuthority(config));
    }

    @Test
    public void shouldNotWarnWhenSingleNodeWithDynamicCertificateAuthority() {
        // Single-node dynamic CA is fine (one node, one CA) -> no warning.
        Configuration config = Configuration.configuration()
            .clusterEnabled(false)
            .dynamicallyCreateCertificateAuthorityCertificate(true);

        assertFalse(StateBackendFactory.isClusteredWithDynamicCertificateAuthority(config));
    }

    @Test
    public void shouldThrowWhenBlobStoreTypeIsUnrecognised() {
        // A blobStoreType that is neither a built-in (memory/filesystem) nor a
        // known cloud registrar key must fail with the "not a recognised" guidance,
        // exercising the BLOB_STORE_REGISTRARS lookup-miss branch.
        Configuration config = Configuration.configuration()
            .maxExpectations(50)
            .blobStoreType("not-a-real-backend");

        try {
            StateBackendFactory.create(config);
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertThat(e.getMessage(), containsString("is not a recognised blob store type"));
            assertThat(e.getMessage(), containsString("supported types: memory, filesystem, s3, gcs, azure"));
        }
    }
}
