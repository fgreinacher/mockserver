package org.mockserver.blob.gcs;

import com.google.api.gax.paging.Page;
import com.google.cloud.storage.*;
import org.mockserver.state.Blob;
import org.mockserver.state.BlobKeys;
import org.mockserver.state.BlobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * {@link BlobStore} implementation backed by Google Cloud Storage.
 * Blob keys are mapped to GCS object names with an optional
 * configurable prefix.
 * <p>
 * Metadata is stored as GCS object metadata (custom key-value pairs
 * on the object itself).
 * <p>
 * Thread-safety: {@link Storage} is thread-safe; this class adds no
 * mutable state beyond the injected client and configuration.
 */
public class GcsBlobStore implements BlobStore {

    private static final Logger LOG = LoggerFactory.getLogger(GcsBlobStore.class);

    private final Storage storage;
    private final String bucket;
    private final String keyPrefix;

    /**
     * Creates a GCS blob store.
     *
     * @param storage   the GCS storage client (caller owns lifecycle)
     * @param bucket    the GCS bucket name
     * @param keyPrefix optional key prefix; empty string for no prefix
     */
    public GcsBlobStore(Storage storage, String bucket, String keyPrefix) {
        this.storage = storage;
        this.bucket = bucket;
        this.keyPrefix = keyPrefix != null ? keyPrefix : "";
    }

    /**
     * Composes the GCS object name from the configured prefix and the blob key,
     * via {@link BlobKeys#join(String, String)} so the result carries exactly
     * one separator between prefix and key and never a leading or doubled
     * {@code /}.
     */
    private String toGcsName(String key) {
        return BlobKeys.join(keyPrefix, key);
    }

    private String fromGcsName(String gcsName) {
        return BlobKeys.stripPrefix(keyPrefix, gcsName);
    }

    @Override
    public void put(String key, byte[] data, Map<String, String> metadata) {
        String gcsName = toGcsName(key);
        Map<String, String> gcsMeta = metadata != null ? new HashMap<>(metadata) : Collections.emptyMap();

        BlobInfo blobInfo = BlobInfo.newBuilder(BlobId.of(bucket, gcsName))
            .setMetadata(gcsMeta)
            .build();

        storage.create(blobInfo, data);
        LOG.debug("put blob '{}' to gs://{}/{} ({} bytes, {} metadata entries)",
            key, bucket, gcsName, data.length, gcsMeta.size());
    }

    @Override
    public Optional<Blob> get(String key) {
        String gcsName = toGcsName(key);
        com.google.cloud.storage.Blob gcsBlob = storage.get(BlobId.of(bucket, gcsName));
        // storage.get() returns null when the object does not exist --
        // no need for a separate gcsBlob.exists() network call.
        if (gcsBlob == null) {
            return Optional.empty();
        }

        byte[] data = gcsBlob.getContent();
        Map<String, String> metadata = gcsBlob.getMetadata() != null
            ? new HashMap<>(gcsBlob.getMetadata())
            : Collections.emptyMap();

        return Optional.of(new Blob(key, data, metadata));
    }

    @Override
    public List<String> list(String prefix) {
        String gcsPrefix = toGcsName(prefix);

        Page<com.google.cloud.storage.Blob> page = storage.list(
            bucket,
            Storage.BlobListOption.prefix(gcsPrefix)
        );

        return StreamSupport.stream(page.iterateAll().spliterator(), false)
            .map(b -> fromGcsName(b.getName()))
            .collect(Collectors.toList());
    }

    @Override
    public boolean delete(String key) {
        String gcsName = toGcsName(key);
        BlobId blobId = BlobId.of(bucket, gcsName);
        boolean deleted = storage.delete(blobId);
        if (deleted) {
            LOG.debug("deleted blob '{}' from gs://{}/{}", key, bucket, gcsName);
        }
        return deleted;
    }

    /**
     * Closes the underlying GCS {@link Storage} client, releasing its
     * HTTP transport resources. {@code Storage} implements
     * {@link AutoCloseable}.
     */
    @Override
    public void close() {
        if (storage != null) {
            try {
                storage.close();
                LOG.debug("closed GCS storage client for bucket '{}'", bucket);
            } catch (Exception e) {
                LOG.warn("failed to close GCS storage client for bucket '{}': {}",
                    bucket, e.getMessage());
            }
        }
    }
}
