package io.github.knibel.userdataconnector.api;

/**
 * Hook interface for receiving notifications whenever user identity data changes.
 *
 * <p>Implement this interface and register the implementation as a Spring bean in the
 * application context.  The starter will automatically discover all registered listeners
 * and call {@link #onUserDataChanged(UserDataChangeEvent)} after every change.
 *
 * <pre>{@code
 * @Component
 * public class MyAuditListener implements UserDataChangeListener {
 *     @Override
 *     public void onUserDataChanged(UserDataChangeEvent event) {
 *         // react to the change …
 *     }
 * }
 * }</pre>
 */
@FunctionalInterface
public interface UserDataChangeListener {

    /**
     * Called after a user identity record has been inserted, updated, or deleted.
     *
     * @param event the change event (never {@code null})
     */
    void onUserDataChanged(UserDataChangeEvent event);
}
