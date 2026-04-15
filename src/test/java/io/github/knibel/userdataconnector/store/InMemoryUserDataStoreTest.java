package io.github.knibel.userdataconnector.store;

import io.github.knibel.userdataconnector.api.ChangeType;
import io.github.knibel.userdataconnector.api.UserDataChangeEvent;
import io.github.knibel.userdataconnector.api.UserDataChangeListener;
import io.github.knibel.userdataconnector.api.UserIdentityData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryUserDataStoreTest {

    private final List<UserDataChangeEvent> capturedEvents = new ArrayList<>();
    private InMemoryUserDataStore store;

    @BeforeEach
    void setUp() {
        capturedEvents.clear();
        UserDataChangeListener listener = capturedEvents::add;
        store = new InMemoryUserDataStore(List.of(listener));
    }

    @Test
    void upsert_storesDataAndPublishesUpsertEvent() {
        UserIdentityData data = new UserIdentityData("alice", Map.of("email", "alice@example.com"));
        store.upsert(data);

        assertThat(store.findByUserId("alice")).hasValue(data);
        assertThat(store.exists("alice")).isTrue();
        assertThat(capturedEvents).hasSize(1);
        UserDataChangeEvent event = capturedEvents.get(0);
        assertThat(event.getUserId()).isEqualTo("alice");
        assertThat(event.getChangeType()).isEqualTo(ChangeType.UPSERT);
        assertThat(event.getData()).isEqualTo(data);
    }

    @Test
    void upsert_replacesExistingRecord() {
        store.upsert(new UserIdentityData("alice", Map.of("role", "user")));
        UserIdentityData updated = new UserIdentityData("alice", Map.of("role", "admin"));
        store.upsert(updated);

        assertThat(store.findByUserId("alice")).hasValue(updated);
        assertThat(capturedEvents).hasSize(2);
    }

    @Test
    void delete_removesDataAndPublishesDeleteEvent() {
        store.upsert(new UserIdentityData("bob", Map.of("name", "Bob")));
        store.delete("bob");

        assertThat(store.findByUserId("bob")).isEmpty();
        assertThat(store.exists("bob")).isFalse();

        UserDataChangeEvent deleteEvent = capturedEvents.get(1);
        assertThat(deleteEvent.getUserId()).isEqualTo("bob");
        assertThat(deleteEvent.getChangeType()).isEqualTo(ChangeType.DELETE);
        assertThat(deleteEvent.getData()).isNull();
    }

    @Test
    void delete_isIdempotentWhenUserDoesNotExist() {
        // Should not throw
        store.delete("nonexistent");
        assertThat(capturedEvents).hasSize(1);
        assertThat(capturedEvents.get(0).getChangeType()).isEqualTo(ChangeType.DELETE);
    }

    @Test
    void findAll_returnsAllRecords() {
        store.upsert(new UserIdentityData("u1", Map.of("k", "v1")));
        store.upsert(new UserIdentityData("u2", Map.of("k", "v2")));

        assertThat(store.findAll()).hasSize(2)
                .extracting(UserIdentityData::getUserId)
                .containsExactlyInAnyOrder("u1", "u2");
    }

    @Test
    void findByUserId_returnsEmptyWhenNotFound() {
        assertThat(store.findByUserId("missing")).isEqualTo(Optional.empty());
    }

    @Test
    void listenerException_doesNotPreventSubsequentListeners() {
        List<UserDataChangeEvent> secondListenerEvents = new ArrayList<>();
        UserDataChangeListener failing = e -> { throw new RuntimeException("boom"); };
        UserDataChangeListener second = secondListenerEvents::add;

        InMemoryUserDataStore multiStore = new InMemoryUserDataStore(List.of(failing, second));
        multiStore.upsert(new UserIdentityData("u", Map.of()));

        // Second listener still received the event despite the first one throwing
        assertThat(secondListenerEvents).hasSize(1);
    }
}
