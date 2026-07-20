package org.mockserver.blob.s3;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.mockserver.state.BlobStore;
import org.mockserver.state.contract.BlobStoreContract;
import org.mockserver.test.DockerAvailability;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;

/**
 * Runs the shared {@link BlobStoreContract} against a real MinIO
 * instance via Testcontainers. Docker-gated: skips if Docker is
 * not available.
 */
public class S3BlobStoreContractTest extends BlobStoreContract {

    private static final String MINIO_IMAGE = "minio/minio:RELEASE.2024-11-07T00-52-20Z";
    private static final String ACCESS_KEY = "minioadmin";
    private static final String SECRET_KEY = "minioadmin";
    private static final String TEST_BUCKET = "mockserver-test";

    @SuppressWarnings("resource")
    private static GenericContainer<?> minioContainer;
    private static S3Client s3Client;

    @BeforeClass
    public static void startMinIO() {
        // Wrapped: DockerClientFactory.isDockerAvailable() THROWS rather than returning
        // false for post-connection failures (e.g. Ryuk rejected by a user-namespace
        // remapped daemon), which would turn this skip into a hard ERROR.
        Assume.assumeTrue(
            "Docker is not available -- skipping S3 integration test",
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

        String endpoint = "http://" + minioContainer.getHost() + ":" + minioContainer.getMappedPort(9000);

        s3Client = S3Client.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.US_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
            .forcePathStyle(true)
            .build();

        // Create the test bucket
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

    @Override
    protected BlobStore createStore() {
        // Use a unique key prefix per test to isolate test data
        String prefix = "test-" + UUID.randomUUID() + "/";
        return new S3BlobStore(s3Client, TEST_BUCKET, prefix);
    }

    /**
     * S3 carries user metadata in {@code x-amz-meta-*} HTTP headers, and header field names are
     * ASCII tokens (RFC 9110 &sect;5.1), so a key containing characters above {@code U+00FF}
     * cannot be represented. S3 correctly refuses the write rather than storing something else.
     */
    @Override
    protected boolean supportsNonAsciiMetadataKeys() {
        return false;
    }

    /**
     * Pins the rejection to a genuine "this request is invalid" response from S3, so the contract
     * cannot be satisfied by an unrelated failure (a stopped container, a bad credential, a
     * connection reset) that merely happens to throw.
     */
    @Override
    protected void assertNonAsciiMetadataKeyRejection(RuntimeException rejection) {
        assertThat("expected S3 to reject the unrepresentable metadata key, but got: " + rejection,
            rejection, instanceOf(S3Exception.class));
        assertThat("expected a 4xx invalid-request rejection, not a transport or auth failure",
            ((S3Exception) rejection).statusCode(), is(400));
    }
}
