package org.mockserver.blob.s3;

import org.mockserver.state.Blob;
import org.mockserver.state.BlobKeys;
import org.mockserver.state.BlobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * {@link BlobStore} implementation backed by AWS S3 (or any S3-compatible
 * store such as MinIO). Blob keys are mapped to S3 object keys with an
 * optional configurable prefix.
 * <p>
 * Metadata is stored as S3 user metadata on the object itself (the
 * {@code x-amz-meta-*} headers). This avoids a secondary metadata store
 * and keeps each blob's metadata atomically consistent with its data.
 * <p>
 * Thread-safety: {@link S3Client} is thread-safe; this class adds no
 * mutable state beyond the injected client and configuration.
 */
public class S3BlobStore implements BlobStore {

    private static final Logger LOG = LoggerFactory.getLogger(S3BlobStore.class);

    private final S3Client s3Client;
    private final String bucket;
    private final String keyPrefix;

    /**
     * Creates an S3 blob store.
     *
     * @param s3Client  the AWS S3 client
     * @param bucket    the S3 bucket name
     * @param keyPrefix optional key prefix (e.g. "mockserver/"); empty string
     *                  for no prefix
     */
    public S3BlobStore(S3Client s3Client, String bucket, String keyPrefix) {
        this.s3Client = s3Client;
        this.bucket = bucket;
        this.keyPrefix = keyPrefix != null ? keyPrefix : "";
    }

    /**
     * Composes the S3 object key from the configured prefix and the blob key.
     * Delegates to {@link BlobKeys#join(String, String)} so that the result
     * carries exactly one separator between prefix and key, never a leading
     * {@code /} and never a doubled {@code //} — both of which S3-compatible
     * stores reject (MinIO answers HTTP 400 "Object name contains unsupported
     * characters").
     */
    private String toS3Key(String key) {
        return BlobKeys.join(keyPrefix, key);
    }

    private String fromS3Key(String s3Key) {
        return BlobKeys.stripPrefix(keyPrefix, s3Key);
    }

    @Override
    public void put(String key, byte[] data, Map<String, String> metadata) {
        String s3Key = toS3Key(key);
        Map<String, String> s3Meta = metadata != null ? new HashMap<>(metadata) : Collections.emptyMap();

        PutObjectRequest request = PutObjectRequest.builder()
            .bucket(bucket)
            .key(s3Key)
            .metadata(s3Meta)
            .build();

        s3Client.putObject(request, RequestBody.fromBytes(data));
        LOG.debug("put blob '{}' to s3://{}/{} ({} bytes, {} metadata entries)",
            key, bucket, s3Key, data.length, s3Meta.size());
    }

    @Override
    public Optional<Blob> get(String key) {
        String s3Key = toS3Key(key);
        try {
            byte[] data;
            Map<String, String> metadata;
            try (var response = s3Client.getObject(GetObjectRequest.builder()
                .bucket(bucket)
                .key(s3Key)
                .build())) {
                // Capture metadata from the getObject response itself --
                // response.response() returns the GetObjectResponse which
                // carries the S3 user metadata. No separate headObject
                // call is needed.
                metadata = response.response().metadata() != null
                    ? new HashMap<>(response.response().metadata())
                    : Collections.emptyMap();
                data = response.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException("failed to read blob from S3: " + key, e);
            }

            return Optional.of(new Blob(key, data, metadata));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<String> list(String prefix) {
        String s3Prefix = toS3Key(prefix);

        ListObjectsV2Request request = ListObjectsV2Request.builder()
            .bucket(bucket)
            .prefix(s3Prefix)
            .build();

        List<String> keys = new ArrayList<>();
        ListObjectsV2Response response;
        do {
            response = s3Client.listObjectsV2(request);
            // The AWS SDK can return a null contents() list when no objects
            // match the prefix; guard against it so listing an empty/missing
            // prefix yields an empty result instead of throwing an NPE
            // (consistent with the GCS and Azure blob stores).
            if (response.contents() != null) {
                keys.addAll(response.contents().stream()
                    .map(S3Object::key)
                    .map(this::fromS3Key)
                    .collect(Collectors.toList()));
            }

            request = request.toBuilder()
                .continuationToken(response.nextContinuationToken())
                .build();
        } while (response.isTruncated());

        return keys;
    }

    @Override
    public boolean delete(String key) {
        String s3Key = toS3Key(key);
        try {
            // NOTE: head-then-delete is inherently non-atomic (TOCTOU).
            // S3 has no atomic conditional-delete operation, so another
            // client could delete or overwrite the object between the
            // headObject check and the deleteObject call. This is
            // acceptable for the BlobStore contract: the worst case is
            // returning true when the object was already deleted by a
            // concurrent caller, or deleting an object that was
            // concurrently re-created.
            s3Client.headObject(HeadObjectRequest.builder()
                .bucket(bucket)
                .key(s3Key)
                .build());
        } catch (NoSuchKeyException e) {
            return false;
        }

        s3Client.deleteObject(DeleteObjectRequest.builder()
            .bucket(bucket)
            .key(s3Key)
            .build());

        LOG.debug("deleted blob '{}' from s3://{}/{}", key, bucket, s3Key);
        return true;
    }

    /**
     * Closes the underlying {@link S3Client}, releasing its HTTP connection
     * pool and I/O threads.
     */
    @Override
    public void close() {
        if (s3Client != null) {
            s3Client.close();
            LOG.debug("closed S3 client for bucket '{}'", bucket);
        }
    }
}
