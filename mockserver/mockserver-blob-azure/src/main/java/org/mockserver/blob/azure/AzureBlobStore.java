package org.mockserver.blob.azure;

import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.BlobListDetails;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.models.ListBlobsOptions;
import com.azure.storage.blob.options.BlobParallelUploadOptions;
import org.mockserver.state.Blob;
import org.mockserver.state.BlobKeys;
import org.mockserver.state.BlobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * {@link BlobStore} implementation backed by Azure Blob Storage.
 * Blob keys are mapped to Azure blob names with an optional
 * configurable prefix.
 * <p>
 * Metadata is stored as Azure blob metadata (custom key-value pairs
 * on the blob itself).
 * <p>
 * Thread-safety: {@link BlobServiceClient} is thread-safe; this class
 * adds no mutable state beyond the injected client and configuration.
 */
public class AzureBlobStore implements BlobStore {

    private static final Logger LOG = LoggerFactory.getLogger(AzureBlobStore.class);

    private final BlobContainerClient containerClient;
    private final String keyPrefix;

    /**
     * Creates an Azure blob store.
     *
     * @param containerClient the Azure container client (caller owns lifecycle)
     * @param keyPrefix       optional key prefix; empty string for no prefix
     */
    public AzureBlobStore(BlobContainerClient containerClient, String keyPrefix) {
        this.containerClient = containerClient;
        this.keyPrefix = keyPrefix != null ? keyPrefix : "";
    }

    /**
     * Composes the Azure blob name from the configured prefix and the blob key,
     * via {@link BlobKeys#join(String, String)} so the result carries exactly
     * one separator between prefix and key and never a leading or doubled
     * {@code /}.
     */
    private String toAzureName(String key) {
        return BlobKeys.join(keyPrefix, key);
    }

    private String fromAzureName(String azureName) {
        return BlobKeys.stripPrefix(keyPrefix, azureName);
    }

    @Override
    public void put(String key, byte[] data, Map<String, String> metadata) {
        String azureName = toAzureName(key);
        Map<String, String> encodedMeta = metadata != null ? encodeMetadataKeys(metadata) : Collections.emptyMap();

        var blobClient = containerClient.getBlobClient(azureName);

        // Upload data and metadata atomically via BlobParallelUploadOptions
        // so that a concurrent get() never sees data without its metadata.
        BlobParallelUploadOptions uploadOptions = new BlobParallelUploadOptions(
            new ByteArrayInputStream(data), data.length)
            .setMetadata(encodedMeta.isEmpty() ? null : encodedMeta);
        blobClient.uploadWithResponse(uploadOptions, null, null);

        LOG.debug("put blob '{}' to azure://{}/{} ({} bytes, {} metadata entries)",
            key, containerClient.getBlobContainerName(), azureName, data.length, encodedMeta.size());
    }

    @Override
    public Optional<Blob> get(String key) {
        String azureName = toAzureName(key);
        var blobClient = containerClient.getBlobClient(azureName);

        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            blobClient.downloadStream(outputStream);
            byte[] data = outputStream.toByteArray();

            var properties = blobClient.getProperties();
            Map<String, String> encodedMeta = properties.getMetadata();
            Map<String, String> metadata = encodedMeta != null
                ? decodeMetadataKeys(encodedMeta)
                : Collections.emptyMap();

            return Optional.of(new Blob(key, data, metadata));
        } catch (BlobStorageException e) {
            if (e.getStatusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public List<String> list(String prefix) {
        String azurePrefix = toAzureName(prefix);

        ListBlobsOptions options = new ListBlobsOptions()
            .setPrefix(azurePrefix)
            .setDetails(new BlobListDetails().setRetrieveMetadata(false));

        return containerClient.listBlobs(options, null).stream()
            .map(BlobItem::getName)
            .map(this::fromAzureName)
            .collect(Collectors.toList());
    }

    @Override
    public boolean delete(String key) {
        String azureName = toAzureName(key);
        var blobClient = containerClient.getBlobClient(azureName);

        try {
            if (!blobClient.exists()) {
                return false;
            }
            blobClient.delete();
            LOG.debug("deleted blob '{}' from azure://{}/{}", key,
                containerClient.getBlobContainerName(), azureName);
            return true;
        } catch (BlobStorageException e) {
            if (e.getStatusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    /**
     * Reversible metadata key encoding for Azure compatibility.
     * <p>
     * Azure blob metadata keys must be valid C# identifiers:
     * {@code [a-zA-Z_][a-zA-Z0-9_]*}. Original keys may contain
     * characters like {@code -}, {@code =}, {@code .}, etc.
     * <p>
     * Encoding scheme (fixed-width prefix-hex escape):
     * <ul>
     *   <li>Literal underscore {@code _} is escaped to {@code _005f}
     *       (its lowercase hex code unit)</li>
     *   <li>Any other character outside {@code [a-zA-Z0-9]} is
     *       escaped to {@code _XXXX} where {@code XXXX} is the
     *       FOUR-digit lowercase hex of the character's UTF-16 code
     *       unit</li>
     *   <li>Letters pass through unescaped; digits pass through
     *       unless leading, since an Azure key may not start with a
     *       digit</li>
     * </ul>
     * <p>
     * This is fully reversible for ALL keys: {@code decode(encode(k)).equals(k)}
     * for every string, including non-ASCII and non-BMP keys. Keys
     * with the same characters never collide because the escape is
     * injective (underscore itself is always escaped).
     * <p>
     * The escape width is deliberately fixed. An earlier version formatted with
     * {@code %02x}, which widens to four digits above {@code 0xFF} while the
     * decoder consumed exactly two, corrupting every non-Latin-1 key
     * ({@code 中文} decoded back as {@code N2de87}).
     *
     * @param metadata the original metadata map
     * @return a new map with Azure-safe encoded keys
     */
    static Map<String, String> encodeMetadataKeys(Map<String, String> metadata) {
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            String encoded = encodeKey(entry.getKey());
            result.put(encoded, entry.getValue());
        }
        return result;
    }

    /**
     * Decodes metadata keys that were encoded by {@link #encodeMetadataKeys}.
     *
     * @param metadata the Azure metadata map with encoded keys
     * @return a new map with the original decoded keys
     */
    static Map<String, String> decodeMetadataKeys(Map<String, String> metadata) {
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            String decoded = decodeKey(entry.getKey());
            result.put(decoded, entry.getValue());
        }
        return result;
    }

    /**
     * Number of hex digits in a single escape sequence. Fixed at four so that every UTF-16 code
     * unit -- not just those below {@code 0x100} -- encodes and decodes unambiguously.
     */
    private static final int ESCAPE_HEX_DIGITS = 4;
    /**
     * Total length of an escape sequence: the {@code _} marker plus its hex digits.
     */
    private static final int ESCAPE_LENGTH = 1 + ESCAPE_HEX_DIGITS;

    /**
     * Encode a single metadata key to an Azure-safe identifier.
     * <p>
     * Every non-alphanumeric character becomes {@code _XXXX}, a FIXED four-digit lowercase hex
     * escape of its UTF-16 code unit. The width is fixed at four because a variable-width escape
     * is not decodable: the previous implementation formatted with {@code %02x}, which silently
     * widened to four digits for any code point above {@code 0xFF} while the decoder always
     * consumed exactly two -- so {@code 中文} encoded to {@code _4e2d_6587} and decoded back to
     * {@code N2de87}, corrupting the key. Four digits covers the whole UTF-16 range; characters
     * outside the BMP are encoded as their two surrogate code units and recombine on decode.
     * <p>
     * A leading digit is escaped as well, because an Azure metadata key must begin with a letter
     * or an underscore -- escaping it makes the result start with {@code _} and removes the need
     * for the separate {@code _00} guard prefix the previous implementation used (that prefix is
     * ambiguous against a four-digit escape, which can itself begin {@code _00}).
     */
    private static String encodeKey(String key) {
        StringBuilder sb = new StringBuilder(key.length() * ESCAPE_LENGTH);
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            boolean alphabetic = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
            boolean digit = c >= '0' && c <= '9';
            // a digit passes through unless it is first: an Azure key may not start with one
            if (alphabetic || (digit && i > 0)) {
                sb.append(c);
            } else {
                sb.append('_');
                sb.append(String.format("%0" + ESCAPE_HEX_DIGITS + "x", (int) c));
            }
        }
        return sb.toString();
    }

    /**
     * Decode a single Azure metadata key back to the original, reversing {@link #encodeKey}.
     * <p>
     * Guarantees {@code decodeKey(encodeKey(k)).equals(k)} for every {@link String} {@code k},
     * including non-ASCII and non-BMP keys.
     */
    private static String decodeKey(String encoded) {
        StringBuilder sb = new StringBuilder(encoded.length());
        int i = 0;
        while (i < encoded.length()) {
            char c = encoded.charAt(i);
            if (c == '_' && i + ESCAPE_LENGTH <= encoded.length()
                && isHex(encoded, i + 1, i + ESCAPE_LENGTH)) {
                sb.append((char) Integer.parseInt(encoded.substring(i + 1, i + ESCAPE_LENGTH), 16));
                i += ESCAPE_LENGTH;
            } else {
                // Not a valid escape sequence; pass through literally
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /**
     * Whether {@code [from, to)} of {@code value} is entirely hex digits. Checked explicitly
     * rather than relying on {@link Integer#parseInt} throwing, because {@code parseInt} also
     * accepts a leading {@code +} or {@code -}, which would let a non-escape sequence such as
     * {@code _-12f} decode as if it were one.
     */
    private static boolean isHex(String value, int from, int to) {
        for (int i = from; i < to; i++) {
            char c = value.charAt(i);
            boolean hexDigit = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hexDigit) {
                return false;
            }
        }
        return true;
    }

    /**
     * Closes the Azure blob store. The Azure {@link BlobServiceClient}
     * and {@link BlobContainerClient} do not implement
     * {@link AutoCloseable} -- the underlying Netty/Reactor HTTP client
     * is managed by the SDK and does not expose an explicit close. This
     * is a documented no-op.
     */
    @Override
    public void close() {
        // Azure BlobServiceClient / BlobContainerClient do not implement
        // AutoCloseable. The SDK manages the underlying HTTP client
        // lifecycle internally. No explicit resource release is needed.
        LOG.debug("close() called on AzureBlobStore (no-op: Azure SDK manages HTTP client lifecycle)");
    }
}
