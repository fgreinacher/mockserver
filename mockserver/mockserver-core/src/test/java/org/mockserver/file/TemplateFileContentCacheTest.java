package org.mockserver.file;

import com.sun.management.ThreadMXBean;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.Assert.assertThrows;

public class TemplateFileContentCacheTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Before
    public void resetCache() {
        TemplateFileContentCache.clear();
    }

    private void writeFile(File file, String content) throws Exception {
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private void ageFile(File file) throws Exception {
        // Move mtime comfortably outside the recently-modified grace window so the file is cacheable.
        Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(System.currentTimeMillis() - (TemplateFileContentCache.RECENTLY_MODIFIED_GRACE_MILLIS * 5)));
    }

    @Test
    public void shouldReadFileContent() throws Exception {
        File file = temporaryFolder.newFile("template.vtl");
        writeFile(file, "hello world");

        assertThat(TemplateFileContentCache.readFileFromClassPathOrPath(file.getAbsolutePath()), is("hello world"));
    }

    @Test
    public void shouldPickUpEditOnNextRequest() throws Exception {
        File file = temporaryFolder.newFile("template.vtl");
        writeFile(file, "original content");
        ageFile(file);

        assertThat(TemplateFileContentCache.readFileFromClassPathOrPath(file.getAbsolutePath()), is("original content"));

        writeFile(file, "edited content that is a different length");

        assertThat(TemplateFileContentCache.readFileFromClassPathOrPath(file.getAbsolutePath()), is("edited content that is a different length"));
    }

    @Test
    public void shouldPickUpSameSizeSameSecondEdit() throws Exception {
        // Overwrite in place with content of the same length, back-to-back: mtime and size may be
        // identical to the cached stamp, so only the recently-modified grace rule catches the edit.
        File file = temporaryFolder.newFile("template.vtl");
        writeFile(file, "AAAAAAAAAAAAAAAA");

        assertThat(TemplateFileContentCache.readFileFromClassPathOrPath(file.getAbsolutePath()), is("AAAAAAAAAAAAAAAA"));

        writeFile(file, "BBBBBBBBBBBBBBBB");

        assertThat(TemplateFileContentCache.readFileFromClassPathOrPath(file.getAbsolutePath()), is("BBBBBBBBBBBBBBBB"));
    }

    @Test
    public void shouldNotReReadUnchangedFile() throws Exception {
        File file = temporaryFolder.newFile("template.vtl");
        writeFile(file, "stable content");
        ageFile(file);
        String path = file.getAbsolutePath();

        long before = TemplateFileContentCache.DISK_READS.get();
        assertThat(TemplateFileContentCache.readFileFromClassPathOrPath(path), is("stable content"));
        assertThat(TemplateFileContentCache.readFileFromClassPathOrPath(path), is("stable content"));
        assertThat(TemplateFileContentCache.readFileFromClassPathOrPath(path), is("stable content"));

        // Only the first read touched disk; the cheap metadata check served the rest.
        assertThat(TemplateFileContentCache.DISK_READS.get() - before, is(1L));
    }

    @Test
    public void shouldNotReReadClasspathResource() {
        String resource = "org/mockserver/socket/CertificateAuthorityCertificate.pem";

        long before = TemplateFileContentCache.DISK_READS.get();
        String first = TemplateFileContentCache.readFileFromClassPathOrPath(resource);
        String second = TemplateFileContentCache.readFileFromClassPathOrPath(resource);

        assertThat(second, is(first));
        assertThat(TemplateFileContentCache.DISK_READS.get() - before, is(1L));
    }

    @Test
    public void shouldThrowSameAsUncachedWhenCachedFileDeleted() throws Exception {
        File file = temporaryFolder.newFile("template.vtl");
        writeFile(file, "content");
        ageFile(file);
        String path = file.getAbsolutePath();

        // Populate the cache with a stamp that would otherwise be served.
        assertThat(TemplateFileContentCache.readFileFromClassPathOrPath(path), is("content"));

        Files.delete(file.toPath());

        RuntimeException exception = assertThrows(RuntimeException.class, () ->
            TemplateFileContentCache.readFileFromClassPathOrPath(path)
        );
        assertThat(exception.getMessage(), containsString(path));
    }

    @Test
    public void shouldThrowSameAsUncachedForNonExistentFile() {
        String path = "/nonexistent/path/to/template.vtl";

        RuntimeException cachedException = assertThrows(RuntimeException.class, () ->
            TemplateFileContentCache.readFileFromClassPathOrPath(path)
        );
        RuntimeException uncachedException = assertThrows(RuntimeException.class, () ->
            FileReader.readFileFromClassPathOrPath(path)
        );
        assertThat(cachedException.getMessage(), is(uncachedException.getMessage()));
        assertThat(cachedException.getMessage(), containsString(path));
    }

    @Test
    public void shouldBoundCacheSize() throws Exception {
        int overCap = TemplateFileContentCache.MAX_CACHED_FILES + 25;
        for (int i = 0; i < overCap; i++) {
            File file = temporaryFolder.newFile("template-" + i + ".vtl");
            writeFile(file, "content-" + i);
            TemplateFileContentCache.readFileFromClassPathOrPath(file.getAbsolutePath());
        }

        assertThat(TemplateFileContentCache.size(), lessThanOrEqualTo(TemplateFileContentCache.MAX_CACHED_FILES));
    }

    @Test
    public void shouldReadConcurrentlyWithoutError() throws Exception {
        File file = temporaryFolder.newFile("template.vtl");
        writeFile(file, "concurrent content");
        ageFile(file);
        String path = file.getAbsolutePath();

        int threads = 16;
        int readsPerThread = 200;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                tasks.add(() -> {
                    for (int i = 0; i < readsPerThread; i++) {
                        if (!"concurrent content".equals(TemplateFileContentCache.readFileFromClassPathOrPath(path))) {
                            return false;
                        }
                    }
                    return true;
                });
            }
            List<Future<Boolean>> results = executor.invokeAll(tasks, 30, TimeUnit.SECONDS);
            for (Future<Boolean> result : results) {
                assertThat(result.get(), is(true));
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void shouldAllocateFarLessWhenServedFromCache() throws Exception {
        ThreadMXBean threadMXBean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        Assume.assumeTrue("Thread allocated memory measurement not supported", threadMXBean.isThreadAllocatedMemorySupported());

        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 4096; i++) {
            builder.append("template-line-").append(i % 10).append('\n');
        }
        File file = temporaryFolder.newFile("large-template.vtl");
        writeFile(file, builder.toString());
        ageFile(file);
        String path = file.getAbsolutePath();

        int iterations = 500;

        // Warm up both paths (JIT, class init) before measuring.
        for (int i = 0; i < 50; i++) {
            FileReader.readFileFromClassPathOrPath(path);
            TemplateFileContentCache.readFileFromClassPathOrPath(path);
        }

        long uncachedStart = threadMXBean.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < iterations; i++) {
            FileReader.readFileFromClassPathOrPath(path);
        }
        long uncachedBytes = threadMXBean.getCurrentThreadAllocatedBytes() - uncachedStart;

        long cachedStart = threadMXBean.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < iterations; i++) {
            TemplateFileContentCache.readFileFromClassPathOrPath(path);
        }
        long cachedBytes = threadMXBean.getCurrentThreadAllocatedBytes() - cachedStart;

        long uncachedPerOp = uncachedBytes / iterations;
        long cachedPerOp = cachedBytes / iterations;
        System.out.println("[TemplateFileContentCache] bytes/op uncached=" + uncachedPerOp + " cached=" + cachedPerOp);

        // Motivation, not a gate: serving from cache must allocate far less than re-reading and decoding
        // the whole file. The margin is large (a stat vs a full byte[]+String), so a 10x factor is safe.
        assertThat(cachedPerOp * 10, lessThanOrEqualTo(uncachedPerOp));
    }
}
