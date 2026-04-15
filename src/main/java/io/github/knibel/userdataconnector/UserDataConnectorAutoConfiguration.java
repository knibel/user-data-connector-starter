package io.github.knibel.userdataconnector;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.knibel.userdataconnector.api.UserDataChangeListener;
import io.github.knibel.userdataconnector.api.UserDataRepository;
import io.github.knibel.userdataconnector.source.KafkaCompactUserDataSource;
import io.github.knibel.userdataconnector.source.RestWithDeltaKafkaUserDataSource;
import io.github.knibel.userdataconnector.store.InMemoryUserDataStore;
import io.github.knibel.userdataconnector.web.UserDataWebhookController;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.DispatcherServlet;

import java.util.stream.Collectors;

/**
 * Spring Boot auto-configuration for the user-data-connector starter.
 *
 * <p>Registers the following beans automatically:
 * <ul>
 *   <li>{@link UserDataRepository} – the read-only repository (always present).</li>
 *   <li>One source adapter bean depending on
 *       {@code user-data-connector.source-type}:
 *     <ul>
 *       <li>{@code KAFKA_COMPACT} → {@link KafkaCompactUserDataSource}
 *           (requires {@code spring-kafka} on the classpath)</li>
 *       <li>{@code REST_WITH_DELTA_KAFKA} → {@link RestWithDeltaKafkaUserDataSource}
 *           (requires {@code spring-kafka} and {@code spring-web} on the classpath)</li>
 *       <li>{@code WEBHOOK} → {@link UserDataWebhookController}
 *           (requires {@code spring-webmvc} on the classpath)</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p>All {@link UserDataChangeListener} beans found in the application context are
 * automatically wired into the store so they receive change notifications.
 */
@AutoConfiguration
@EnableConfigurationProperties(UserDataConnectorProperties.class)
public class UserDataConnectorAutoConfiguration {

    // ---- Always-present beans ----

    /**
     * Fallback {@link ObjectMapper} created only when no other {@code ObjectMapper}
     * bean is present in the context (e.g. when Spring Boot's Jackson auto-configuration
     * is not active).
     */
    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper userDataConnectorObjectMapper() {
        return new ObjectMapper();
    }

    /**
     * The central in-memory store.  All {@link UserDataChangeListener} beans discovered
     * in the application context are registered at creation time.
     */
    @Bean
    public InMemoryUserDataStore inMemoryUserDataStore(
            ObjectProvider<UserDataChangeListener> listenerProvider) {
        return new InMemoryUserDataStore(
                listenerProvider.stream().collect(Collectors.toList()));
    }

    /**
     * Exposes the store as the public {@link UserDataRepository} API.
     * Applications should inject {@link UserDataRepository}, not the store directly.
     */
    @Bean
    @ConditionalOnMissingBean(UserDataRepository.class)
    public UserDataRepository userDataRepository(InMemoryUserDataStore store) {
        return store;
    }

    // ---- KAFKA_COMPACT source ----

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.kafka.listener.KafkaMessageListenerContainer")
    @ConditionalOnProperty(prefix = "user-data-connector", name = "source-type",
            havingValue = "KAFKA_COMPACT")
    static class KafkaCompactSourceConfiguration {

        @Bean
        public KafkaCompactUserDataSource kafkaCompactUserDataSource(
                InMemoryUserDataStore store,
                UserDataConnectorProperties properties,
                ObjectMapper objectMapper) {
            return new KafkaCompactUserDataSource(
                    store, properties.getKafkaCompact(), objectMapper);
        }
    }

    // ---- REST_WITH_DELTA_KAFKA source ----

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = {
            "org.springframework.kafka.listener.KafkaMessageListenerContainer",
            "org.springframework.web.client.RestTemplate"
    })
    @ConditionalOnProperty(prefix = "user-data-connector", name = "source-type",
            havingValue = "REST_WITH_DELTA_KAFKA")
    static class RestWithDeltaKafkaSourceConfiguration {

        /**
         * Creates a dedicated {@link RestTemplate} for the snapshot fetch.
         * If the application already defines a {@code RestTemplate} bean, that one is
         * reused instead.
         */
        @Bean
        @ConditionalOnMissingBean(RestTemplate.class)
        public RestTemplate userDataConnectorRestTemplate() {
            return new RestTemplate();
        }

        @Bean
        public RestWithDeltaKafkaUserDataSource restWithDeltaKafkaUserDataSource(
                InMemoryUserDataStore store,
                UserDataConnectorProperties properties,
                ObjectMapper objectMapper,
                RestTemplate restTemplate) {
            return new RestWithDeltaKafkaUserDataSource(
                    store, properties.getRestWithDeltaKafka(), objectMapper, restTemplate);
        }
    }

    // ---- WEBHOOK source ----

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(DispatcherServlet.class)
    @ConditionalOnProperty(prefix = "user-data-connector", name = "source-type",
            havingValue = "WEBHOOK")
    static class WebhookSourceConfiguration {

        @Bean
        public UserDataWebhookController userDataWebhookController(
                InMemoryUserDataStore store,
                UserDataConnectorProperties properties) {
            return new UserDataWebhookController(store, properties.getWebhook());
        }
    }
}
