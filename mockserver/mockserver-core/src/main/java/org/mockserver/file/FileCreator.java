package org.mockserver.file;

import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

import static org.slf4j.event.Level.ERROR;

public class FileCreator {

    private static final MockServerLogger MOCK_SERVER_LOGGER = new MockServerLogger(FileCreator.class);

    /**
     * Atomically write {@code content} to {@code target}, applying restrictive owner-only permissions
     * ({@code 0600}) when {@code ownerOnly} is true (private keys, key stores) or world-readable
     * ({@code 0644}) permissions otherwise (public certificates), on POSIX filesystems.
     * <p>
     * The bytes are written to a sibling temp file (created up-front with the target permissions, so a
     * private key is never momentarily world-readable) then moved into place with {@code ATOMIC_MOVE}
     * where supported, so a concurrent reader never observes a truncated or empty file. On non-POSIX
     * filesystems (e.g. Windows) the permission step is skipped and the platform default is used — the
     * atomic write still applies.
     *
     * @param type      human-readable description of the artefact, used in the failure message
     * @param target    the destination file
     * @param content   the file content (UTF-8 encoded)
     * @param ownerOnly true to restrict to the owning user (private key material), false for public data
     * @throws IOException if the parent directory is missing/unwritable, or the write/move fails — the
     *                     caller MUST fail fast rather than swallow this, otherwise a later read surfaces
     *                     a misleading "cannot read PEM" error (defect C13)
     */
    public static void writeToFileAtomically(String type, File target, String content, boolean ownerOnly) throws IOException {
        writeToFileAtomically(type, target, content.getBytes(StandardCharsets.UTF_8), ownerOnly);
    }

    /**
     * Binary variant of {@link #writeToFileAtomically(String, File, String, boolean)} — see that method
     * for the atomicity and permission guarantees. Used for key stores (JKS) that carry private key
     * material and must be owner-only ({@code 0600}).
     */
    public static void writeToFileAtomically(String type, File target, byte[] content, boolean ownerOnly) throws IOException {
        createParentDirs(target);
        File parent = target.getCanonicalFile().getParentFile();
        if (parent == null || !parent.isDirectory()) {
            throw new IOException("Unable to write " + type + " to " + target.getAbsolutePath() + " — parent directory does not exist");
        }
        if (!parent.canWrite()) {
            throw new IOException("Unable to write " + type + " to " + target.getAbsolutePath() + " — directory " + parent.getAbsolutePath() + " is not writable");
        }
        boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        String permissions = ownerOnly ? "rw-------" : "rw-r--r--";
        Path parentPath = parent.toPath();
        Path tempPath = posix
            ? Files.createTempFile(parentPath, ".mockserver", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(permissions)))
            : Files.createTempFile(parentPath, ".mockserver", ".tmp");
        try {
            Files.write(tempPath, content);
            if (posix) {
                // re-assert in case the create-time attribute was widened by an aggressive umask
                Files.setPosixFilePermissions(tempPath, PosixFilePermissions.fromString(permissions));
            }
            try {
                Files.move(tempPath, target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException atomicMoveNotSupported) {
                Files.move(tempPath, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tempPath);
        }
    }

    public static File createFileIfNotExists(String type, File file) {
        if (!file.exists()) {
            try {
                createParentDirs(file);
                if (!file.createNewFile()) {
                    MOCK_SERVER_LOGGER.logEvent(
                        new LogEntry()
                            .setLogLevel(ERROR)
                            .setMessageFormat("failed to create the file{}while attempting to save " + type + " file")
                            .setArguments(file.getAbsolutePath())
                    );

                }
            } catch (Throwable throwable) {
                MOCK_SERVER_LOGGER.logEvent(
                    new LogEntry()
                        .setLogLevel(ERROR)
                        .setMessageFormat("failed to create the file{}while attempting to save " + type + " file")
                        .setArguments(file.getAbsolutePath())
                        .setThrowable(throwable)
                );
            }
        }
        return file;
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    public static void createParentDirs(File file) throws IOException {
        File parent = file.getCanonicalFile().getParentFile();
        if (parent == null) {
            /*
             * The given directory is a filesystem root. All zero of its ancestors exist. This doesn't
             * mean that the root itself exists -- consider x:\ on a Windows machine without such a drive
             * -- or even that the caller can create it, but this method makes no such guarantees even for
             * non-root files.
             */
            return;
        }
        createParentDirs(parent);
        if (!parent.exists()) {
            parent.mkdirs();
        }
        if (!parent.isDirectory()) {
            throw new IOException("Unable to create parent directories of " + file);
        }
    }

}
