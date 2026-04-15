package io.github.knibel.userdataconnector.store;

import io.github.knibel.userdataconnector.api.ChangeType;
import io.github.knibel.userdataconnector.api.UserDataChangeEvent;
import io.github.knibel.userdataconnector.api.UserDataChangeListener;
import io.github.knibel.userdataconnector.api.UserDataRepository;
import io.github.knibel.userdataconnector.api.UserIdentityData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory store that implements {@link UserDataRepository}.
 *
 * <p>Only the starter's internal source adapters write to this store; application code
 * reads exclusively through the {@link UserDataRepository} interface.
 */
public class InMemoryUserDataStore implements UserDataRepository {

    private static final Logger log = LoggerFactory.getLogger(InMemoryUserDataStore.class);

    private final ConcurrentHashMap<String, UserIdentityData> store = new ConcurrentHashMap<>();
    private final List<UserDataChangeListener> listeners;

    public InMemoryUserDataStore(List<UserDataChangeListener> listeners) {
        this.listeners = List.copyOf(listeners);
    }

    // ---- UserDataRepository (read-only public API) ----

    @Override
    public Optional<UserIdentityData> findByUserId(String userId) {
        return Optional.ofNullable(store.get(userId));
    }

    @Override
    public Collection<UserIdentityData> findAll() {
        return Collections.unmodifiableCollection(store.values());
    }

    @Override
    public boolean exists(String userId) {
        return store.containsKey(userId);
    }

    /**
     * Returns the identity data for the currently authenticated user.
     *
     * <p>Reads the principal name from the Spring Security {@link SecurityContextHolder}.
     * When used with Spring Boot Security OAuth2 Resource Server the Bearer JWT is
     * automatically parsed and the {@code sub} claim becomes the principal name.
     *
     * <p>Returns an empty {@link Optional} when there is no active security context,
     * the request is unauthenticated, or no record exists for the principal.
     */
    @Override
    public Optional<UserIdentityData> getCurrent() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        return findByUserId(authentication.getName());
    }

    // ---- Internal write API (package-visible for source adapters) ----

    /**
     * Inserts or replaces the identity data for the user identified by
     * {@link UserIdentityData#getUserId()}.  Triggers a {@link ChangeType#UPSERT} event.
     */
    public void upsert(UserIdentityData data) {
        store.put(data.getUserId(), data);
        notifyListeners(new UserDataChangeEvent(data.getUserId(), ChangeType.UPSERT, data));
    }

    /**
     * Removes identity data for the given user.  Triggers a {@link ChangeType#DELETE}
     * event even when no record existed before (idempotent).
     */
    public void delete(String userId) {
        store.remove(userId);
        notifyListeners(new UserDataChangeEvent(userId, ChangeType.DELETE, null));
    }

    // ---- Private helpers ----

    private void notifyListeners(UserDataChangeEvent event) {
        for (UserDataChangeListener listener : listeners) {
            try {
                listener.onUserDataChanged(event);
            } catch (Exception e) {
                log.error("UserDataChangeListener threw an exception for event {}", event, e);
            }
        }
    }
}
