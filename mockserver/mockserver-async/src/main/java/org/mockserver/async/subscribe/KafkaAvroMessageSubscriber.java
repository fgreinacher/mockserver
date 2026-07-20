package org.mockserver.async.subscribe;

import org.apache.avro.Schema;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.mockserver.async.security.KafkaSecurity;
import org.mockserver.async.security.KafkaSecurityProperties;
import org.mockserver.async.serde.AvroPayloadCodec;
import org.mockserver.async.serde.ConfluentWireFormat;
import org.mockserver.async.serde.SchemaRegistryClient;
import org.mockserver.metrics.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A {@link MessageSubscriber} that consumes <b>Confluent wire-format Avro</b>
 * messages from Kafka and records them as JSON — the consume/verify counterpart of
 * {@link org.mockserver.async.publish.KafkaAvroMessagePublisher}.
 *
 * <p>For each consumed record the wire-format header ({@link ConfluentWireFormat}) is
 * stripped to recover the schema id and Avro binary, the schema is resolved (from the
 * {@link SchemaRegistryClient} by id, or the configured inline schema in registry-less
 * mode), and the Avro binary is decoded back to JSON ({@link AvroPayloadCodec}). The
 * recorded payload is therefore JSON, so the existing substring / JSON-path
 * verification works unchanged.
 *
 * <p>Mirrors {@link KafkaMessageSubscriber}'s poll-thread confinement: all
 * {@link KafkaConsumer} access happens on the single background poll thread.
 */
public class KafkaAvroMessageSubscriber implements MessageSubscriber {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaAvroMessageSubscriber.class);
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(100);
    private static final int MAX_LOG_PAYLOAD_LENGTH = 100;
    /** Default registry-less schema id, matching the control plane's {@code avroSchemaId} default. */
    static final int DEFAULT_SCHEMA_ID = 1;

    private final KafkaConsumer<String, byte[]> consumer;
    private final int maxRecordedMessages;
    private final SchemaRegistryClient registryClient;
    private final Schema inlineSchema;

    /**
     * In registry-less mode the inline schema describes exactly one writer schema id — the
     * one the matching publisher frames its messages with (see
     * {@link org.mockserver.async.publish.KafkaAvroMessagePublisher}). Avro binary carries no
     * field names or types, so decoding a message written with a <i>different</i> schema
     * against the inline schema yields silently wrong values rather than an error. Messages
     * carrying any other schema id are therefore left undecoded instead of mis-decoded.
     */
    private final int expectedSchemaId;
    private final ConcurrentMap<Integer, Schema> schemaCache = new ConcurrentHashMap<>();

    private final ConcurrentMap<String, BoundedMessageStore> recordedMessages = new ConcurrentHashMap<>();
    private final Set<String> desiredTopics = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<Runnable> pendingOps = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService pollExecutor;
    private volatile boolean failed = false;

    /**
     * @param bootstrapServers    comma-separated host:port pairs
     * @param groupId             the consumer group id
     * @param maxRecordedMessages max recorded messages per channel
     * @param security            Kafka security (may be null)
     * @param registryClient      Schema Registry client, or null for registry-less mode
     * @param inlineSchemaJson    inline Avro schema for registry-less decoding (may be null when a registry is used)
     */
    public KafkaAvroMessageSubscriber(String bootstrapServers, String groupId, int maxRecordedMessages,
                                      KafkaSecurity security, SchemaRegistryClient registryClient,
                                      String inlineSchemaJson) {
        this(bootstrapServers, groupId, maxRecordedMessages, security, registryClient,
            inlineSchemaJson, DEFAULT_SCHEMA_ID);
    }

    /**
     * @param bootstrapServers    comma-separated host:port pairs
     * @param groupId             the consumer group id
     * @param maxRecordedMessages max recorded messages per channel
     * @param security            Kafka security (may be null)
     * @param registryClient      Schema Registry client, or null for registry-less mode
     * @param inlineSchemaJson    inline Avro schema for registry-less decoding (may be null when a registry is used)
     * @param expectedSchemaId    the schema id the inline schema corresponds to; messages framed
     *                            with any other id are not decoded with it (registry-less mode only)
     */
    public KafkaAvroMessageSubscriber(String bootstrapServers, String groupId, int maxRecordedMessages,
                                      KafkaSecurity security, SchemaRegistryClient registryClient,
                                      String inlineSchemaJson, int expectedSchemaId) {
        this.consumer = new KafkaConsumer<>(buildConsumerProperties(bootstrapServers, groupId, security));
        this.maxRecordedMessages = maxRecordedMessages;
        this.registryClient = registryClient;
        this.expectedSchemaId = expectedSchemaId;
        this.inlineSchema = inlineSchemaJson != null ? AvroPayloadCodec.parseSchema(inlineSchemaJson) : null;
        this.pollExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "async-kafka-avro-subscriber");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Package-private constructor for injecting a mock consumer in tests.
     */
    KafkaAvroMessageSubscriber(KafkaConsumer<String, byte[]> consumer, int maxRecordedMessages,
                               SchemaRegistryClient registryClient, String inlineSchemaJson) {
        this(consumer, maxRecordedMessages, registryClient, inlineSchemaJson, DEFAULT_SCHEMA_ID);
    }

    /**
     * Package-private constructor for injecting a mock consumer with an explicit
     * registry-less schema id in tests.
     */
    KafkaAvroMessageSubscriber(KafkaConsumer<String, byte[]> consumer, int maxRecordedMessages,
                               SchemaRegistryClient registryClient, String inlineSchemaJson,
                               int expectedSchemaId) {
        this.consumer = consumer;
        this.maxRecordedMessages = maxRecordedMessages;
        this.registryClient = registryClient;
        this.expectedSchemaId = expectedSchemaId;
        this.inlineSchema = inlineSchemaJson != null ? AvroPayloadCodec.parseSchema(inlineSchemaJson) : null;
        this.pollExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "async-kafka-avro-subscriber-test");
            t.setDaemon(true);
            return t;
        });
    }

    static Properties buildConsumerProperties(String bootstrapServers, String groupId, KafkaSecurity security) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        KafkaSecurityProperties.applySecurity(props, security);
        return props;
    }

    @Override
    public void subscribe(String channel) {
        desiredTopics.add(channel);
        recordedMessages.putIfAbsent(channel, new BoundedMessageStore(maxRecordedMessages));
        pendingOps.add(() -> consumer.subscribe(new ArrayList<>(desiredTopics)));
        ensurePollLoopRunning();
        LOG.info("Subscribed to Kafka (Avro) topic '{}'", channel);
    }

    @Override
    public void unsubscribe(String channel) {
        desiredTopics.remove(channel);
        if (desiredTopics.isEmpty()) {
            pendingOps.add(consumer::unsubscribe);
        } else {
            pendingOps.add(() -> consumer.subscribe(new ArrayList<>(desiredTopics)));
        }
        LOG.info("Unsubscribed from Kafka (Avro) topic '{}'", channel);
    }

    @Override
    public List<RecordedMessage> getRecordedMessages(String channel) {
        BoundedMessageStore store = recordedMessages.get(channel);
        return store != null ? Collections.unmodifiableList(store.snapshot()) : Collections.emptyList();
    }

    @Override
    public List<RecordedMessage> getAllRecordedMessages() {
        List<RecordedMessage> all = new ArrayList<>();
        recordedMessages.values().forEach(store -> all.addAll(store.snapshot()));
        all.sort(Comparator.comparing(RecordedMessage::getTimestamp));
        return Collections.unmodifiableList(all);
    }

    @Override
    public void close() {
        running.set(false);
        consumer.wakeup();
        pollExecutor.shutdown();
        try {
            if (!pollExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                pollExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            pollExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        recordedMessages.clear();
        desiredTopics.clear();
        pendingOps.clear();
    }

    public boolean isHealthy() {
        return !failed;
    }

    private void ensurePollLoopRunning() {
        if (running.compareAndSet(false, true)) {
            pollExecutor.submit(this::pollLoop);
        }
    }

    private void pollLoop() {
        try {
            while (running.get()) {
                Runnable op;
                while ((op = pendingOps.poll()) != null) {
                    op.run();
                }
                ConsumerRecords<String, byte[]> records = consumer.poll(POLL_TIMEOUT);
                for (ConsumerRecord<String, byte[]> record : records) {
                    recordDecoded(record);
                }
            }
        } catch (WakeupException e) {
            if (running.get()) {
                LOG.error("Unexpected WakeupException in Kafka Avro poll loop", e);
            }
        } catch (Exception e) {
            if (running.get()) {
                failed = true;
                LOG.error("Kafka Avro poll loop terminated unexpectedly; subscriber is no longer healthy: {}",
                    e.getMessage(), e);
            }
        } finally {
            try {
                consumer.close(Duration.ofSeconds(5));
            } catch (Exception e) {
                LOG.warn("Error closing Kafka Avro consumer: {}", e.getMessage());
            }
        }
    }

    private void recordDecoded(ConsumerRecord<String, byte[]> record) {
        String jsonPayload = decodeToJson(record.value());
        Map<String, String> headers = new LinkedHashMap<>();
        for (Header header : record.headers()) {
            headers.put(header.key(),
                header.value() != null ? new String(header.value(), StandardCharsets.UTF_8) : null);
        }
        RecordedMessage msg = new RecordedMessage(record.topic(), record.key(), jsonPayload, headers);
        recordedMessages.computeIfAbsent(record.topic(),
            k -> new BoundedMessageStore(maxRecordedMessages)).add(msg);
        Metrics.incrementAsyncMessageConsumed(record.topic());
        if (LOG.isDebugEnabled()) {
            LOG.debug("Recorded Avro message from Kafka topic '{}': key={}, value={}",
                record.topic(), record.key(), truncate(jsonPayload));
        }
    }

    /**
     * Decode a consumed value: strip the Confluent wire-format header, resolve the
     * schema, and convert the Avro binary to JSON. Falls back to the raw UTF-8 string
     * when the bytes are not wire-format-framed (so non-Avro messages are still
     * recorded rather than dropped).
     */
    String decodeToJson(byte[] value) {
        if (value == null) {
            return null;
        }
        if (!ConfluentWireFormat.isWireFormat(value)) {
            return new String(value, StandardCharsets.UTF_8);
        }
        try {
            ConfluentWireFormat.Decoded decoded = ConfluentWireFormat.decode(value);
            Schema schema = resolveSchema(decoded.getSchemaId());
            if (schema == null) {
                LOG.warn("No schema available for schema id {} (registry-less mode requires an inline schema); "
                    + "recording raw bytes as string", decoded.getSchemaId());
                return new String(value, StandardCharsets.UTF_8);
            }
            return AvroPayloadCodec.avroToJson(schema, decoded.getPayload());
        } catch (Exception e) {
            LOG.warn("Failed to Avro-decode consumed message; recording raw bytes as string: {}", e.getMessage());
            return new String(value, StandardCharsets.UTF_8);
        }
    }

    private Schema resolveSchema(int schemaId) {
        if (registryClient == null) {
            // registry-less: the inline schema describes only its own schema id. Decoding a
            // different writer schema against it would produce wrong values with no error.
            if (schemaId != expectedSchemaId) {
                LOG.warn("Consumed message carries Avro schema id {} but the configured inline schema "
                    + "is id {}; not decoding with a mismatched schema (configure a schema registry, "
                    + "or set avroSchemaId to match the producer)", schemaId, expectedSchemaId);
                return null;
            }
            return inlineSchema;
        }
        return schemaCache.computeIfAbsent(schemaId,
            id -> AvroPayloadCodec.parseSchema(registryClient.getSchemaById(id)));
    }

    private static String truncate(String value) {
        if (value == null) {
            return "null";
        }
        return value.length() <= MAX_LOG_PAYLOAD_LENGTH
            ? value
            : value.substring(0, MAX_LOG_PAYLOAD_LENGTH) + "...(" + value.length() + " chars)";
    }
}
