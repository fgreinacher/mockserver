package org.mockserver.blob.azure;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
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
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Docker-gated wiring test for {@link AzureBlobStoreRegistrar#createAzureBlobStore(Configuration)} —
 * the config-property to Azure-client/store wiring the {@code AzureBlobStoreContractTest}
 * deliberately bypasses by hand-building {@code new AzureBlobStore(containerClient, ...)}.
 * <p>
 * Drives the registrar from <em>configuration only</em> (exactly as production does) — a
 * {@code blobStoreConnectionString} whose {@code BlobEndpoint} points at a real Azurite
 * emulator plus a {@code blobStoreContainer} name — then proves both:
 * <ul>
 *   <li>the built {@link BlobContainerClient} targets the configured container and the endpoint
 *       carried by the connection string, and</li>
 *   <li>the resulting store genuinely round-trips against Azurite, with the blob landing in the
 *       <em>configured</em> container under the <em>configured</em> keyPrefix — verified through
 *       an independent admin client, so a mis-wired container or prefix cannot pass.</li>
 * </ul>
 */
public class AzureBlobStoreRegistrarConfigWiringTest {

    private static final String AZURITE_IMAGE = "mcr.microsoft.com/azure-storage/azurite:3.36.0";

    // Azurite well-known development credentials
    private static final String ACCOUNT_NAME = "devstoreaccount1";
    private static final String ACCOUNT_KEY = "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";

    @SuppressWarnings("resource")
    private static GenericContainer<?> azuriteContainer;
    private static String blobEndpointHostPort;
    private static String connectionString;
    /** Independent client used only to create containers and verify what actually landed. */
    private static BlobServiceClient adminServiceClient;

    @BeforeClass
    public static void startAzurite() {
        // Wrapped: DockerClientFactory.isDockerAvailable() THROWS rather than returning
        // false for post-connection failures (e.g. Ryuk rejected by a user-namespace
        // remapped daemon), which would turn this skip into a hard ERROR.
        Assume.assumeTrue(
            "Docker is not available -- skipping Azure config-wiring test",
            DockerAvailability.isAvailable(() -> DockerClientFactory.instance().isDockerAvailable())
        );

        azuriteContainer = new GenericContainer<>(AZURITE_IMAGE)
            .withExposedPorts(10000)
            // --skipApiVersionCheck: the azure-storage-blob SDK negotiates a Storage REST
            // API version that runs ahead of every released Azurite build, so the emulator
            // otherwise rejects the client with HTTP 400 InvalidHeaderValue.
            .withCommand("azurite-blob", "--blobHost", "0.0.0.0", "--blobPort", "10000", "--skipApiVersionCheck")
            .waitingFor(Wait.forListeningPort()
                .withStartupTimeout(Duration.ofSeconds(30)));

        azuriteContainer.start();

        blobEndpointHostPort = azuriteContainer.getHost() + ":" + azuriteContainer.getMappedPort(10000);
        String endpoint = "http://" + blobEndpointHostPort;
        connectionString = String.format(
            "DefaultEndpointsProtocol=http;AccountName=%s;AccountKey=%s;BlobEndpoint=%s/%s;",
            ACCOUNT_NAME, ACCOUNT_KEY, endpoint, ACCOUNT_NAME
        );

        adminServiceClient = new BlobServiceClientBuilder()
            .connectionString(connectionString)
            .buildClient();
    }

    @AfterClass
    public static void stopAzurite() {
        if (azuriteContainer != null) {
            azuriteContainer.stop();
        }
    }

    private static String freshContainer() {
        String container = "wiring-" + UUID.randomUUID();
        adminServiceClient.getBlobContainerClient(container).create();
        return container;
    }

    // ----- required-property guard branches (Docker-gated by @BeforeClass) -----

    @Test
    public void shouldThrowWhenContainerMissing() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> AzureBlobStoreRegistrar.createAzureBlobStore(
                new Configuration().blobStoreConnectionString(connectionString)));
        assertThat(ex.getMessage(), is("blobStoreType=azure requires blobStoreContainer to be configured"));
    }

    @Test
    public void shouldThrowWhenConnectionStringMissing() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> AzureBlobStoreRegistrar.createAzureBlobStore(
                new Configuration().blobStoreContainer("some-container")));
        assertThat(ex.getMessage(), is("blobStoreType=azure requires blobStoreConnectionString to be configured"));
    }

    // ----- container-name + endpoint wiring, asserted on the built client -----

    @Test
    public void shouldTargetConfiguredContainerName() {
        String container = freshContainer();
        AzureBlobStore store = AzureBlobStoreRegistrar.createAzureBlobStore(
            new Configuration()
                .blobStoreContainer(container)
                .blobStoreConnectionString(connectionString));
        try {
            BlobContainerClient client = (BlobContainerClient) readField(store, "containerClient");
            assertEquals("container client must target the configured container",
                container, client.getBlobContainerName());
        } finally {
            store.close();
        }
    }

    @Test
    public void shouldUseEndpointFromConnectionString() {
        // The endpoint lives inside the connection string; a registrar that ignored it (or
        // hard-coded a different BlobEndpoint) would produce a client URL for another host.
        String container = freshContainer();
        AzureBlobStore store = AzureBlobStoreRegistrar.createAzureBlobStore(
            new Configuration()
                .blobStoreContainer(container)
                .blobStoreConnectionString(connectionString));
        try {
            BlobContainerClient client = (BlobContainerClient) readField(store, "containerClient");
            assertThat("container URL must carry the emulator endpoint from the connection string",
                client.getBlobContainerUrl(), containsString(blobEndpointHostPort));
        } finally {
            store.close();
        }
    }

    // ----- container + keyPrefix wiring, proven against Azurite via an independent client -----

    @Test
    public void shouldRoundTripAgainstEmulatorIntoConfiguredContainerAndPrefix() {
        String container = freshContainer();
        String keyPrefix = "mockserver/prefix/";
        String key = "persistedExpectations-" + UUID.randomUUID() + ".json";
        byte[] payload = "azure-wiring-payload".getBytes(StandardCharsets.UTF_8);

        AzureBlobStore store = AzureBlobStoreRegistrar.createAzureBlobStore(
            new Configuration()
                .blobStoreContainer(container)
                .blobStoreConnectionString(connectionString)
                .blobStoreKeyPrefix(keyPrefix));
        try {
            store.put(key, payload, Collections.emptyMap());

            // 1) the store itself reads it back
            Optional<Blob> read = store.get(key);
            assertTrue("blob must be readable back through the configured store", read.isPresent());
            assertEquals("azure-wiring-payload", new String(read.get().getData(), StandardCharsets.UTF_8));

            // 2) an INDEPENDENT client proves the blob landed in the CONFIGURED container under the
            //    CONFIGURED prefix -- a registrar that ignored either would fail this even though
            //    its own get() might still have round-tripped.
            String expectedBlobName = BlobKeys.join(keyPrefix, key);
            ByteArrayOutputStream landed = new ByteArrayOutputStream();
            adminServiceClient.getBlobContainerClient(container)
                .getBlobClient(expectedBlobName)
                .downloadStream(landed);
            assertEquals("blob must exist in container '" + container + "' at '" + expectedBlobName + "'",
                "azure-wiring-payload", landed.toString(StandardCharsets.UTF_8));

            assertEquals(keyPrefix, readField(store, "keyPrefix"));
        } finally {
            store.close();
        }
    }

    // ----- reflection helper (mirrors S3BlobStoreRegistrarConfigWiringTest) -----

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
