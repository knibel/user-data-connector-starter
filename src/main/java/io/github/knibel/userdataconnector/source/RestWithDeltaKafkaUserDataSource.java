package io.github.knibel.userdataconnector.source;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.knibel.userdataconnector.UserDataConnectorProperties.RestWithDeltaKafkaProperties;
import io.github.knibel.userdataconnector.api.UserIdentityData;
import io.github.knibel.userdataconnector.store.InMemoryUserDataStore;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Data source that combines a REST snapshot with an ongoing Kafka delta stream.
 *
 * <p><strong>Start-up behaviour:</strong>
 * <ol>
 *   <li>Performs a {@code GET} request to
 *       {@code <baseUrl><dataPath>} and populates the store with the returned
 *       snapshot.</li>
 *   <li>Subscribes to the configured <em>delta</em> Kafka topic to apply subsequent
 *       changes in real time.</li>
 * </ol>
 *
 * <p><strong>Expected REST response format</strong> – a JSON array of user records:
 * <pre>{@code
 * [
 *   { "userId": "alice", "attributes": { "email": "alice@example.com" } },
 *   { "userId": "bob",   "attributes": { "role":  "admin" } }
 * ]
 * }</pre>
 *
 * <p><strong>Kafka delta message format</strong> – identical to the compact-topic format:
 * key = userId, value = JSON attributes object (or {@code null} to delete).
 */
public class RestWithDeltaKafkaUserDataSource implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RestWithDeltaKafkaUserDataSource.class);

    private final InMemoryUserDataStore store;
    private final RestWithDeltaKafkaProperties config;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate;

    private KafkaMessageListenerContainer<String, String> container;
    private volatile boolean running = false;

    public RestWithDeltaKafkaUserDataSource(InMemoryUserDataStore store,
                                            RestWithDeltaKafkaProperties config,
                                            ObjectMapper objectMapper,
                                            RestTemplate restTemplate) {
        this.store = store;
        this.config = config;
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplate;
    }

    @Override
    public void start() {
        if (config.getBaseUrl() == null || config.getBaseUrl().isBlank()) {
            throw new IllegalStateException(
                    "user-data-connector.rest-with-delta-kafka.base-url must be configured");
        }
        if (config.getDeltaKafkaBootstrapServers() == null
                || config.getDeltaKafkaBootstrapServers().isBlank()) {
            throw new IllegalStateException(
                    "user-data-connector.rest-with-delta-kafka.delta-kafka-bootstrap-servers must be configured");
        }

        loadInitialSnapshot();
        startDeltaKafkaConsumer();
        running = true;
    }

    @Override
    public void stop() {
        if (container != null) {
            container.stop();
        }
        running = false;
        log.info("RestWithDeltaKafkaUserDataSource stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    // ---- private helpers ----

    private void loadInitialSnapshot() {
        String url = config.getBaseUrl() + config.getDataPath();
        log.info("Loading user identity snapshot from {}", url);
        try {
            String json = restTemplate.getForObject(url, String.class);
            if (json != null) {
                List<UserDataRecord> records = objectMapper.readValue(
                        json, new TypeReference<>() {});
                records.forEach(r -> store.upsert(new UserIdentityData(r.getUserId(), r.getAttributes())));
                log.info("Loaded {} user records from REST snapshot", records.size());
            }
        } catch (Exception e) {
            log.error("Failed to load initial user data snapshot from {}", url, e);
        }
    }

    private void startDeltaKafkaConsumer() {
        Map<String, Object> consumerProps = new HashMap<>();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, config.getDeltaKafkaBootstrapServers());
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, config.getDeltaKafkaGroupId());
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);

        DefaultKafkaConsumerFactory<String, String> consumerFactory =
                new DefaultKafkaConsumerFactory<>(consumerProps);

        ContainerProperties containerProps = new ContainerProperties(config.getDeltaKafkaTopic());
        containerProps.setMessageListener((MessageListener<String, String>) this::processDeltaRecord);

        container = new KafkaMessageListenerContainer<>(consumerFactory, containerProps);
        container.start();

        log.info("Delta Kafka consumer started – topic '{}'", config.getDeltaKafkaTopic());
    }

    private void processDeltaRecord(ConsumerRecord<String, String> record) {
        String userId = record.key();
        if (userId == null) {
            log.warn("Received delta Kafka record with null key, skipping");
            return;
        }

        if (record.value() == null) {
            store.delete(userId);
            log.debug("Deleted user '{}' via delta tombstone", userId);
        } else {
            try {
                Map<String, String> attributes = objectMapper.readValue(
                        record.value(), new TypeReference<>() {});
                store.upsert(new UserIdentityData(userId, attributes));
                log.debug("Upserted user '{}' from delta topic", userId);
            } catch (Exception e) {
                log.error("Failed to process delta record for userId '{}': {}", userId, record.value(), e);
            }
        }
    }

    // ---- DTO for REST snapshot deserialization ----

    static class UserDataRecord {
        private String userId;
        private Map<String, String> attributes = new HashMap<>();

        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }

        public Map<String, String> getAttributes() { return attributes; }
        public void setAttributes(Map<String, String> attributes) { this.attributes = attributes; }
    }
}
