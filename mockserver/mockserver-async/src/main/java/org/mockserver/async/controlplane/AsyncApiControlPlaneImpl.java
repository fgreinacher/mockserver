package org.mockserver.async.controlplane;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mockserver.async.AsyncApiControlPlane;
import org.mockserver.async.AsyncApiControlPlaneRegistry;
import org.mockserver.async.AsyncApiMockOrchestrator;
import org.mockserver.async.MessageExampleGenerator;
import org.mockserver.async.asyncapi.AsyncApiChannel;
import org.mockserver.async.asyncapi.AsyncApiMessage;
import org.mockserver.async.asyncapi.AsyncApiParser;
import org.mockserver.async.asyncapi.AsyncApiSpec;
import org.mockserver.async.publish.AmqpMessagePublisher;
import org.mockserver.async.publish.KafkaAvroMessagePublisher;
import org.mockserver.async.publish.KafkaMessagePublisher;
import org.mockserver.async.publish.MessagePublisher;
import org.mockserver.async.publish.Mqtt5MessagePublisher;
import org.mockserver.async.publish.MqttMessagePublisher;
import org.mockserver.async.serde.SchemaRegistryClient;
import org.mockserver.async.security.KafkaSecurity;
import org.mockserver.async.security.MqttSecurity;
import org.mockserver.async.subscribe.AmqpMessageSubscriber;
import org.mockserver.async.subscribe.KafkaAvroMessageSubscriber;
import org.mockserver.async.subscribe.KafkaMessageSubscriber;
import org.mockserver.async.subscribe.MessageSubscriber;
import org.mockserver.async.subscribe.Mqtt5MessageSubscriber;
import org.mockserver.async.subscribe.MqttMessageSubscriber;
import org.mockserver.async.subscribe.RecordedMessage;
import org.mockserver.async.validation.AsyncApiSchemaValidator;
import org.mockserver.configuration.ConfigurationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Implementation of {@link AsyncApiControlPlane} that lives in the mockserver-async
 * module and is registered into the core's {@link AsyncApiControlPlaneRegistry}
 * at server startup.
 * <p>
 * Handles:
 * <ul>
 *   <li>Loading AsyncAPI specs via the REST control-plane</li>
 *   <li>Creating publishers and subscribers for each channel</li>
 *   <li>Schema validation of generated and consumed messages</li>
 *   <li>Returning status including recorded messages</li>
 *   <li>Resetting all state on server reset</li>
 * </ul>
 */
public class AsyncApiControlPlaneImpl implements AsyncApiControlPlane {

    private static final Logger LOG = LoggerFactory.getLogger(AsyncApiControlPlaneImpl.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_LOG_PAYLOAD_LENGTH = 100;

    /**
     * Maximum number of validation issue records retained. Prevents unbounded
     * memory growth if many channels produce schema-invalid examples.
     */
    static final int MAX_VALIDATION_ISSUES = 1000;

    private final AsyncApiParser parser = new AsyncApiParser();
    private final MessageExampleGenerator generator = new MessageExampleGenerator();
    private final AsyncApiSchemaValidator schemaValidator = new AsyncApiSchemaValidator();

    // Active state
    private volatile AsyncApiSpec loadedSpec;
    private volatile BrokerConfig activeBrokerConfig;
    private final List<AsyncApiMockOrchestrator> activeOrchestrators = new CopyOnWriteArrayList<>();
    private final List<MessagePublisher> activePublishers = new CopyOnWriteArrayList<>();
    private final List<MessageSubscriber> activeSubscribers = new CopyOnWriteArrayList<>();
    private final List<SchemaValidationRecord> validationIssues = new CopyOnWriteArrayList<>();

    /**
     * Register this implementation into the core registry.
     * Call at server startup (e.g. from MockServerLifeCycle or Main).
     */
    public static void registerIfAvailable() {
        try {
            AsyncApiControlPlaneImpl impl = new AsyncApiControlPlaneImpl();
            AsyncApiControlPlaneRegistry.getInstance().register(impl);
            LOG.info("AsyncAPI control-plane registered");
        } catch (Exception e) {
            LOG.debug("AsyncAPI control-plane not available: {}", e.getMessage());
        }
    }

    @Override
    public synchronized JsonNode load(String requestBody) {
        // Reset any previous state
        resetInternal();

        try {
            // Parse the request body: either a plain spec or a wrapper
            String specContent;
            BrokerConfig brokerConfig;

            JsonNode bodyNode = tryParseJson(requestBody);
            if (bodyNode != null && bodyNode.has("spec")) {
                specContent = bodyNode.get("spec").isTextual()
                    ? bodyNode.get("spec").asText()
                    : MAPPER.writeValueAsString(bodyNode.get("spec"));
                brokerConfig = parseBrokerConfig(bodyNode.get("brokerConfig"));
            } else {
                // Plain spec
                specContent = requestBody;
                brokerConfig = BrokerConfig.defaultConfig();
            }

            AsyncApiSpec spec = parser.parse(specContent);
            this.loadedSpec = spec;
            this.activeBrokerConfig = brokerConfig;

            // Create publishers and subscribers for each channel
            for (AsyncApiChannel channel : spec.getChannels()) {
                // Validate generated examples against schema
                String example = generator.generateExample(channel);
                if (channel.getPayloadSchema() != null) {
                    AsyncApiSchemaValidator.ValidationResult result = schemaValidator.validate(example, channel.getPayloadSchema());
                    if (!result.isValid()) {
                        addValidationIssue(new SchemaValidationRecord(
                            channel.getName(), "generated_example", result.getErrors()));
                        LOG.warn("Generated example for channel '{}' does not conform to schema: {}",
                            channel.getName(), truncate(String.valueOf(result.getErrors())));
                    }
                }

                // Validate the first spec-provided example of each message against its schema
                validateFirstMessageExamples(channel);
            }

            // Create broker connections based on config
            createBrokerConnections(spec, brokerConfig);

            // Build response
            return buildLoadResponse(spec);

        } catch (Exception e) {
            // Clean up any partially-created brokers on failure
            resetInternal();
            throw new RuntimeException("Failed to load AsyncAPI spec: " + e.getMessage(), e);
        }
    }

    /**
     * Create publisher/subscriber connections. Extracted so partial-failure cleanup
     * is handled by the caller's catch block calling {@link #resetInternal()}.
     */
    private void createBrokerConnections(AsyncApiSpec spec, BrokerConfig brokerConfig) {
        int maxRecordedMessages = ConfigurationProperties.asyncRecordedMessageMaxEntries();

        if (brokerConfig.kafkaBootstrapServers != null) {
            boolean avro = "avro".equals(brokerConfig.kafkaValueFormat);
            SchemaRegistryClient registryClient = (avro && brokerConfig.kafkaSchemaRegistryUrl != null)
                ? new SchemaRegistryClient(brokerConfig.kafkaSchemaRegistryUrl) : null;

            MessagePublisher publisher = avro
                ? new KafkaAvroMessagePublisher(brokerConfig.kafkaBootstrapServers, brokerConfig.kafkaSecurity,
                    brokerConfig.avroSchema, registryClient, brokerConfig.avroSchemaId)
                : new KafkaMessagePublisher(brokerConfig.kafkaBootstrapServers, brokerConfig.kafkaSecurity);
            activePublishers.add(publisher);

            AsyncApiMockOrchestrator orchestrator = new AsyncApiMockOrchestrator(spec, publisher, generator);
            activeOrchestrators.add(orchestrator);

            // One-shot publish
            if (brokerConfig.publishOnLoad) {
                orchestrator.publishAll();
            }
            // Scheduled publish
            if (brokerConfig.publishIntervalMillis > 0) {
                orchestrator.startPublishing(brokerConfig.publishIntervalMillis);
            }

            // Create subscriber if consume is enabled
            if (brokerConfig.consume) {
                String groupId = brokerConfig.kafkaGroupId != null
                    ? brokerConfig.kafkaGroupId : "mockserver-async-consumer";
                MessageSubscriber subscriber = avro
                    ? new KafkaAvroMessageSubscriber(brokerConfig.kafkaBootstrapServers, groupId,
                        maxRecordedMessages, brokerConfig.kafkaSecurity, registryClient, brokerConfig.avroSchema)
                    : new KafkaMessageSubscriber(brokerConfig.kafkaBootstrapServers, groupId,
                        maxRecordedMessages, brokerConfig.kafkaSecurity);
                activeSubscribers.add(subscriber);
                for (AsyncApiChannel channel : spec.getChannels()) {
                    subscriber.subscribe(channel.getName());
                }
            }
        }

        if (brokerConfig.amqpUri != null) {
            MessagePublisher publisher = new AmqpMessagePublisher(brokerConfig.amqpUri, spec);
            activePublishers.add(publisher);

            AsyncApiMockOrchestrator orchestrator = new AsyncApiMockOrchestrator(spec, publisher, generator);
            activeOrchestrators.add(orchestrator);

            if (brokerConfig.publishOnLoad) {
                orchestrator.publishAll();
            }
            if (brokerConfig.publishIntervalMillis > 0) {
                orchestrator.startPublishing(brokerConfig.publishIntervalMillis);
            }

            // Create subscriber if consume is enabled
            if (brokerConfig.consume) {
                AmqpMessageSubscriber subscriber = new AmqpMessageSubscriber(
                    brokerConfig.amqpUri, spec, maxRecordedMessages);
                activeSubscribers.add(subscriber);
                for (AsyncApiChannel channel : spec.getChannels()) {
                    subscriber.subscribe(channel.getName());
                }
            }
        }

        if (brokerConfig.mqttBrokerUrl != null) {
            String pubClientId = brokerConfig.mqttClientId != null
                ? brokerConfig.mqttClientId + "-pub" : "mockserver-mqtt-pub";
            int qos = brokerConfig.mqttQos >= 0 ? brokerConfig.mqttQos : 1;
            boolean mqtt5 = brokerConfig.mqttProtocolVersion == 5;
            MessagePublisher publisher = mqtt5
                ? new Mqtt5MessagePublisher(brokerConfig.mqttBrokerUrl, pubClientId, qos, brokerConfig.mqttSecurity)
                : new MqttMessagePublisher(brokerConfig.mqttBrokerUrl, pubClientId, qos, brokerConfig.mqttSecurity);
            activePublishers.add(publisher);

            AsyncApiMockOrchestrator orchestrator = new AsyncApiMockOrchestrator(spec, publisher, generator);
            activeOrchestrators.add(orchestrator);

            if (brokerConfig.publishOnLoad) {
                orchestrator.publishAll();
            }
            if (brokerConfig.publishIntervalMillis > 0) {
                orchestrator.startPublishing(brokerConfig.publishIntervalMillis);
            }

            // Create subscriber if consume is enabled
            if (brokerConfig.consume) {
                String subClientId = brokerConfig.mqttClientId != null
                    ? brokerConfig.mqttClientId + "-sub" : "mockserver-mqtt-sub";
                MessageSubscriber subscriber = mqtt5
                    ? new Mqtt5MessageSubscriber(brokerConfig.mqttBrokerUrl, subClientId, qos,
                        maxRecordedMessages, brokerConfig.mqttSecurity)
                    : new MqttMessageSubscriber(brokerConfig.mqttBrokerUrl, subClientId, qos,
                        maxRecordedMessages, brokerConfig.mqttSecurity);
                activeSubscribers.add(subscriber);
                for (AsyncApiChannel channel : spec.getChannels()) {
                    subscriber.subscribe(channel.getName());
                }
            }
        }
    }

    @Override
    public synchronized JsonNode status() {
        ObjectNode result = MAPPER.createObjectNode();

        if (loadedSpec == null) {
            result.put("loaded", false);
            result.putArray("channels");
            result.put("publishers", 0);
            result.put("subscribers", 0);
            result.putArray("recordedMessages");
            return result;
        }

        result.put("loaded", true);
        result.put("specTitle", loadedSpec.getTitle());
        result.put("specVersion", loadedSpec.getAsyncApiVersion());

        ArrayNode channelsArray = result.putArray("channels");
        for (AsyncApiChannel channel : loadedSpec.getChannels()) {
            ObjectNode channelNode = MAPPER.createObjectNode();
            channelNode.put("name", channel.getName());
            channelNode.put("hasSchema", channel.getPayloadSchema() != null);
            channelNode.put("exampleCount", channel.getPayloadExamples().size());
            channelsArray.add(channelNode);
        }

        result.put("publishers", activePublishers.size());
        result.put("subscribers", activeSubscribers.size());

        // Recorded messages from subscribers
        ArrayNode recordedArray = result.putArray("recordedMessages");
        for (MessageSubscriber subscriber : activeSubscribers) {
            for (RecordedMessage msg : subscriber.getAllRecordedMessages()) {
                ObjectNode msgNode = MAPPER.createObjectNode();
                msgNode.put("channel", msg.getChannel());
                if (msg.getKey() != null) {
                    msgNode.put("key", msg.getKey());
                }
                msgNode.put("payload", msg.getPayload());
                if (!msg.getHeaders().isEmpty()) {
                    ObjectNode headersNode = msgNode.putObject("headers");
                    msg.getHeaders().forEach(headersNode::put);
                }
                msgNode.put("timestamp", msg.getTimestamp().toString());

                // Validate recorded message against schema
                AsyncApiChannel matchingChannel = findChannel(msg.getChannel());
                if (matchingChannel != null && matchingChannel.getPayloadSchema() != null) {
                    AsyncApiSchemaValidator.ValidationResult validationResult =
                        schemaValidator.validate(msg.getPayload(), matchingChannel.getPayloadSchema());
                    msgNode.put("schemaValid", validationResult.isValid());
                    if (!validationResult.isValid()) {
                        msgNode.put("schemaErrors", validationResult.getErrors());
                    }
                }

                recordedArray.add(msgNode);
            }
        }

        // Validation issues from example generation
        if (!validationIssues.isEmpty()) {
            ArrayNode issuesArray = result.putArray("validationIssues");
            for (SchemaValidationRecord issue : validationIssues) {
                ObjectNode issueNode = MAPPER.createObjectNode();
                issueNode.put("channel", issue.channel);
                issueNode.put("context", issue.context);
                issueNode.put("errors", issue.errors);
                issuesArray.add(issueNode);
            }
        }

        return result;
    }

    @Override
    public synchronized String verify(String verificationJson) {
        if (verificationJson == null || verificationJson.isBlank()) {
            throw new IllegalArgumentException("verification request body must not be empty");
        }

        JsonNode request;
        try {
            request = MAPPER.readTree(verificationJson);
        } catch (IOException e) {
            throw new IllegalArgumentException("invalid JSON in verification request: " + e.getMessage(), e);
        }

        if (request == null || !request.has("channel")) {
            throw new IllegalArgumentException("verification request must contain a 'channel' field");
        }

        String channel = request.get("channel").asText();
        String payloadSubstring = textOrNull(request, "payloadSubstring");
        String payloadJsonPath = textOrNull(request, "payloadJsonPath");
        String expectedValue = textOrNull(request, "expectedValue");

        // Parse count semantics — default is atLeast: 1
        int atLeast = -1;
        int atMost = -1;
        int exactly = -1;
        JsonNode countNode = request.get("count");
        if (countNode != null && countNode.isObject()) {
            if (countNode.has("atLeast")) {
                atLeast = countNode.get("atLeast").asInt();
            }
            if (countNode.has("atMost")) {
                atMost = countNode.get("atMost").asInt();
            }
            if (countNode.has("exactly")) {
                exactly = countNode.get("exactly").asInt();
            }
        }
        // Default: atLeast 1 when no count specified
        if (atLeast < 0 && atMost < 0 && exactly < 0) {
            atLeast = 1;
        }

        // Collect matching messages from all active subscribers
        int matchingCount = 0;
        for (MessageSubscriber subscriber : activeSubscribers) {
            for (RecordedMessage msg : subscriber.getRecordedMessages(channel)) {
                if (matchesPayloadCriteria(msg, payloadSubstring, payloadJsonPath, expectedValue)) {
                    matchingCount++;
                }
            }
        }

        // Check count constraints
        return checkCount(channel, matchingCount, atLeast, atMost, exactly,
            payloadSubstring, payloadJsonPath, expectedValue);
    }

    private boolean matchesPayloadCriteria(RecordedMessage msg, String payloadSubstring,
                                           String payloadJsonPath, String expectedValue) {
        String payload = msg.getPayload();
        if (payload == null) {
            payload = "";
        }

        // Substring match
        if (payloadSubstring != null && !payload.contains(payloadSubstring)) {
            return false;
        }

        // JSON path match (simple dot-notation extraction)
        if (payloadJsonPath != null && expectedValue != null) {
            String actualValue = extractJsonPath(payload, payloadJsonPath);
            if (!expectedValue.equals(actualValue)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Simple dot-notation JSON path extractor (e.g. "user.name" extracts from
     * {@code {"user":{"name":"Alice"}}}). Handles string, number, boolean, and null values.
     * Returns {@code null} if the path does not resolve.
     */
    private String extractJsonPath(String payload, String path) {
        try {
            JsonNode node = MAPPER.readTree(payload);
            for (String segment : path.split("\\.")) {
                if (node == null || !node.has(segment)) {
                    return null;
                }
                node = node.get(segment);
            }
            if (node == null || node.isNull()) {
                return null;
            }
            return node.isTextual() ? node.asText() : node.toString();
        } catch (IOException e) {
            return null;
        }
    }

    private String checkCount(String channel, int actual, int atLeast, int atMost, int exactly,
                              String payloadSubstring, String payloadJsonPath, String expectedValue) {
        StringBuilder criteria = new StringBuilder();
        criteria.append("channel '").append(channel).append("'");
        if (payloadSubstring != null) {
            criteria.append(" with payload containing '").append(payloadSubstring).append("'");
        }
        if (payloadJsonPath != null && expectedValue != null) {
            criteria.append(" with ").append(payloadJsonPath).append("='").append(expectedValue).append("'");
        }

        if (exactly >= 0) {
            if (actual != exactly) {
                return "expected exactly " + exactly + " message(s) matching " + criteria
                    + " but found " + actual;
            }
        }
        if (atLeast >= 0) {
            if (actual < atLeast) {
                return "expected at least " + atLeast + " message(s) matching " + criteria
                    + " but found " + actual;
            }
        }
        if (atMost >= 0) {
            if (actual > atMost) {
                return "expected at most " + atMost + " message(s) matching " + criteria
                    + " but found " + actual;
            }
        }
        return null; // verification passed
    }

    @Override
    public String generateHttpExpectations(String requestBody) {
        return new AsyncApiHttpExpectationGenerator().generateSerialized(requestBody);
    }

    @Override
    public synchronized void reset() {
        resetInternal();
        LOG.info("AsyncAPI control-plane reset");
    }

    private void resetInternal() {
        for (AsyncApiMockOrchestrator orchestrator : activeOrchestrators) {
            try {
                orchestrator.stop();
            } catch (Exception e) {
                LOG.warn("Error stopping orchestrator: {}", e.getMessage());
            }
        }
        for (MessagePublisher publisher : activePublishers) {
            try {
                publisher.close();
            } catch (Exception e) {
                LOG.warn("Error closing publisher: {}", e.getMessage());
            }
        }
        for (MessageSubscriber subscriber : activeSubscribers) {
            try {
                subscriber.close();
            } catch (Exception e) {
                LOG.warn("Error closing subscriber: {}", e.getMessage());
            }
        }
        activeOrchestrators.clear();
        activePublishers.clear();
        activeSubscribers.clear();
        validationIssues.clear();
        loadedSpec = null;
        activeBrokerConfig = null;
    }

    /**
     * Package-private: add a subscriber for testing purposes only.
     * Allows unit tests to inject mock/stub subscribers without needing a real broker.
     */
    void addSubscriberForTesting(MessageSubscriber subscriber) {
        activeSubscribers.add(subscriber);
    }

    /**
     * Validate the first spec-provided payload example of each message in a channel
     * against that message's payload schema. Skips messages that have no schema or
     * no explicit examples. Issues are surfaced as warning logs and validation records
     * in the load response — matching the existing convention for generated-example
     * validation — without hard-failing the load.
     * <p>
     * Scope: first example per message only (catches malformed spec examples early).
     */
    private void validateFirstMessageExamples(AsyncApiChannel channel) {
        for (AsyncApiMessage message : channel.getMessages()) {
            JsonNode schema = message.getPayloadSchema();
            if (schema == null) {
                continue;
            }
            List<JsonNode> examples = message.getPayloadExamples();
            if (examples.isEmpty()) {
                continue;
            }
            // Validate the first example only
            JsonNode firstExample = examples.get(0);
            String examplePayload;
            try {
                examplePayload = MAPPER.writeValueAsString(firstExample);
            } catch (Exception e) {
                LOG.warn("Failed to serialize first message example for channel '{}' message '{}': {}",
                    channel.getName(), message.getName(), e.getMessage());
                continue;
            }
            AsyncApiSchemaValidator.ValidationResult result = schemaValidator.validate(examplePayload, schema);
            if (!result.isValid()) {
                String context = message.getName() != null
                    ? "first_message_example:" + message.getName()
                    : "first_message_example";
                addValidationIssue(new SchemaValidationRecord(
                    channel.getName(), context, result.getErrors()));
                LOG.warn("First example for channel '{}' message '{}' does not conform to schema: {}",
                    channel.getName(), message.getName(), truncate(String.valueOf(result.getErrors())));
            }
        }
    }

    /**
     * Add a validation issue, enforcing the bounded cap.
     */
    private void addValidationIssue(SchemaValidationRecord record) {
        // CopyOnWriteArrayList size check + add is not atomic, but for a cap this
        // is fine — at worst we overshoot by a small number under concurrency
        while (validationIssues.size() >= MAX_VALIDATION_ISSUES) {
            validationIssues.remove(0);
        }
        validationIssues.add(record);
    }

    private JsonNode buildLoadResponse(AsyncApiSpec spec) {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("loaded", true);
        result.put("specTitle", spec.getTitle());
        result.put("specVersion", spec.getAsyncApiVersion());
        result.put("channelCount", spec.getChannels().size());

        ArrayNode channelsArray = result.putArray("channels");
        for (AsyncApiChannel channel : spec.getChannels()) {
            ObjectNode channelNode = MAPPER.createObjectNode();
            channelNode.put("name", channel.getName());
            channelNode.put("hasSchema", channel.getPayloadSchema() != null);
            channelsArray.add(channelNode);
        }

        result.put("publishers", activePublishers.size());
        result.put("subscribers", activeSubscribers.size());

        if (!validationIssues.isEmpty()) {
            ArrayNode issuesArray = result.putArray("validationIssues");
            for (SchemaValidationRecord issue : validationIssues) {
                ObjectNode issueNode = MAPPER.createObjectNode();
                issueNode.put("channel", issue.channel);
                issueNode.put("context", issue.context);
                issueNode.put("errors", issue.errors);
                issuesArray.add(issueNode);
            }
        }

        return result;
    }

    private JsonNode tryParseJson(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (!trimmed.startsWith("{")) {
            return null;
        }
        try {
            return MAPPER.readTree(trimmed);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Parse the broker configuration from the request body JSON. Accessible
     * at package level for unit testing.
     */
    BrokerConfig parseBrokerConfig(JsonNode node) {
        BrokerConfig config = new BrokerConfig();
        if (node != null) {
            config.kafkaBootstrapServers = textOrNull(node, "kafkaBootstrapServers");
            config.mqttBrokerUrl = textOrNull(node, "mqttBrokerUrl");
            config.amqpUri = textOrNull(node, "amqpUri");
            config.mqttClientId = textOrNull(node, "mqttClientId");
            config.kafkaGroupId = textOrNull(node, "kafkaGroupId");
            config.publishOnLoad = boolOrDefault(node, "publishOnLoad", true);
            config.consume = boolOrDefault(node, "consume", false);
            config.publishIntervalMillis = longOrDefault(node, "publishIntervalMillis", 0);
            config.mqttQos = intOrDefault(node, "mqttQos", 1);
            config.mqttProtocolVersion = intOrDefault(node, "mqttProtocolVersion", 3);
            if (config.mqttProtocolVersion != 3 && config.mqttProtocolVersion != 5) {
                throw new IllegalArgumentException("Unsupported 'mqttProtocolVersion' value "
                    + config.mqttProtocolVersion + " — allowed values are 3 or 5");
            }
            config.kafkaSecurity = parseKafkaSecurity(node.get("kafkaSecurity"));
            config.mqttSecurity = parseMqttSecurity(node.get("mqttSecurity"));
            String valueFormat = textOrNull(node, "kafkaValueFormat");
            if (valueFormat != null) {
                String normalisedFormat = valueFormat.toLowerCase();
                if ("protobuf".equals(normalisedFormat)) {
                    throw new IllegalArgumentException("Unsupported 'kafkaValueFormat' value '"
                        + valueFormat + "' — protobuf is not yet supported; allowed values are json or avro");
                }
                if (!"json".equals(normalisedFormat) && !"avro".equals(normalisedFormat)) {
                    throw new IllegalArgumentException("Unsupported 'kafkaValueFormat' value '"
                        + valueFormat + "' — allowed values are json or avro");
                }
                config.kafkaValueFormat = normalisedFormat;
            }
            config.kafkaSchemaRegistryUrl = textOrNull(node, "kafkaSchemaRegistryUrl");
            config.avroSchema = avroSchemaOrNull(node.get("avroSchema"));
            config.avroSchemaId = intOrDefault(node, "avroSchemaId", 1);
        }
        // Fall back to ConfigurationProperties defaults when request values are absent
        if (config.kafkaBootstrapServers == null) {
            String configDefault = ConfigurationProperties.asyncKafkaBootstrapServers();
            if (configDefault != null && !configDefault.isEmpty()) {
                config.kafkaBootstrapServers = configDefault;
            }
        }
        if (config.mqttBrokerUrl == null) {
            String configDefault = ConfigurationProperties.asyncMqttBrokerUrl();
            if (configDefault != null && !configDefault.isEmpty()) {
                config.mqttBrokerUrl = configDefault;
            }
        }
        if (config.amqpUri == null) {
            String configDefault = ConfigurationProperties.asyncAmqpUri();
            if (configDefault != null && !configDefault.isEmpty()) {
                config.amqpUri = configDefault;
            }
        }
        return config;
    }

    private KafkaSecurity parseKafkaSecurity(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        KafkaSecurity.Builder builder = KafkaSecurity.builder();
        String securityProtocol = textOrNull(node, "securityProtocol");
        if (securityProtocol != null) {
            builder.securityProtocol(securityProtocol);
        }
        String saslMechanism = textOrNull(node, "saslMechanism");
        if (saslMechanism != null) {
            builder.saslMechanism(saslMechanism);
        }
        String saslJaasConfig = textOrNull(node, "saslJaasConfig");
        if (saslJaasConfig != null) {
            builder.saslJaasConfig(saslJaasConfig);
        }
        String sslTruststoreLocation = textOrNull(node, "sslTruststoreLocation");
        if (sslTruststoreLocation != null) {
            builder.sslTruststoreLocation(sslTruststoreLocation);
        }
        String sslTruststorePassword = textOrNull(node, "sslTruststorePassword");
        if (sslTruststorePassword != null) {
            builder.sslTruststorePassword(sslTruststorePassword);
        }
        String sslKeystoreLocation = textOrNull(node, "sslKeystoreLocation");
        if (sslKeystoreLocation != null) {
            builder.sslKeystoreLocation(sslKeystoreLocation);
        }
        String sslKeystorePassword = textOrNull(node, "sslKeystorePassword");
        if (sslKeystorePassword != null) {
            builder.sslKeystorePassword(sslKeystorePassword);
        }
        String sslKeyPassword = textOrNull(node, "sslKeyPassword");
        if (sslKeyPassword != null) {
            builder.sslKeyPassword(sslKeyPassword);
        }
        KafkaSecurity result = builder.build();
        return result.isEmpty() ? null : result;
    }

    private MqttSecurity parseMqttSecurity(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        MqttSecurity.Builder builder = MqttSecurity.builder();
        String username = textOrNull(node, "username");
        if (username != null) {
            builder.username(username);
        }
        String password = textOrNull(node, "password");
        if (password != null) {
            builder.password(password);
        }
        JsonNode sslPropsNode = node.get("sslProperties");
        if (sslPropsNode != null && sslPropsNode.isObject()) {
            Map<String, String> sslProps = new LinkedHashMap<>();
            sslPropsNode.fields().forEachRemaining(entry -> {
                if (entry.getValue().isTextual()) {
                    sslProps.put(entry.getKey(), entry.getValue().asText());
                }
            });
            if (!sslProps.isEmpty()) {
                builder.sslProperties(sslProps);
            }
        }
        MqttSecurity result = builder.build();
        return result.isEmpty() ? null : result;
    }

    private AsyncApiChannel findChannel(String name) {
        if (loadedSpec == null) {
            return null;
        }
        for (AsyncApiChannel channel : loadedSpec.getChannels()) {
            if (channel.getName().equals(name)) {
                return channel;
            }
        }
        return null;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return (child != null && child.isTextual()) ? child.asText() : null;
    }

    /**
     * An Avro schema may be supplied either as a JSON string or as an inline JSON
     * object. Return the Avro schema JSON text in either case, or null when absent.
     */
    private static String avroSchemaOrNull(JsonNode child) {
        if (child == null || child.isNull()) {
            return null;
        }
        if (child.isTextual()) {
            String text = child.asText();
            return text.isBlank() ? null : text;
        }
        if (child.isObject()) {
            try {
                return MAPPER.writeValueAsString(child);
            } catch (Exception e) {
                LOG.warn("Failed to serialize inline avroSchema object: {}", e.getMessage());
                return null;
            }
        }
        return null;
    }

    private static boolean boolOrDefault(JsonNode node, String field, boolean defaultValue) {
        JsonNode child = node.get(field);
        return (child != null && child.isBoolean()) ? child.asBoolean() : defaultValue;
    }

    private static long longOrDefault(JsonNode node, String field, long defaultValue) {
        JsonNode child = node.get(field);
        return (child != null && child.isNumber()) ? child.asLong() : defaultValue;
    }

    private static int intOrDefault(JsonNode node, String field, int defaultValue) {
        JsonNode child = node.get(field);
        return (child != null && child.isNumber()) ? child.asInt() : defaultValue;
    }

    private static String truncate(String value) {
        if (value == null) {
            return "null";
        }
        return value.length() <= MAX_LOG_PAYLOAD_LENGTH
            ? value
            : value.substring(0, MAX_LOG_PAYLOAD_LENGTH) + "...(" + value.length() + " chars)";
    }

    /**
     * Broker connection configuration, parsed from the optional {@code brokerConfig}
     * field in the PUT request body.
     */
    static class BrokerConfig {
        String kafkaBootstrapServers;
        String kafkaGroupId;
        String mqttBrokerUrl;
        String mqttClientId;
        String amqpUri;
        boolean publishOnLoad = true;
        boolean consume = false;
        long publishIntervalMillis = 0;
        int mqttQos = 1;
        int mqttProtocolVersion = 3;
        KafkaSecurity kafkaSecurity;
        MqttSecurity mqttSecurity;
        // Kafka value serialization: "json" (default) or "avro" (Confluent wire format)
        String kafkaValueFormat = "json";
        // Confluent Schema Registry URL; when present, schema ids are registered/resolved against it
        String kafkaSchemaRegistryUrl;
        // Inline Avro schema JSON (registry-less mode, or the schema to register on publish)
        String avroSchema;
        // Fixed Avro schema id used to frame published messages in registry-less mode
        int avroSchemaId = 1;

        static BrokerConfig defaultConfig() {
            return new BrokerConfig();
        }
    }

    /**
     * Record of a schema validation issue for reporting.
     */
    static class SchemaValidationRecord {
        final String channel;
        final String context;
        final String errors;

        SchemaValidationRecord(String channel, String context, String errors) {
            this.channel = channel;
            this.context = context;
            this.errors = errors;
        }
    }
}
