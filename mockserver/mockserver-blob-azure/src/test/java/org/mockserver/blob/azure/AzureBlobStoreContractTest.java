package org.mockserver.blob.azure;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.mockserver.state.BlobStore;
import org.mockserver.state.contract.BlobStoreContract;
import org.mockserver.test.DockerAvailability;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.time.Duration;
import java.util.UUID;

/**
 * Runs the shared {@link BlobStoreContract} against a real Azurite
 * instance via Testcontainers. Docker-gated: skips if Docker is
 * not available.
 */
public class AzureBlobStoreContractTest extends BlobStoreContract {

    private static final String AZURITE_IMAGE = "mcr.microsoft.com/azure-storage/azurite:3.33.0";
    private static final String TEST_CONTAINER_NAME = "mockserver-test";

    // Azurite well-known development credentials
    private static final String ACCOUNT_NAME = "devstoreaccount1";
    private static final String ACCOUNT_KEY = "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";

    @SuppressWarnings("resource")
    private static GenericContainer<?> azuriteContainer;
    private static BlobServiceClient blobServiceClient;
    private static BlobContainerClient containerClient;

    @BeforeClass
    public static void startAzurite() {
        // Wrapped: DockerClientFactory.isDockerAvailable() THROWS rather than returning
        // false for post-connection failures (e.g. Ryuk rejected by a user-namespace
        // remapped daemon), which would turn this skip into a hard ERROR.
        Assume.assumeTrue(
            "Docker is not available -- skipping Azure integration test",
            DockerAvailability.isAvailable(() -> DockerClientFactory.instance().isDockerAvailable())
        );

        azuriteContainer = new GenericContainer<>(AZURITE_IMAGE)
            .withExposedPorts(10000)
            .withCommand("azurite-blob", "--blobHost", "0.0.0.0", "--blobPort", "10000")
            .waitingFor(Wait.forListeningPort()
                .withStartupTimeout(Duration.ofSeconds(30)));

        azuriteContainer.start();

        String endpoint = "http://" + azuriteContainer.getHost() + ":" + azuriteContainer.getMappedPort(10000);
        String connectionString = String.format(
            "DefaultEndpointsProtocol=http;AccountName=%s;AccountKey=%s;BlobEndpoint=%s/%s;",
            ACCOUNT_NAME, ACCOUNT_KEY, endpoint, ACCOUNT_NAME
        );

        blobServiceClient = new BlobServiceClientBuilder()
            .connectionString(connectionString)
            .buildClient();

        // Create the test container
        containerClient = blobServiceClient.getBlobContainerClient(TEST_CONTAINER_NAME);
        containerClient.create();
    }

    @AfterClass
    public static void stopAzurite() {
        if (azuriteContainer != null) {
            azuriteContainer.stop();
        }
    }

    @Override
    protected BlobStore createStore() {
        // Use a unique key prefix per test to isolate test data
        String prefix = "test-" + UUID.randomUUID() + "/";
        return new AzureBlobStore(containerClient, prefix);
    }
}
