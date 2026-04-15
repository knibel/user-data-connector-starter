package io.github.knibel.userdataconnector;

import io.github.knibel.userdataconnector.api.UserDataChangeEvent;
import io.github.knibel.userdataconnector.api.UserDataChangeListener;
import io.github.knibel.userdataconnector.api.UserDataRepository;
import io.github.knibel.userdataconnector.store.InMemoryUserDataStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link UserDataConnectorAutoConfiguration}.
 */
class UserDataConnectorAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(UserDataConnectorAutoConfiguration.class));

    @Test
    void userDataRepositoryBeanIsPresent_withWebhookSource() {
        contextRunner
                .withPropertyValues("user-data-connector.source-type=WEBHOOK")
                .run(context -> {
                    assertThat(context).hasSingleBean(UserDataRepository.class);
                    assertThat(context).hasSingleBean(InMemoryUserDataStore.class);
                });
    }

    @Test
    void webhookControllerBeanIsPresent_withWebhookSource() {
        contextRunner
                .withPropertyValues("user-data-connector.source-type=WEBHOOK")
                .run(context ->
                        assertThat(context).hasBean("userDataWebhookController"));
    }

    @Test
    void webhookControllerBeanIsAbsent_withoutSourceType() {
        contextRunner.run(context ->
                assertThat(context).doesNotHaveBean("userDataWebhookController"));
    }

    @Test
    void customListenerIsWiredIntoStore() {
        contextRunner
                .withPropertyValues("user-data-connector.source-type=WEBHOOK")
                .withUserConfiguration(ListenerConfig.class)
                .run(context -> {
                    // Verify the listener bean exists
                    assertThat(context).hasSingleBean(RecordingListener.class);

                    // Trigger a change via the store and verify the listener is called
                    InMemoryUserDataStore store = context.getBean(InMemoryUserDataStore.class);
                    store.upsert(new io.github.knibel.userdataconnector.api.UserIdentityData(
                            "test-user", java.util.Map.of("k", "v")));

                    RecordingListener listener = context.getBean(RecordingListener.class);
                    assertThat(listener.getEvents()).hasSize(1);
                    assertThat(listener.getEvents().get(0).getUserId()).isEqualTo("test-user");
                });
    }

    @Test
    void repositoryAndStoreShareTheSameInstance() {
        contextRunner
                .withPropertyValues("user-data-connector.source-type=WEBHOOK")
                .run(context -> {
                    UserDataRepository repository = context.getBean(UserDataRepository.class);
                    InMemoryUserDataStore store = context.getBean(InMemoryUserDataStore.class);
                    assertThat(repository).isSameAs(store);
                });
    }

    // ---- Supporting beans ----

    static class RecordingListener implements UserDataChangeListener {
        private final List<UserDataChangeEvent> events = new ArrayList<>();

        @Override
        public void onUserDataChanged(UserDataChangeEvent event) {
            events.add(event);
        }

        List<UserDataChangeEvent> getEvents() { return events; }
    }

    @Configuration
    static class ListenerConfig {
        @Bean
        public RecordingListener recordingListener() {
            return new RecordingListener();
        }
    }
}
