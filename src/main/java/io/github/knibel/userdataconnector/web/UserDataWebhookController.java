package io.github.knibel.userdataconnector.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.knibel.userdataconnector.UserDataConnectorProperties.WebhookProperties;
import io.github.knibel.userdataconnector.api.UserIdentityData;
import io.github.knibel.userdataconnector.store.InMemoryUserDataStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * REST controller that receives push notifications from the upstream identity service
 * when the {@code WEBHOOK} source type is active.
 *
 * <p>Expected request body (JSON):
 * <pre>{@code
 * {
 *   "userId": "alice",
 *   "eventType": "UPSERT",        // or "DELETE"
 *   "attributes": {
 *     "email": "alice@example.com",
 *     "role":  "admin"
 *   }
 * }
 * }</pre>
 *
 * <p>When {@code user-data-connector.webhook.secret} is set, every request must carry
 * an {@code X-Webhook-Secret} header with the matching value; otherwise the request is
 * rejected with {@code 401 Unauthorized}.
 */
@RestController
public class UserDataWebhookController {

    private static final Logger log = LoggerFactory.getLogger(UserDataWebhookController.class);

    static final String EVENT_TYPE_DELETE = "DELETE";

    private final InMemoryUserDataStore store;
    private final WebhookProperties config;

    public UserDataWebhookController(InMemoryUserDataStore store, WebhookProperties config) {
        this.store = store;
        this.config = config;
    }

    /**
     * Handles incoming webhook push notifications.
     *
     * <p>The path is configurable via {@code user-data-connector.webhook.path}
     * (default: {@code /webhook/user-data}).
     */
    @PostMapping("${user-data-connector.webhook.path:/webhook/user-data}")
    public ResponseEntity<Void> handleWebhook(
            @RequestBody WebhookPayload payload,
            @RequestHeader(value = "X-Webhook-Secret", required = false) String providedSecret) {

        if (!isAuthorized(providedSecret)) {
            log.warn("Rejected webhook request – invalid or missing X-Webhook-Secret");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        if (payload.getUserId() == null || payload.getUserId().isBlank()) {
            log.warn("Rejected webhook request – userId is missing");
            return ResponseEntity.badRequest().build();
        }

        if (EVENT_TYPE_DELETE.equalsIgnoreCase(payload.getEventType())) {
            store.delete(payload.getUserId());
            log.debug("Webhook: deleted user '{}'", payload.getUserId());
        } else {
            Map<String, String> attributes = payload.getAttributes() != null
                    ? payload.getAttributes() : new HashMap<>();
            store.upsert(new UserIdentityData(payload.getUserId(), attributes));
            log.debug("Webhook: upserted user '{}'", payload.getUserId());
        }

        return ResponseEntity.ok().build();
    }

    private boolean isAuthorized(String providedSecret) {
        String expected = config.getSecret();
        if (expected == null || expected.isBlank()) {
            // No secret configured – allow all requests
            return true;
        }
        return expected.equals(providedSecret);
    }

    // ---- Request DTO ----

    static class WebhookPayload {
        @JsonProperty("userId")
        private String userId;

        /** {@code UPSERT} (default) or {@code DELETE}. */
        @JsonProperty("eventType")
        private String eventType = "UPSERT";

        @JsonProperty("attributes")
        private Map<String, String> attributes;

        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }

        public String getEventType() { return eventType; }
        public void setEventType(String eventType) { this.eventType = eventType; }

        public Map<String, String> getAttributes() { return attributes; }
        public void setAttributes(Map<String, String> attributes) { this.attributes = attributes; }
    }
}
