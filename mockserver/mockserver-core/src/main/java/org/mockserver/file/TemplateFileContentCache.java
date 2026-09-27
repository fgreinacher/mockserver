package org.mockserver.file;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-path cache of template file content, validated on every read by a cheap metadata (single stat)
 * check rather than by re-reading the file.
 * <p>
 * The contract this preserves is that an edited {@code templateFile} takes effect on the next request:
 * the cached content is only trusted while a one-syscall {@link Files#readAttributes} confirms the file
 * has not changed. When the size, last-modified time or file key (inode, where the platform exposes one)
 * differs, or the file cannot be stat-ed (deleted, replaced, permissions), the read falls through to the
 * canonical {@link FileReader#readFileFromClassPathOrPath(String)} path, which reproduces the exact
 * content and the exact error behaviour of an uncached read.
 * <p>
 * This is used only for template files ({@link org.mockserver.model.HttpTemplate}); FILE bodies and other
 * one-time reads (schemas, PEM material, initializers) stay on the uncached path.
 */
public class TemplateFileContentCache {

    // Bounds the number of distinct template files cached, so a client that points templateFile at an
    // unbounded set of distinct paths cannot grow memory without limit. Matches the template engines'
    // parsed-template cache bound (MustacheTemplateEngine.PARSED_TEMPLATE_CACHE_MAX).
    static final int MAX_CACHED_FILES = 1000;

    // A file whose mtime is within this window of when we cached it is never served from cache. Some
    // filesystems record mtime at 1s granularity, so an in-place edit that keeps the same size and lands
    // in the same tick as the read that populated the cache would otherwise be invisible to a
    // size+mtime+fileKey comparison. Requiring the cached content's mtime to sit at least this far in the
    // past before we trust an unchanged stamp closes that window: a recently-modified file is re-read
    // until it settles, so no same-second edit is ever missed. The cost is re-reading a file that is
    // being actively edited, which is transient.
    static final long RECENTLY_MODIFIED_GRACE_MILLIS = 2000L;

    // Number of times a read fell through to the canonical (disk/classpath) reader. Used by tests to
    // prove that an unchanged file is not re-read; not part of the public contract.
    static final AtomicLong DISK_READS = new AtomicLong();

    private static final Map<String, Entry> CACHE = Collections.synchronizedMap(new LinkedHashMap<String, Entry>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
            return size() > MAX_CACHED_FILES;
        }
    });

    public static String readFileFromClassPathOrPath(String filename) {
        Entry entry = CACHE.get(filename);
        if (entry != null && entry.isValid()) {
            return entry.content;
        }
        DISK_READS.incrementAndGet();
        String content = FileReader.readFileFromClassPathOrPath(filename);
        CACHE.put(filename, Entry.create(filename, content));
        return content;
    }

    static void clear() {
        CACHE.clear();
        DISK_READS.set(0);
    }

    static int size() {
        return CACHE.size();
    }

    private static final class Entry {
        private final String content;
        // null when the source is an immutable classpath resource (e.g. a jar entry), which cannot change
        // during a run and so needs no revalidation; otherwise the filesystem path to stat.
        private final Path path;
        private final long size;
        private final long mtimeMillis;
        private final Object fileKey;
        private final long cachedAtMillis;

        private Entry(String content, Path path, long size, long mtimeMillis, Object fileKey, long cachedAtMillis) {
            this.content = content;
            this.path = path;
            this.size = size;
            this.mtimeMillis = mtimeMillis;
            this.fileKey = fileKey;
            this.cachedAtMillis = cachedAtMillis;
        }

        private static Entry create(String filename, String content) {
            Path path = resolvePathForValidation(filename);
            if (path == null) {
                return new Entry(content, null, 0L, 0L, null, 0L);
            }
            try {
                BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
                return new Entry(content, path, attributes.size(), attributes.lastModifiedTime().toMillis(), attributes.fileKey(), System.currentTimeMillis());
            } catch (IOException | RuntimeException e) {
                // Could not stat immediately after a successful read; store a stamp that never validates so
                // subsequent reads fall through to the canonical reader (i.e. this file is effectively uncached).
                return new Entry(content, path, -1L, -1L, null, 0L);
            }
        }

        private boolean isValid() {
            if (path == null) {
                return true;
            }
            try {
                BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
                if (attributes.size() != size) {
                    return false;
                }
                if (attributes.lastModifiedTime().toMillis() != mtimeMillis) {
                    return false;
                }
                Object currentFileKey = attributes.fileKey();
                if (fileKey != null && currentFileKey != null && !fileKey.equals(currentFileKey)) {
                    return false;
                }
                return cachedAtMillis - mtimeMillis > RECENTLY_MODIFIED_GRACE_MILLIS;
            } catch (IOException | RuntimeException e) {
                return false;
            }
        }

        private static Path resolvePathForValidation(String filename) {
            URL url = FileReader.class.getClassLoader().getResource(filename);
            if (url != null) {
                if ("file".equals(url.getProtocol())) {
                    try {
                        return Paths.get(url.toURI());
                    } catch (URISyntaxException e) {
                        return null;
                    }
                }
                // A non-file classpath resource (jar entry etc.) cannot be edited in place during a run.
                return null;
            }
            // Not a classpath resource: a filesystem path, resolved against the working directory exactly as
            // new File(filename) is in FileReader.
            return Paths.get(filename);
        }
    }
}
