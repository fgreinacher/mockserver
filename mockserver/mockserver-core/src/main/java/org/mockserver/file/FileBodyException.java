package org.mockserver.file;

/**
 * Thrown when the file referenced by a {@code FileBody} cannot be read while materialising a response
 * (or request-matching) body. Carries the offending file path for server-side logging; callers in the
 * response pipeline turn it into a clean, logged 500 whose body does NOT leak the path.
 *
 * @author jamesdbloom
 */
public class FileBodyException extends RuntimeException {

    private final String filePath;

    public FileBodyException(String filePath, Throwable cause) {
        super("Exception while loading file body \"" + filePath + "\"", cause);
        this.filePath = filePath;
    }

    public String getFilePath() {
        return filePath;
    }
}
