package org.mockserver.blob.gcs;

import com.google.auth.Credentials;
import com.google.cloud.NoCredentials;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.state.Blob;
import org.mockserver.state.BlobKeys;
import org.mockserver.test.DockerAvailability;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Docker-gated wiring test for {@link GcsBlobStoreRegistrar#createGcsBlobStore(Configuration)} —
 * the config-property to GCS-client/store wiring the {@code GcsBlobStoreContractTest}
 * deliberately bypasses by hand-building {@code new GcsBlobStore(storage, ...)}.
 * <p>
 * Unlike the S3 SDK, the GCS {@link Storage} client's endpoint and credentials cannot be
 * asserted from a purely-in-memory builder alone in a way that also proves the client is
 * usable, so this test drives the registrar from <em>configuration only</em> (exactly as
 * production does) pointed at a real fake-gcs-server emulator, then proves both:
 * <ul>
 *   <li>the observable {@link StorageOptions} carry the configured endpoint, project and
 *       credentials (so a dropped {@code setHost}/{@code setProjectId} is caught even before
 *       any I/O), and</li>
 *   <li>the resulting store genuinely round-trips against the emulator, and the object lands
 *       in the <em>configured</em> bucket under the <em>configured</em> keyPrefix — verified
 *       through an independent admin client, so a mis-wired bucket or prefix cannot pass.</li>
 * </ul>
 */
public class GcsBlobStoreRegistrarConfigWiringTest {

    private static final String FAKE_GCS_IMAGE = "fsouza/fake-gcs-server:1.49.3";

    @SuppressWarnings("resource")
    private static GenericContainer<?> gcsContainer;
    private static String endpoint;
    /** Independent client used only to create buckets and verify what actually landed. */
    private static Storage adminStorage;

    @BeforeClass
    public static void startFakeGcs() {
        // Wrapped: DockerClientFactory.isDockerAvailable() THROWS rather than returning
        // false for post-connection failures (e.g. Ryuk rejected by a user-namespace
        // remapped daemon), which would turn this skip into a hard ERROR.
        Assume.assumeTrue(
            "Docker is not available -- skipping GCS config-wiring test",
            DockerAvailability.isAvailable(() -> DockerClientFactory.instance().isDockerAvailable())
        );

        gcsContainer = new GenericContainer<>(FAKE_GCS_IMAGE)
            .withExposedPorts(4443)
            .withCommand(
                "-scheme", "http",
                "-backend", "memory",
                "-public-host", "localhost"
            )
            .waitingFor(new HttpWaitStrategy()
                .forPath("/storage/v1/b")
                .forPort(4443)
                .forStatusCode(200)
                .withStartupTimeout(Duration.ofSeconds(30)));

        gcsContainer.start();

        endpoint = "http://" + gcsContainer.getHost() + ":" + gcsContainer.getMappedPort(4443);

        adminStorage = StorageOptions.newBuilder()
            .setHost(endpoint)
            .setCredentials(NoCredentials.getInstance())
            .setProjectId("admin-project")
            .build()
            .getService();
    }

    @AfterClass
    public static void stopFakeGcs() {
        if (adminStorage != null) {
            try {
                adminStorage.close();
            } catch (Exception e) {
                // best-effort cleanup
            }
        }
        if (gcsContainer != null) {
            gcsContainer.stop();
        }
    }

    private static String freshBucket() {
        String bucket = "wiring-" + UUID.randomUUID();
        adminStorage.create(BucketInfo.of(bucket));
        return bucket;
    }

    // ----- pure guard branches (no emulator dependency, but Docker-gated by @BeforeClass) -----

    @Test
    public void shouldThrowWhenBucketMissing() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> GcsBlobStoreRegistrar.createGcsBlobStore(new Configuration()));
        assertThat(ex.getMessage(), is("blobStoreType=gcs requires blobStoreBucket to be configured"));
    }

    @Test
    public void shouldThrowWhenBucketEmpty() {
        assertThrows(IllegalStateException.class,
            () -> GcsBlobStoreRegistrar.createGcsBlobStore(new Configuration().blobStoreBucket("")));
    }

    // ----- endpoint / credentials / project wiring, asserted on the built client -----

    @Test
    public void shouldApplyEndpointOverrideAsStorageHost() {
        // If the registrar dropped setHost(endpoint) the client would target real GCS.
        try (GcsBlobStore store = GcsBlobStoreRegistrar.createGcsBlobStore(
            new Configuration().blobStoreBucket(freshBucket()).blobStoreEndpoint(endpoint))) {
            assertEquals(endpoint, storageOptions(store).getHost());
        }
    }

    @Test
    public void shouldUseNoCredentialsWhenEndpointConfigured() {
        try (GcsBlobStore store = GcsBlobStoreRegistrar.createGcsBlobStore(
            new Configuration().blobStoreBucket(freshBucket()).blobStoreEndpoint(endpoint))) {
            Credentials credentials = storageOptions(store).getCredentials();
            assertThat(credentials, instanceOf(NoCredentials.class));
        }
    }

    @Test
    public void shouldApplyConfiguredProjectId() {
        // Distinct from the registrar's "test-project" fallback, so a dropped setProjectId shows.
        String projectId = "my-project-" + UUID.randomUUID();
        try (GcsBlobStore store = GcsBlobStoreRegistrar.createGcsBlobStore(
            new Configuration()
                .blobStoreBucket(freshBucket())
                .blobStoreEndpoint(endpoint)
                .blobStoreProjectId(projectId))) {
            assertEquals(projectId, storageOptions(store).getProjectId());
        }
    }

    @Test
    public void shouldFallBackToTestProjectWhenProjectIdUnset() {
        // DEFAULT-path assertion: with an endpoint but no project, the registrar substitutes
        // "test-project" so the emulator client is still constructible.
        try (GcsBlobStore store = GcsBlobStoreRegistrar.createGcsBlobStore(
            new Configuration().blobStoreBucket(freshBucket()).blobStoreEndpoint(endpoint))) {
            assertEquals("test-project", storageOptions(store).getProjectId());
        }
    }

    // ----- bucket + keyPrefix wiring, proven against the emulator via an independent client -----

    @Test
    public void shouldRoundTripAgainstEmulatorIntoConfiguredBucketAndPrefix() {
        String bucket = freshBucket();
        String keyPrefix = "mockserver/prefix/";
        String key = "persistedExpectations-" + UUID.randomUUID() + ".json";
        byte[] payload = "gcs-wiring-payload".getBytes(StandardCharsets.UTF_8);

        try (GcsBlobStore store = GcsBlobStoreRegistrar.createGcsBlobStore(
            new Configuration()
                .blobStoreBucket(bucket)
                .blobStoreEndpoint(endpoint)
                .blobStoreKeyPrefix(keyPrefix))) {

            store.put(key, payload, Collections.emptyMap());

            // 1) the store itself reads it back
            Optional<Blob> read = store.get(key);
            assertTrue("blob must be readable back through the configured store", read.isPresent());
            assertEquals("gcs-wiring-payload", new String(read.get().getData(), StandardCharsets.UTF_8));

            // 2) an INDEPENDENT client proves the object landed in the CONFIGURED bucket under the
            //    CONFIGURED prefix -- a registrar that ignored either would fail this even though
            //    its own get() might still have round-tripped.
            String expectedObjectName = BlobKeys.join(keyPrefix, key);
            com.google.cloud.storage.Blob landed = adminStorage.get(BlobId.of(bucket, expectedObjectName));
            assertNotNull("object must exist in bucket '" + bucket + "' at '" + expectedObjectName + "'", landed);
            assertEquals("gcs-wiring-payload", new String(landed.getContent(), StandardCharsets.UTF_8));

            // and the store honours the configured bucket field verbatim
            assertEquals(bucket, readField(store, "bucket"));
            assertEquals(keyPrefix, readField(store, "keyPrefix"));
        }
    }

    // ----- reflection helpers (mirrors S3BlobStoreRegistrarConfigWiringTest) -----

    private static StorageOptions storageOptions(GcsBlobStore store) {
        return ((Storage) readField(store, "storage")).getOptions();
    }

    private static Object readField(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not read field '" + name + "' from " + target.getClass(), e);
        }
    }
}
