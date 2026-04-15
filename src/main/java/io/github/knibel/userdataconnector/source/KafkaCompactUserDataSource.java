package io.github.knibel.userdataconnector.source;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.knibel.userdataconnector.UserDataConnectorProperties.KafkaCompactProperties;
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

import java.util.HashMap;
import java.util.Map;

/**
 * Data source that keeps the in-memory store in sync by consuming a <em>compacted</em>
 * Kafka topic.
 *
 * <p>Message format:
 * <ul>
 *   <li>Key   – userId (String)</li>
 *   <li>Value – JSON object whose fields are the identity attributes, e.g.
 *               {@code {"email":"alice@example.com","role":"admin"}}.
 *               A {@code null} value is treated as a tombstone and removes the user.</li>
 * </ul>
 *
 * <p>The consumer starts from the earliest available offset so that on startup the full
 * snapshot is replayed from the compacted topic.
 */
public class KafkaCompactUserDataSource implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(KafkaCompactUserDataSource.class);

    private final InMemoryUserDataStore store;
    private final KafkaCompactProperties config;
    private final ObjectMapper objectMapper;

    private KafkaMessageListenerContainer<String, String> container;
    private volatile boolean running = false;

    public KafkaCompactUserDataSource(InMemoryUserDataStore store,
                                      KafkaCompactProperties config,
                                      ObjectMapper objectMapper) {
        this.store = store;
        this.config = config;
        this.objectMapper = objectMapper;
    }

    @Override
    public void start() {
        if (config.getBootstrapServers() == null || config.getBootstrapServers().isBlank()) {
            throw new IllegalStateException(
                    "user-data-connector.kafka-compact.bootstrap-servers must be configured");
        }

        Map<String, Object> consumerProps = new HashMap<>();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, config.getBootstrapServers());
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, config.getGroupId());
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);

        DefaultKafkaConsumerFactory<String, String> consumerFactory =
                new DefaultKafkaConsumerFactory<>(consumerProps);

        ContainerProperties containerProps = new ContainerProperties(config.getTopic());
        containerProps.setMessageListener((MessageListener<String, String>) this::processRecord);

        container = new KafkaMessageListenerContainer<>(consumerFactory, containerProps);
        container.start();
        running = true;

        log.info("KafkaCompactUserDataSource started – consuming topic '{}'", config.getTopic());
    }

    @Override
    public void stop() {
        if (container != null) {
            container.stop();
        }
        running = false;
        log.info("KafkaCompactUserDataSource stopped");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    // ---- private helpers ----

    private void processRecord(ConsumerRecord<String, String> record) {
        String userId = record.key();
        if (userId == null) {
            log.warn("Received Kafka record with null key on topic '{}', skipping", config.getTopic());
            return;
        }

        if (record.value() == null) {
            // Tombstone message – delete the user
            store.delete(userId);
            log.debug("Deleted user '{}' via tombstone on topic '{}'", userId, config.getTopic());
        } else {
            try {
                Map<String, String> attributes = objectMapper.readValue(
                        record.value(), new TypeReference<>() {});
                store.upsert(new UserIdentityData(userId, attributes));
                log.debug("Upserted user '{}' from topic '{}'", userId, config.getTopic());
            } catch (Exception e) {
                log.error("Failed to parse Kafka record for userId '{}': {}", userId, record.value(), e);
            }
        }
    }
}
