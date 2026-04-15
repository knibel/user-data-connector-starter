package io.github.knibel.userdataconnector.api;

import java.util.Collection;
import java.util.Optional;

/**
 * Read-only repository for user identity data.
 *
 * <p>The repository is populated automatically from the configured data source
 * (compact Kafka topic, REST + delta Kafka, or webhook).  Consumer code should
 * only read from this interface; all writes are managed internally by the starter.
 */
public interface UserDataRepository {

    /**
     * Returns the identity data for the given user, or an empty {@link Optional} if
     * no data is available for that user.
     *
     * @param userId the user identifier (never {@code null})
     * @return the user's identity data, or empty
     */
    Optional<UserIdentityData> findByUserId(String userId);

    /**
     * Returns an unmodifiable view of all user identity records currently held in memory.
     *
     * @return all known user records (never {@code null})
     */
    Collection<UserIdentityData> findAll();

    /**
     * Returns {@code true} if at least one record exists for the given user.
     *
     * @param userId the user identifier (never {@code null})
     */
    boolean exists(String userId);
}
