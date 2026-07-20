package org.mockserver.async;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.async.asyncapi.AsyncApiChannel;
import org.mockserver.async.asyncapi.AsyncApiMessage;
import org.mockserver.async.asyncapi.AsyncApiSpec;
import org.mockserver.async.publish.MessagePublisher;
import org.mockserver.async.publish.PublishOptions;
import org.mockserver.metrics.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Orchestrates publishing of example messages derived from an AsyncAPI spec
 * to a message broker via a {@link MessagePublisher}.
 * <p>
 * Supports one-shot publishing ({@link #publishAll()}) and scheduled
 * periodic publishing ({@link #startPublishing(long)} / {@link #stop()}).
 * <p>
 * When a message defines a {@code correlationId.location}, the orchestrator
 * generates a unique correlation ID at publish time and injects it at the
 * specified location (header or payload JSON Pointer).
 */
public class AsyncApiMockOrchestrator {

    private static final Logger LOG = LoggerFactory.getLogger(AsyncApiMockOrchestrator.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String HEADER_LOCATION_PREFIX = "$message.header#/";
    private static final String PAYLOAD_LOCATION_PREFIX = "$message.payload#";

    private final AsyncApiSpec spec;
    private final MessagePublisher publisher;
    private final MessageExampleGenerator generator;
    private volatile ScheduledExecutorService scheduler;

    /** Failure message from the most recent scheduled publish cycle; null when the last one succeeded. */
    private volatile String lastPublishFailure;
    private final Supplier<String> correlationIdSupplier;

    public AsyncApiMockOrchestrator(AsyncApiSpec spec, MessagePublisher publisher) {
        this(spec, publisher, new MessageExampleGenerator());
    }

    /**
     * Constructor for use with a custom generator (used by the control-plane implementation
     * and tests).
     */
    public AsyncApiMockOrchestrator(AsyncApiSpec spec, MessagePublisher publisher, MessageExampleGenerator generator) {
        this(spec, publisher, generator, () -> UUID.randomUUID().toString());
    }

    /**
     * Full constructor with injectable correlation-ID supplier (used by tests to pin the ID).
     */
    AsyncApiMockOrchestrator(AsyncApiSpec spec, MessagePublisher publisher,
                             MessageExampleGenerator generator, Supplier<String> correlationIdSupplier) {
        this.spec = spec;
        this.publisher = publisher;
        this.generator = generator;
        this.correlationIdSupplier = correlationIdSupplier;
    }

    /**
     * Publish the generated example message for each message in each channel,
     * threading any AsyncAPI bindings (MQTT qos/retain, Kafka key) and
     * correlation IDs as {@link PublishOptions}.
     * <p>
     * Multi-message channels (v3 multiple {@code messages}, v2 {@code oneOf})
     * result in one publish call per message. Single-message channels behave
     * identically to the previous single-publish behavior.
     */
    public void publishAll() {
        // channels are counted only after flush() confirms delivery, so a send the broker later
        // rejects does not inflate the published metric
        List<String> publishedChannels = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (AsyncApiChannel ch : spec.getChannels()) {
            List<AsyncApiMessage> messages = ch.getMessages();
            for (AsyncApiMessage msg : messages) {
                String payload = generator.generateExample(msg);
                if (payload == null || payload.isBlank()) {
                    continue;
                }

                Map<String, String> correlationHeaders = null;
                String correlationIdLocation = msg.getCorrelationIdLocation();
                if (correlationIdLocation != null) {
                    String correlationId = correlationIdSupplier.get();
                    if (correlationIdLocation.startsWith(HEADER_LOCATION_PREFIX)) {
                        String headerName = correlationIdLocation.substring(HEADER_LOCATION_PREFIX.length());
                        if (!headerName.isEmpty()) {
                            correlationHeaders = new LinkedHashMap<>();
                            correlationHeaders.put(headerName, correlationId);
                        }
                    } else if (correlationIdLocation.startsWith(PAYLOAD_LOCATION_PREFIX)) {
                        String pointerStr = correlationIdLocation.substring(PAYLOAD_LOCATION_PREFIX.length());
                        payload = injectIntoPayload(payload, pointerStr, correlationId);
                    } else {
                        LOG.debug("Unrecognised correlationId location prefix '{}'; skipping injection",
                            correlationIdLocation);
                    }
                }

                PublishOptions options = buildPublishOptions(ch, msg, correlationHeaders);
                LOG.debug("Publishing example to channel '{}': {}", ch.getName(), payload);
                // Contain per message: a publish can now fail (an AMQP message reaching no queue),
                // and without this the first failing channel would abort the whole cycle, so a
                // 10-channel spec whose first channel has no bound queue would publish nothing at
                // all — on any channel — rather than losing just the one.
                try {
                    publisher.publish(ch.getName(), payload, options);
                    publishedChannels.add(ch.getName());
                } catch (Exception e) {
                    failures.add(ch.getName() + ": " + describe(e));
                    LOG.warn("Failed to publish to channel '{}'; continuing with the remaining "
                        + "channels: {}", ch.getName(), describe(e));
                }
            }
        }

        if (!publishedChannels.isEmpty()) {
            // Block until the broker has acknowledged every send, so a caller that reports
            // "published" is not doing so before delivery is known (asynchronous on Kafka).
            try {
                publisher.flush();
                publishedChannels.forEach(Metrics::incrementAsyncMessagePublished);
            } catch (Exception e) {
                // Kafka's flush reports only the first failure and carries no per-message
                // attribution, so the successfully-delivered messages in this cycle cannot be
                // identified and none are counted. See docs/code/metrics.md.
                failures.add("delivery confirmation failed: " + describe(e));
            }
        }

        if (!failures.isEmpty()) {
            throw new RuntimeException("Publish cycle completed with "
                + failures.size() + " failure(s): " + String.join("; ", failures));
        }
    }

    /** Exception description that never renders a literal "null" for a message-less exception. */
    private static String describe(Exception e) {
        String message = e.getMessage();
        return (message != null && !message.isBlank()) ? message : e.getClass().getSimpleName();
    }

    /**
     * Inject a value into a JSON payload at the given JSON Pointer path.
     * Creates intermediate objects when needed. Returns the original payload
     * unchanged if it is not valid JSON or the pointer is empty.
     */
    private String injectIntoPayload(String payload, String pointerStr, String value) {
        if (pointerStr == null || pointerStr.isEmpty() || pointerStr.equals("/")) {
            LOG.debug("Empty or root-only JSON Pointer for correlation ID; skipping payload injection");
            return payload;
        }
        try {
            JsonNode root = MAPPER.readTree(payload);
            if (!(root instanceof ObjectNode)) {
                LOG.debug("Payload is not a JSON object; skipping correlation ID injection");
                return payload;
            }
            JsonPointer pointer = JsonPointer.compile(pointerStr);
            setValueAtPointer((ObjectNode) root, pointer, value);
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            LOG.debug("Failed to inject correlation ID into payload: {}", e.getMessage());
            return payload;
        }
    }

    /**
     * Set a text value at the given JSON Pointer path in an ObjectNode tree,
     * creating intermediate ObjectNode containers as needed.
     */
    private void setValueAtPointer(ObjectNode root, JsonPointer pointer, String value) {
        // Walk the pointer segments, creating intermediate objects
        JsonPointer head = pointer.head();
        String lastSegment = pointer.last().getMatchingProperty();

        if (head == null || head.matches()) {
            // Single-level pointer: set directly on root
            root.put(lastSegment, value);
            return;
        }

        // Multi-level pointer: navigate/create intermediate nodes
        ObjectNode current = root;
        // Collect all segments except the last
        JsonPointer remaining = pointer;
        java.util.List<String> segments = new java.util.ArrayList<>();
        while (remaining != null && !remaining.matches()) {
            segments.add(remaining.getMatchingProperty());
            remaining = remaining.tail();
        }

        // Navigate to the parent of the last segment, creating intermediates
        for (int i = 0; i < segments.size() - 1; i++) {
            String seg = segments.get(i);
            JsonNode child = current.get(seg);
            if (child instanceof ObjectNode) {
                current = (ObjectNode) child;
            } else {
                ObjectNode newNode = MAPPER.createObjectNode();
                current.set(seg, newNode);
                current = newNode;
            }
        }

        // Set the value at the final segment
        current.put(segments.get(segments.size() - 1), value);
    }

    /**
     * Build {@link PublishOptions} from per-message Kafka key, channel-level
     * MQTT qos/retain bindings, and optional correlation-ID headers.
     */
    private PublishOptions buildPublishOptions(AsyncApiChannel channel, AsyncApiMessage message,
                                               Map<String, String> correlationHeaders) {
        String kafkaKey = message.getKafkaKey();
        Integer mqttQos = channel.getMqttQos();
        Boolean mqttRetain = channel.getMqttRetain();
        boolean hasCorrelationHeaders = correlationHeaders != null && !correlationHeaders.isEmpty();
        if (kafkaKey == null && mqttQos == null && mqttRetain == null && !hasCorrelationHeaders) {
            return PublishOptions.none();
        }
        return new PublishOptions(kafkaKey, mqttQos, mqttRetain, correlationHeaders);
    }

    /**
     * Start periodic publishing at the given interval.
     *
     * @param intervalMillis the interval between publish cycles in milliseconds
     */
    public synchronized void startPublishing(long intervalMillis) {
        if (scheduler != null && !scheduler.isShutdown()) {
            LOG.warn("Scheduled publishing already running; stop() first");
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "async-mock-publisher");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::publishAllSurvivingFailure, 0, intervalMillis,
            TimeUnit.MILLISECONDS);
        LOG.info("Started scheduled publishing every {} ms", intervalMillis);
    }

    /**
     * Run one publish cycle, containing any failure so the schedule survives it.
     * <p>
     * {@link java.util.concurrent.ScheduledExecutorService#scheduleAtFixedRate} cancels the task
     * permanently if an execution throws, and the throwable is buried in a {@code ScheduledFuture}
     * nobody reads. Letting a publish failure escape would therefore stop periodic publishing for
     * good — silently, and with no recovery once the underlying cause (an AMQP queue not yet bound,
     * a Kafka broker briefly unavailable) has cleared. A failed cycle is logged and the next cycle
     * retries.
     */
    private void publishAllSurvivingFailure() {
        try {
            publishAll();
            lastPublishFailure = null;
        } catch (Exception e) {
            lastPublishFailure = e.getMessage();
            LOG.warn("Scheduled publish cycle failed; the schedule continues and the next cycle "
                + "will retry: {}", e.getMessage(), e);
        }
    }

    /**
     * The failure message from the most recent scheduled publish cycle, or null if the last cycle
     * succeeded. Exposed so a stalled or failing mock can be diagnosed without reading the log.
     */
    public String getLastPublishFailure() {
        return lastPublishFailure;
    }

    /**
     * Stop periodic publishing, waiting for an in-flight publish to finish.
     * <p>
     * Callers close the publishers immediately after this returns (see
     * {@code resetInternal()}), so returning while a scheduled {@code publishAll} is still running
     * would let that thread publish against a channel being closed underneath it. Shutting down
     * gracefully and then awaiting termination closes that window: {@code shutdown()} cancels the
     * periodic schedule (a {@code ScheduledThreadPoolExecutor} drops periodic tasks on shutdown by
     * default) while letting an already-running publish complete.
     * <p>
     * Matches the shutdown shape already used by {@code KafkaMessageSubscriber} and
     * {@code KafkaAvroMessageSubscriber}: same 5s budget, fall back to {@code shutdownNow()} if the
     * publish overruns it, and restore the interrupt flag rather than swallowing the interrupt.
     */
    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            scheduler = null;
            LOG.info("Stopped scheduled publishing");
        }
    }
}
