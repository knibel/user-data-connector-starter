package io.github.knibel.userdataconnector.api;

import java.util.Objects;

/**
 * Emitted by the starter whenever user identity data changes in the in-memory store.
 *
 * <p>For {@link ChangeType#UPSERT} events the {@link #getData()} field contains the
 * new state.  For {@link ChangeType#DELETE} events {@link #getData()} is {@code null}.
 */
public final class UserDataChangeEvent {

    private final String userId;
    private final ChangeType changeType;
    private final UserIdentityData data;

    public UserDataChangeEvent(String userId, ChangeType changeType, UserIdentityData data) {
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.changeType = Objects.requireNonNull(changeType, "changeType must not be null");
        this.data = data;
    }

    /** The user that was affected by the change. */
    public String getUserId() {
        return userId;
    }

    /** Whether the data was inserted/updated or deleted. */
    public ChangeType getChangeType() {
        return changeType;
    }

    /**
     * The new identity data (only set for {@link ChangeType#UPSERT}; {@code null} for
     * {@link ChangeType#DELETE}).
     */
    public UserIdentityData getData() {
        return data;
    }

    @Override
    public String toString() {
        return "UserDataChangeEvent{userId='" + userId + "', changeType=" + changeType + '}';
    }
}
