package io.github.knibel.userdataconnector.api;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable value object that holds the identity attributes of a single user.
 * Attributes are stored as a flat map of arbitrary key-value pairs supplied by the
 * upstream identity service.
 */
public final class UserIdentityData {

    private final String userId;
    private final Map<String, String> attributes;

    public UserIdentityData(String userId, Map<String, String> attributes) {
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(attributes, "attributes must not be null");
        this.attributes = Collections.unmodifiableMap(new HashMap<>(attributes));
    }

    /** The unique identifier of the user. */
    public String getUserId() {
        return userId;
    }

    /** All identity attributes as an unmodifiable map. */
    public Map<String, String> getAttributes() {
        return attributes;
    }

    /** Convenience accessor for a single attribute value, or {@code null} if absent. */
    public String getAttribute(String key) {
        return attributes.get(key);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UserIdentityData that)) return false;
        return Objects.equals(userId, that.userId) && Objects.equals(attributes, that.attributes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, attributes);
    }

    @Override
    public String toString() {
        return "UserIdentityData{userId='" + userId + "', attributes=" + attributes + '}';
    }
}
