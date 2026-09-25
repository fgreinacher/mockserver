package org.mockserver.memory;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.MockServerEventLog;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.RequestMatchers;
import org.mockserver.mock.listeners.MockServerLogListener;
import org.mockserver.mock.listeners.MockServerMatcherListener;
import org.mockserver.mock.listeners.MockServerMatcherNotifier;
import org.slf4j.event.Level;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.mock.HttpState.getPort;

public class MemoryMonitoring implements MockServerLogListener, MockServerMatcherListener {

    private static final AtomicInteger memoryUpdateFrequency = new AtomicInteger(0);
    private static final AtomicInteger currentLogEntriesCount = new AtomicInteger(0);
    private static final AtomicInteger currentExpectationsCount = new AtomicInteger(0);
    private static final List<MemoryPoolMXBean> memoryPoolMXBeans = ManagementFactory.getMemoryPoolMXBeans();
    private static final MockServerLogger MOCK_SERVER_LOGGER = new MockServerLogger(MemoryMonitoring.class);
    private final Configuration configuration;
    private final File csvFile;
    // Held so stop() can unregister the listeners registered in the constructor, preventing a
    // listener leak when the owning HttpState is stopped/recreated (e.g. embedded/test reuse).
    private final MockServerEventLog mockServerLog;
    private final RequestMatchers requestMatchers;

    public MemoryMonitoring(Configuration configuration, MockServerEventLog mockServerLog, RequestMatchers requestMatchers) {
        if (configuration.outputMemoryUsageCsv()) {
            this.configuration = configuration;
            this.csvFile = new File(configuration.memoryUsageCsvDirectory(), "memoryUsage_" + new SimpleDateFormat("yyyy-MM-dd").format(new Date()) + ".csv");
            this.mockServerLog = mockServerLog;
            this.requestMatchers = requestMatchers;
            if (!csvFile.exists()) {
                String line = buildStatistics().stream().map(Pair::getKey).collect(Collectors.joining(","));
                writeLineToCsv(line);
            }
            if (mockServerLog != null) {
                mockServerLog.registerListener(this);
            }
            if (requestMatchers != null) {
                requestMatchers.registerListener(this);
            }
        } else {
            this.configuration = null;
            this.csvFile = null;
            this.mockServerLog = null;
            this.requestMatchers = null;
        }
    }

    /**
     * Unregister the listeners registered in the constructor so this instance does not leak as a
     * permanent listener on the event log / request matchers when its owning {@link org.mockserver.mock.HttpState}
     * is stopped. No-op when CSV memory monitoring is disabled (no listeners were registered).
     */
    public void stop() {
        if (mockServerLog != null) {
            mockServerLog.unregisterListener(this);
        }
        if (requestMatchers != null) {
            requestMatchers.unregisterListener(this);
        }
    }

    public static Summary getJVMMemory(MemoryType heap) {
        return new Summary(memoryPoolMXBeans.stream().filter(bean -> bean.getType() == heap).collect(Collectors.toList()));
    }

    public void logMemoryMetrics() {
        if (configuration.outputMemoryUsageCsv()) {
            String line = buildStatistics().stream().map(Pair::getValue).map(String::valueOf).collect(Collectors.joining(","));
            writeLineToCsv(line);
        }
    }

    private void writeLineToCsv(String line) {
        try (FileOutputStream rawFileOutputStream = new FileOutputStream(csvFile, true)) {
            rawFileOutputStream.write((line + NEW_LINE).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            if (MOCK_SERVER_LOGGER.isEnabledForInstance(Level.WARN)) {
                MOCK_SERVER_LOGGER.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("exception writing memory usage statistics to CSV file [" + csvFile + "]")
                        .setThrowable(e)
                );
            }
        }
    }

    private List<ImmutablePair<String, Object>> buildStatistics() {
        Summary heap = getJVMMemory(MemoryType.HEAP);
        Summary nonHeap = getJVMMemory(MemoryType.NON_HEAP);
        List<ImmutablePair<String, Object>> memoryStatistics = new ArrayList<>();
        memoryStatistics.add(ImmutablePair.of("mockServerPort", getPort()));
        memoryStatistics.add(ImmutablePair.of("eventLogSize", currentLogEntriesCount.get()));
        memoryStatistics.add(ImmutablePair.of("maxLogEntries", configuration.maxLogEntries()));
        memoryStatistics.add(ImmutablePair.of("expectationsSize", currentExpectationsCount.get()));
        memoryStatistics.add(ImmutablePair.of("maxExpectations", configuration.maxExpectations()));
        memoryStatistics.add(ImmutablePair.of("heapInitialAllocation", heap.getNet().getInit()));
        memoryStatistics.add(ImmutablePair.of("heapUsed", heap.getNet().getUsed()));
        memoryStatistics.add(ImmutablePair.of("heapCommitted", heap.getNet().getCommitted()));
        memoryStatistics.add(ImmutablePair.of("heapMaxAllowed", heap.getNet().getMax()));
        memoryStatistics.add(ImmutablePair.of("nonHeapInitialAllocation", nonHeap.getNet().getInit()));
        memoryStatistics.add(ImmutablePair.of("nonHeapUsed", nonHeap.getNet().getUsed()));
        memoryStatistics.add(ImmutablePair.of("nonHeapCommitted", nonHeap.getNet().getCommitted()));
        memoryStatistics.add(ImmutablePair.of("nonHeapMaxAllowed", nonHeap.getNet().getMax()));
        return memoryStatistics;
    }

    @Override
    public void updated(MockServerEventLog mockServerLog) {
        currentLogEntriesCount.set(mockServerLog.size());
        if (shouldLogMetrics()) {
            logMemoryMetrics();
        }
    }

    @Override
    public void updated(RequestMatchers requestMatchers, MockServerMatcherNotifier.Cause cause) {
        currentExpectationsCount.set(requestMatchers.size());
        if (shouldLogMetrics()) {
            logMemoryMetrics();
        }
    }

    private boolean shouldLogMetrics() {
        return memoryUpdateFrequency.incrementAndGet() % 50 == 0;
    }

}
