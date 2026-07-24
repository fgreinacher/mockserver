package org.mockserver.file;

import com.google.common.io.ByteStreams;

import java.io.*;
import java.nio.file.*;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * @author jamesdbloom
 */
public class FileReader {

    public static String readFileFromClassPathOrPath(String filePath) {
        try (InputStream inputStream = openStreamToFileFromClassPathOrPath(filePath)) {
            return new String(ByteStreams.toByteArray(inputStream), UTF_8.name());
        } catch (IOException ioe) {
            throw new RuntimeException("Exception while loading \"" + filePath + "\"", ioe);
        }
    }

    public static byte[] readBytesFromClassPathOrPath(String filePath) {
        try (InputStream inputStream = openStreamToFileFromClassPathOrPath(filePath)) {
            return ByteStreams.toByteArray(inputStream);
        } catch (IOException ioe) {
            throw new RuntimeException("Exception while loading \"" + filePath + "\"", ioe);
        }
    }

    public static InputStream openStreamToFileFromClassPathOrPath(String filename) throws FileNotFoundException {
        InputStream inputStream = FileReader.class.getClassLoader().getResourceAsStream(filename);
        if (inputStream == null) {
            File file;
            try {
                file = new File(filename).getCanonicalFile();
            } catch (IOException e) {
                throw new FileNotFoundException("Invalid file path: " + filename);
            }
            inputStream = new FileInputStream(file);
        }
        return inputStream;
    }

    public static Reader openReaderToFileFromClassPathOrPath(String filename) throws FileNotFoundException {
        return new InputStreamReader(openStreamToFileFromClassPathOrPath(filename));
    }

}
