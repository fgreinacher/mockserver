package org.mockserver.blob.gcs;

import com.google.cloud.NoCredentials;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.mockserver.state.BlobStore;
import org.mockserver.state.contract.BlobStoreContract;
import org.mockserver.test.DockerAvailability;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;

import java.time.Duration;
import java.util.UUID;

/**
 * Runs the shared {@link BlobStoreContract} against a real
 * fake-gcs-server instance via Testcontainers. Docker-gated:
 * skips if Docker is not available.
 */
public class GcsBlobStoreContractTest extends BlobStoreContract {

    private static final String FAKE_GCS_IMAGE = "fsouza/fake-gcs-server:1.49.3";
    private static final String TEST_BUCKET = "mockserver-test";

    @SuppressWarnings("resource")
    private static GenericContainer<?> gcsContainer;
    private static Storage storage;

    @BeforeClass
    public static void startFakeGcs() {
        // Wrapped: DockerClientFactory.isDockerAvailable() THROWS rather than returning
        // false for post-connection failures (e.g. Ryuk rejected by a user-namespace
        // remapped daemon), which would turn this skip into a hard ERROR.
        Assume.assumeTrue(
            "Docker is not available -- skipping GCS integration test",
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

        String endpoint = "http://" + gcsContainer.getHost() + ":" + gcsContainer.getMappedPort(4443);

        storage = StorageOptions.newBuilder()
            .setHost(endpoint)
            .setCredentials(NoCredentials.getInstance())
            .setProjectId("test-project")
            .build()
            .getService();

        // Create the test bucket
        storage.create(BucketInfo.of(TEST_BUCKET));
    }

    @AfterClass
    public static void stopFakeGcs() {
        if (storage != null) {
            try {
                storage.close();
            } catch (Exception e) {
                // best-effort cleanup
            }
        }
        if (gcsContainer != null) {
            gcsContainer.stop();
        }
    }

    @Override
    protected BlobStore createStore() {
        // Use a unique key prefix per test to isolate test data
        String prefix = "test-" + UUID.randomUUID() + "/";
        return new GcsBlobStore(storage, TEST_BUCKET, prefix);
    }
}
