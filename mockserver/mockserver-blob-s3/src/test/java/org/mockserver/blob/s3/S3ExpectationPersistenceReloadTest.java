package org.mockserver.blob.s3;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.socket.PortFactory;
import org.mockserver.test.DockerAvailability;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * End-to-end test that proves cloud (S3) expectation persistence is symmetric:
 * an expectation created over the wire on one MockServer instance is restored
 * and served by a fresh instance pointed at the same bucket after a restart.
 * <p>
 * Backed by a real MinIO instance via Testcontainers. Docker-gated: skips
 * when Docker is unavailable so the suite degrades gracefully on CI agents
 * without a Docker daemon.
 * <p>
 * This is the positive control for the blob-store reload path added to
 * {@code ExpectationFileSystemPersistence}: without that read path the second
 * server starts empty and returns 404, failing this test.
 */
public class S3ExpectationPersistenceReloadTest {

    private static final String MINIO_IMAGE = "minio/minio:RELEASE.2024-11-07T00-52-20Z";
    private static final String ACCESS_KEY = "minioadmin";
    private static final String SECRET_KEY = "minioadmin";
    private static final String TEST_BUCKET = "mockserver-reload-test";

    @SuppressWarnings("resource")
    private static GenericContainer<?> minioContainer;
    private static S3Client s3Client;
    private static String endpoint;

    private ClientAndServer server;

    @BeforeClass
    public static void startMinIO() {
        // Wrapped probe (lambda, not method reference): DockerClientFactory.isDockerAvailable()
        // THROWS rather than returning false for post-connection failures, which would turn
        // this skip into a hard ERROR and defeat the assume guard.
        Assume.assumeTrue(
            "Docker is not available -- skipping S3 persistence reload test",
            DockerAvailability.isAvailable(() -> DockerClientFactory.instance().isDockerAvailable())
        );

        minioContainer = new GenericContainer<>(MINIO_IMAGE)
            .withExposedPorts(9000)
            .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
            .withCommand("server", "/data")
            .waitingFor(new HttpWaitStrategy()
                .forPath("/minio/health/live")
                .forPort(9000)
                .withStartupTimeout(Duration.ofSeconds(30)));

        minioContainer.start();

        endpoint = "http://" + minioContainer.getHost() + ":" + minioContainer.getMappedPort(9000);

        s3Client = S3Client.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.US_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
            .forcePathStyle(true)
            .build();

        s3Client.createBucket(CreateBucketRequest.builder()
            .bucket(TEST_BUCKET)
            .build());
    }

    @AfterClass
    public static void stopMinIO() {
        if (s3Client != null) {
            s3Client.close();
        }
        if (minioContainer != null) {
            minioContainer.stop();
        }
    }

    @After
    public void stopServer() {
        stopQuietly(server);
    }

    private Configuration s3PersistenceConfiguration(String persistedExpectationsPath, String keyPrefix) {
        return configuration()
            .persistExpectations(true)
            .persistedExpectationsPath(persistedExpectationsPath)
            .blobStoreType("s3")
            .blobStoreBucket(TEST_BUCKET)
            .blobStoreRegion("us-east-1")
            .blobStoreEndpoint(endpoint)
            .blobStoreKeyPrefix(keyPrefix)
            .blobStoreAccessKeyId(ACCESS_KEY)
            .blobStoreSecretAccessKey(SECRET_KEY)
            // Generous on purpose: the production DEFAULT is 10s, but on a contended CI agent
            // running Docker-in-Docker the cold SDK bootstrap plus the first GetObject can exceed
            // it, and a blown deadline surfaces here as a silent "restored nothing" 404 mismatch
            // rather than as a timeout. The deadline must not be the thing under test.
            .blobStoreRestoreTimeoutSeconds(60);
    }

    @Test
    public void shouldRestoreCloudPersistedExpectationsAfterRestart() throws Exception {
        // A per-run key prefix (deliberately WITHOUT a trailing slash) plus a
        // shared persisted path both servers agree on. The persisted-path is an
        // absolute filesystem path (that is how ExpectationFileSystemPersistence
        // derives the blob key), which already begins with '/', so a prefix
        // without a trailing slash yields a single-slash object key that MinIO
        // accepts. A trailing-slash prefix would produce a '//' key that MinIO
        // rejects with "Object name contains unsupported characters".
        String keyPrefix = "reload-" + UUID.randomUUID();
        File persistedExpectations = File.createTempFile("persistedExpectations", ".json");
        persistedExpectations.deleteOnExit();
        String persistedExpectationsPath = persistedExpectations.getAbsolutePath();

        // GIVEN a MockServer with S3 persistence, an expectation created over the wire
        server = ClientAndServer.startClientAndServer(
            s3PersistenceConfiguration(persistedExpectationsPath, keyPrefix), PortFactory.findFreePort());
        server
            .when(request().withPath("/persisted"))
            .respond(response().withBody("restored-from-s3"));

        // the expectation serves on the first instance
        assertThat(get(server.getLocalPort(), "/persisted"), is("restored-from-s3"));

        // AND persistence to S3 has completed (the listener write-back is async)
        awaitS3BlobContains(keyPrefix, "/persisted");

        // WHEN the first instance is stopped
        stopQuietly(server);
        server = null;

        // AND a fresh instance is started against the SAME bucket + persisted path
        server = ClientAndServer.startClientAndServer(
            s3PersistenceConfiguration(persistedExpectationsPath, keyPrefix), PortFactory.findFreePort());

        // THEN the expectation is restored and served by the fresh instance
        assertThat(get(server.getLocalPort(), "/persisted"), is("restored-from-s3"));
    }

    @Test
    public void shouldRestoreCloudPersistedExpectationsWhenInitializationJsonPathMatchesPersistedExpectationsPath() throws Exception {
        // The migration case: a user moving from filesystem persistence to blobStoreType=s3
        // keeps initializationJsonPath pointing at persistedExpectationsPath, which is exactly
        // what the long-standing filesystem guidance tells them to do. The local file stays
        // empty (S3 holds the state), and ExpectationInitializerLoader calls
        // update(EMPTY, new Cause(initializationJsonPath, FILE_INITIALISER)) unconditionally.
        // Cause has value equality, and RequestMatchers.update removes every matcher whose
        // source equals the cause, so a colliding cause source silently deletes everything the
        // restore just loaded.
        String keyPrefix = "reload-init-" + UUID.randomUUID();
        File persistedExpectations = File.createTempFile("persistedExpectationsWithInitializer", ".json");
        persistedExpectations.deleteOnExit();
        String persistedExpectationsPath = persistedExpectations.getAbsolutePath();

        server = ClientAndServer.startClientAndServer(
            s3PersistenceConfiguration(persistedExpectationsPath, keyPrefix), PortFactory.findFreePort());
        server
            .when(request().withPath("/persisted-with-initializer"))
            .respond(response().withBody("survives-the-initializer"));

        assertThat(get(server.getLocalPort(), "/persisted-with-initializer"), is("survives-the-initializer"));
        awaitS3BlobContains(keyPrefix, "/persisted-with-initializer");

        stopQuietly(server);
        server = null;

        // the local file the initializer will read is empty -- only S3 holds the state
        assertThat("the local persisted file must be empty for this test to mean anything",
            persistedExpectations.length(), is(0L));

        server = ClientAndServer.startClientAndServer(
            s3PersistenceConfiguration(persistedExpectationsPath, keyPrefix)
                .initializationJsonPath(persistedExpectationsPath),
            PortFactory.findFreePort());

        assertThat(get(server.getLocalPort(), "/persisted-with-initializer"), is("survives-the-initializer"));
    }

    private String get(int port, String path) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(
            HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    private void awaitS3BlobContains(String keyPrefix, String marker) throws Exception {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(20).toMillis();
        while (System.currentTimeMillis() < deadline) {
            ListObjectsV2Response listing = s3Client.listObjectsV2(b -> b.bucket(TEST_BUCKET).prefix(keyPrefix));
            if (listing.contents() != null) {
                for (var object : listing.contents()) {
                    String body = new String(s3Client.getObjectAsBytes(g -> g.bucket(TEST_BUCKET).key(object.key())).asByteArray());
                    if (body.contains(marker)) {
                        return;
                    }
                }
            }
            Thread.sleep(200);
        }
        StringBuilder allKeys = new StringBuilder();
        ListObjectsV2Response everything = s3Client.listObjectsV2(b -> b.bucket(TEST_BUCKET));
        if (everything.contents() != null) {
            everything.contents().forEach(o -> allKeys.append("\n  ").append(o.key()));
        }
        throw new AssertionError("persisted document containing '" + marker + "' never appeared in S3 under prefix " + keyPrefix
            + "; all bucket keys:" + allKeys);
    }
}
