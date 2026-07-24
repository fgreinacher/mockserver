package org.mockserver.state;

import java.nio.file.Path;

/**
 * Key conventions shared by the object-store backed {@link BlobStore}
 * implementations that apply a {@code blobStoreKeyPrefix} (S3, GCS, Azure);
 * {@link #forPersistedFile(BlobStore, java.nio.file.Path)} additionally governs
 * the key for EVERY store other than {@link FilesystemBlobStore}.
 * <p>
 * Object stores are NOT filesystems: their keys are opaque strings in which
 * {@code /} is only a display convention. Two shapes that a filesystem
 * tolerates cause trouble as object names, and only one of them is fatal:
 * <ul>
 *     <li>a DOUBLED {@code //} — FATAL. Produced whenever a prefix that already
 *     ends in {@code /} is concatenated with a key that begins with one. MinIO
 *     rejects it outright with HTTP 400 "Object name contains unsupported
 *     characters", so the write never lands;</li>
 *     <li>a LEADING {@code /} — accepted, but undesirable. It is a legal byte
 *     in an S3 object name and {@code get} round-trips the very same key, so
 *     writes and reads DO work; the cost is that {@code /a/b} is a distinct
 *     name from {@code a/b} and most path-like tools cannot browse to it. When
 *     the leading {@code /} came from an absolute local path, as the persisted
 *     document's key once did, the object was also named after the writing
 *     container's filesystem layout, so an instance that resolved that path
 *     differently looked under a different name and silently found nothing.</li>
 * </ul>
 * These helpers normalise both away so that prefix + key always yields a
 * single-separator, leading-slash-free, portable object name whether the
 * configured {@code blobStoreKeyPrefix} is empty, ends in {@code /}, ends
 * without one, or (mistakenly) starts with one.
 */
public final class BlobKeys {

    private static final char SEPARATOR = '/';

    private BlobKeys() {
    }

    /**
     * Joins a configured key prefix and a blob key into an object-store key
     * with EXACTLY one separator between them, no leading separator, and no
     * repeated separators anywhere.
     * <p>
     * Examples (prefix, key) → result:
     * <pre>
     *   ("",             "expectations.json") → "expectations.json"
     *   ("mockserver",   "expectations.json") → "mockserver/expectations.json"
     *   ("mockserver/",  "expectations.json") → "mockserver/expectations.json"
     *   ("/mockserver/", "/expectations.json") → "mockserver/expectations.json"
     *   ("mockserver/",  "")                  → "mockserver/"
     * </pre>
     * The empty-key case keeps the trailing separator on purpose: the only
     * caller that passes an empty key is {@code list("")}, which must scope
     * the listing to the prefix rather than to every key that merely starts
     * with the same characters.
     *
     * @param keyPrefix the configured {@code blobStoreKeyPrefix} (may be null or empty)
     * @param key       the blob key (may be null or empty)
     * @return a valid object-store key
     */
    public static String join(String keyPrefix, String key) {
        String prefix = normalizePrefix(keyPrefix);
        String normalizedKey = normalize(key);
        if (prefix.isEmpty()) {
            return normalizedKey;
        }
        if (normalizedKey.isEmpty()) {
            return prefix + SEPARATOR;
        }
        return prefix + SEPARATOR + normalizedKey;
    }

    /**
     * Reverses {@link #join(String, String)}, recovering the NORMALISED blob
     * key from an object-store key. It is an inverse only modulo that
     * normalisation: {@code stripPrefix(p, join(p, "/x.json"))} returns
     * {@code x.json}, not {@code /x.json}. Keys that do not carry the prefix
     * are returned unchanged, matching the tolerant behaviour of the previous
     * {@code startsWith}-based implementations.
     *
     * @param keyPrefix the configured {@code blobStoreKeyPrefix} (may be null or empty)
     * @param storeKey  the object-store key as returned by the cloud SDK
     * @return the blob key without the prefix
     */
    public static String stripPrefix(String keyPrefix, String storeKey) {
        if (storeKey == null) {
            return "";
        }
        String prefix = normalizePrefix(keyPrefix);
        if (prefix.isEmpty()) {
            return storeKey;
        }
        String prefixWithSeparator = prefix + SEPARATOR;
        if (storeKey.startsWith(prefixWithSeparator)) {
            return storeKey.substring(prefixWithSeparator.length());
        }
        if (storeKey.equals(prefix)) {
            return "";
        }
        return storeKey;
    }

    /**
     * Derives the object-store key under which a persisted document is stored.
     * <p>
     * For the {@link FilesystemBlobStore} the key IS the file path, so the
     * absolute path is kept — the store resolves it back to the very file the
     * pre-blob-store direct-I/O implementation wrote.
     * <p>
     * For every other store the key is the FILE NAME alone. Embedding the
     * absolute local path in a cloud object name produced an invalid key (a
     * leading {@code /}, doubled up to {@code //} by any prefix ending in a
     * separator, which MinIO rejects with HTTP 400) and tied the object name
     * to the writing container's filesystem layout, so an instance started
     * from a different directory silently restored nothing.
     *
     * @param blobStore the store the key is destined for
     * @param filePath  the configured local persistence path
     * @return the key to use with {@code blobStore}
     */
    public static String forPersistedFile(BlobStore blobStore, Path filePath) {
        if (blobStore instanceof FilesystemBlobStore) {
            return filePath.toAbsolutePath().toString();
        }
        Path fileName = filePath.getFileName();
        // getFileName() is null only for a root path such as "/" or "C:\", which cannot
        // be a persistence file; fall back to the normalized path rather than an NPE.
        return fileName != null ? normalize(fileName.toString()) : normalize(filePath.toString());
    }

    /**
     * Normalizes a key: drops any leading separator and collapses runs of
     * separators into one. A trailing separator is preserved because callers
     * use it to scope a {@code list} to a logical folder.
     *
     * @param key the key to normalize (may be null)
     * @return the normalized key, never null
     */
    public static String normalize(String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        StringBuilder normalized = new StringBuilder(key.length());
        for (int index = 0; index < key.length(); index++) {
            char character = key.charAt(index);
            if (character == SEPARATOR) {
                // drop a separator that is leading or that follows another separator
                if (normalized.length() == 0 || normalized.charAt(normalized.length() - 1) == SEPARATOR) {
                    continue;
                }
            }
            normalized.append(character);
        }
        return normalized.toString();
    }

    /**
     * Normalizes a prefix: as {@link #normalize(String)} but also drops any
     * trailing separator, so callers can append exactly one themselves.
     *
     * @param keyPrefix the prefix to normalize (may be null)
     * @return the normalized prefix, never null and never ending in a separator
     */
    public static String normalizePrefix(String keyPrefix) {
        String normalized = normalize(keyPrefix);
        while (normalized.endsWith(String.valueOf(SEPARATOR))) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
