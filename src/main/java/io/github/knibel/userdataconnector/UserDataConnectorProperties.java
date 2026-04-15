package io.github.knibel.userdataconnector;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the user-data-connector starter.
 *
 * <p>All properties are grouped under the {@code user-data-connector} prefix.
 *
 * <p>Example {@code application.yml}:
 * <pre>{@code
 * user-data-connector:
 *   source-type: KAFKA_COMPACT
 *   kafka-compact:
 *     bootstrap-servers: localhost:9092
 *     topic: user-identity-data
 *     group-id: my-app-user-data
 * }</pre>
 */
@ConfigurationProperties(prefix = "user-data-connector")
public class UserDataConnectorProperties {

    /**
     * Selects the data source that populates the in-memory user identity store.
     * <ul>
     *   <li>{@code KAFKA_COMPACT} – consume a compacted Kafka topic (key = userId,
     *       value = JSON attributes, null value = tombstone/delete).</li>
     *   <li>{@code REST_WITH_DELTA_KAFKA} – load a full snapshot from a REST endpoint on
     *       startup, then apply incremental updates from a "delta" Kafka topic.</li>
     *   <li>{@code WEBHOOK} – expose an HTTP endpoint that receives push notifications
     *       from the upstream identity service.</li>
     * </ul>
     */
    private SourceType sourceType;

    private final KafkaCompactProperties kafkaCompact = new KafkaCompactProperties();
    private final RestWithDeltaKafkaProperties restWithDeltaKafka = new RestWithDeltaKafkaProperties();
    private final WebhookProperties webhook = new WebhookProperties();

    // ---- Enum ----

    public enum SourceType {
        KAFKA_COMPACT,
        REST_WITH_DELTA_KAFKA,
        WEBHOOK
    }

    // ---- Getters / Setters ----

    public SourceType getSourceType() {
        return sourceType;
    }

    public void setSourceType(SourceType sourceType) {
        this.sourceType = sourceType;
    }

    public KafkaCompactProperties getKafkaCompact() {
        return kafkaCompact;
    }

    public RestWithDeltaKafkaProperties getRestWithDeltaKafka() {
        return restWithDeltaKafka;
    }

    public WebhookProperties getWebhook() {
        return webhook;
    }

    // ---- Nested configuration classes ----

    /**
     * Properties for the {@code KAFKA_COMPACT} source type.
     */
    public static class KafkaCompactProperties {

        /** Comma-separated list of Kafka broker addresses (e.g. {@code localhost:9092}). */
        private String bootstrapServers;

        /** Name of the compacted Kafka topic that carries user identity data. */
        private String topic = "user-identity-data";

        /** Kafka consumer group identifier. */
        private String groupId = "user-data-connector";

        public String getBootstrapServers() { return bootstrapServers; }
        public void setBootstrapServers(String bootstrapServers) { this.bootstrapServers = bootstrapServers; }

        public String getTopic() { return topic; }
        public void setTopic(String topic) { this.topic = topic; }

        public String getGroupId() { return groupId; }
        public void setGroupId(String groupId) { this.groupId = groupId; }
    }

    /**
     * Properties for the {@code REST_WITH_DELTA_KAFKA} source type.
     */
    public static class RestWithDeltaKafkaProperties {

        /**
         * Base URL of the REST endpoint that provides the full user identity snapshot
         * (e.g. {@code https://identity-service.example.com}).
         */
        private String baseUrl;

        /** Path appended to {@link #baseUrl} for the snapshot endpoint. */
        private String dataPath = "/api/user-data";

        /**
         * Comma-separated list of Kafka broker addresses for the delta / delete topic.
         * Falls back to {@code user-data-connector.kafka-compact.bootstrap-servers} when omitted.
         */
        private String deltaKafkaBootstrapServers;

        /** Name of the Kafka topic that carries incremental (delta/delete) events. */
        private String deltaKafkaTopic = "user-identity-data-delta";

        /** Kafka consumer group identifier for the delta consumer. */
        private String deltaKafkaGroupId = "user-data-connector-delta";

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        public String getDataPath() { return dataPath; }
        public void setDataPath(String dataPath) { this.dataPath = dataPath; }

        public String getDeltaKafkaBootstrapServers() { return deltaKafkaBootstrapServers; }
        public void setDeltaKafkaBootstrapServers(String s) { this.deltaKafkaBootstrapServers = s; }

        public String getDeltaKafkaTopic() { return deltaKafkaTopic; }
        public void setDeltaKafkaTopic(String deltaKafkaTopic) { this.deltaKafkaTopic = deltaKafkaTopic; }

        public String getDeltaKafkaGroupId() { return deltaKafkaGroupId; }
        public void setDeltaKafkaGroupId(String deltaKafkaGroupId) { this.deltaKafkaGroupId = deltaKafkaGroupId; }
    }

    /**
     * Properties for the {@code WEBHOOK} source type.
     */
    public static class WebhookProperties {

        /**
         * HTTP path on which the starter exposes its webhook endpoint.
         * Defaults to {@code /webhook/user-data}.
         */
        private String path = "/webhook/user-data";

        /**
         * Optional shared secret.  When set, every incoming webhook request must include
         * an {@code X-Webhook-Secret} header whose value equals this secret; requests
         * without a valid secret are rejected with {@code 401 Unauthorized}.
         */
        private String secret;

        /**
         * Optional URL of the REST snapshot endpoint.  When set, the starter performs an
         * initial load from this URL on startup (same format as
         * {@link RestWithDeltaKafkaProperties#getBaseUrl()} +
         * {@link RestWithDeltaKafkaProperties#getDataPath()}).
         * Subsequent updates arrive via webhook pushes.
         */
        private String initialDataUrl;

        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }

        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }

        public String getInitialDataUrl() { return initialDataUrl; }
        public void setInitialDataUrl(String initialDataUrl) { this.initialDataUrl = initialDataUrl; }
    }
}
