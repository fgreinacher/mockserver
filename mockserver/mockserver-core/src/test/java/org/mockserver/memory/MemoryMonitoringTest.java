package org.mockserver.memory;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockserver.configuration.Configuration;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayWithSize;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * Covers {@link MemoryMonitoring} CSV export (outputMemoryUsageCsv): header emission on construction,
 * data-row emission via logMemoryMetrics(), and the disabled path writing no file.
 */
public class MemoryMonitoringTest {

    // The exact ordered header keys produced by MemoryMonitoring.buildStatistics().
    private static final List<String> EXPECTED_HEADER_KEYS = Arrays.asList(
        "mockServerPort",
        "eventLogSize",
        "maxLogEntries",
        "expectationsSize",
        "maxExpectations",
        "heapInitialAllocation",
        "heapUsed",
        "heapCommitted",
        "heapMaxAllowed",
        "nonHeapInitialAllocation",
        "nonHeapUsed",
        "nonHeapCommitted",
        "nonHeapMaxAllowed"
    );
    // Index of the heapUsed column within a CSV row (JVM heap in use is always a positive number).
    private static final int HEAP_USED_COLUMN_INDEX = EXPECTED_HEADER_KEYS.indexOf("heapUsed");

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void shouldCreateCsvFileWithHeaderWhenOutputMemoryUsageCsvEnabled() throws Exception {
        // given
        Configuration configuration = configuration()
            .outputMemoryUsageCsv(true)
            .memoryUsageCsvDirectory(temporaryFolder.getRoot().getAbsolutePath());

        // when - construction writes the header row
        new MemoryMonitoring(configuration, null, null);

        // then - (a) a CSV file was created
        File csvFile = singleCsvFile();
        assertThat("expected a memoryUsage CSV file to be created", csvFile.exists(), is(true));

        // and - (b) the header row exactly matches the buildStatistics() keys
        List<String> lines = readLines(csvFile);
        assertThat(lines.size(), is(1));
        assertThat(Arrays.asList(lines.get(0).split(",", -1)), is(EXPECTED_HEADER_KEYS));
    }

    @Test
    public void shouldAppendDataRowWithMatchingColumnCountAndNumericHeap() throws Exception {
        // given
        Configuration configuration = configuration()
            .outputMemoryUsageCsv(true)
            .memoryUsageCsvDirectory(temporaryFolder.getRoot().getAbsolutePath());
        MemoryMonitoring memoryMonitoring = new MemoryMonitoring(configuration, null, null);

        // when - trigger the write path directly (no sleeps, no listener frequency gating)
        memoryMonitoring.logMemoryMetrics();

        // then - (c) header + one data row, data row has the same column count as the header
        File csvFile = singleCsvFile();
        List<String> lines = readLines(csvFile);
        assertThat(lines.size(), is(2));

        String[] headerColumns = lines.get(0).split(",", -1);
        String[] dataColumns = lines.get(1).split(",", -1);
        assertThat(headerColumns, arrayWithSize(EXPECTED_HEADER_KEYS.size()));
        assertThat(dataColumns, arrayWithSize(EXPECTED_HEADER_KEYS.size()));

        // and - the heapUsed column holds a real, positive numeric heap value
        long heapUsed = Long.parseLong(dataColumns[HEAP_USED_COLUMN_INDEX].trim());
        assertThat("heapUsed should be a positive number of bytes", heapUsed, greaterThan(0L));
    }

    @Test
    public void shouldNotWriteAnyFileWhenOutputMemoryUsageCsvDisabled() {
        // given
        Configuration configuration = configuration()
            .outputMemoryUsageCsv(false)
            .memoryUsageCsvDirectory(temporaryFolder.getRoot().getAbsolutePath());

        // when
        new MemoryMonitoring(configuration, null, null);

        // then - (d) nothing was written to the CSV directory
        File[] files = temporaryFolder.getRoot().listFiles();
        assertThat(files, is(new File[0]));
    }

    private File singleCsvFile() {
        File[] files = temporaryFolder.getRoot().listFiles((dir, name) -> name.endsWith(".csv"));
        assertThat("expected exactly one CSV file in the output directory", files, arrayWithSize(1));
        return files[0];
    }

    private static List<String> readLines(File csvFile) throws Exception {
        return Files.readAllLines(csvFile.toPath(), StandardCharsets.UTF_8);
    }
}
