package io.github.knibel.userdataconnector.api;

/**
 * Describes whether a {@link UserDataChangeEvent} represents an insert/update or a deletion.
 */
public enum ChangeType {
    /** The user's identity data was inserted or updated. */
    UPSERT,
    /** The user's identity data was removed. */
    DELETE
}
