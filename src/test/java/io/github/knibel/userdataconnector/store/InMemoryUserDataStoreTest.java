package io.github.knibel.userdataconnector.store;

import io.github.knibel.userdataconnector.UserDataConnectorProperties.IssuerCorrelation;
import io.github.knibel.userdataconnector.api.ChangeType;
import io.github.knibel.userdataconnector.api.UserDataChangeEvent;
import io.github.knibel.userdataconnector.api.UserDataChangeListener;
import io.github.knibel.userdataconnector.api.UserIdentityData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
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

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
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
    void getCurrent_returnsEmptyWhenNoSecurityContext() {
        assertThat(store.getCurrent()).isEmpty();
    }

    @Test
    void getCurrent_returnsEmptyWhenAnonymousAuthentication() {
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken("key", "anonymousUser",
                        List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        assertThat(store.getCurrent()).isEmpty();
    }

    @Test
    void getCurrent_returnsUserDataForAuthenticatedPrincipal() {
        UserIdentityData data = new UserIdentityData("alice", Map.of("email", "alice@example.com"));
        store.upsert(data);

        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("alice", null, "ROLE_USER"));

        assertThat(store.getCurrent()).hasValue(data);
    }

    @Test
    void getCurrent_returnsEmptyWhenAuthenticatedButNoRecordFound() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("unknown-user", null, "ROLE_USER"));

        assertThat(store.getCurrent()).isEmpty();
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

    // ---- findByAttribute tests ----

    @Test
    void findByAttribute_findsMatchingRecord() {
        store.upsert(new UserIdentityData("u1", Map.of("loginName", "alice", "email", "alice@example.com")));
        store.upsert(new UserIdentityData("u2", Map.of("loginName", "bob", "email", "bob@example.com")));

        assertThat(store.findByAttribute("loginName", "alice"))
                .isPresent()
                .hasValueSatisfying(d -> assertThat(d.getUserId()).isEqualTo("u1"));
    }

    @Test
    void findByAttribute_returnsEmptyWhenNoMatch() {
        store.upsert(new UserIdentityData("u1", Map.of("loginName", "alice")));

        assertThat(store.findByAttribute("loginName", "nonexistent")).isEmpty();
    }

    @Test
    void findByAttribute_returnsEmptyWhenKeyNotPresent() {
        store.upsert(new UserIdentityData("u1", Map.of("email", "alice@example.com")));

        assertThat(store.findByAttribute("loginName", "alice")).isEmpty();
    }

    // ---- Issuer-based JWT correlation tests ----

    @Test
    void getCurrent_usesIssuerCorrelation_whenJwtMatchesConfiguredIssuer() {
        IssuerCorrelation correlation = new IssuerCorrelation();
        correlation.setClaimName("preferred_username");
        correlation.setUserKey("loginName");

        InMemoryUserDataStore correlatedStore = new InMemoryUserDataStore(
                List.of(), Map.of("https://issuer-x.example.com", correlation));

        correlatedStore.upsert(new UserIdentityData("internal-id-1",
                Map.of("loginName", "alice", "email", "alice@example.com")));

        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("iss", "https://issuer-x.example.com")
                .claim("sub", "external-sub-123")
                .claim("preferred_username", "alice")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_USER"))));

        assertThat(correlatedStore.getCurrent())
                .isPresent()
                .hasValueSatisfying(d -> {
                    assertThat(d.getUserId()).isEqualTo("internal-id-1");
                    assertThat(d.getAttribute("loginName")).isEqualTo("alice");
                });
    }

    @Test
    void getCurrent_usesSubClaimCorrelation_whenConfiguredForDifferentIssuer() {
        IssuerCorrelation correlation = new IssuerCorrelation();
        correlation.setClaimName("sub");
        correlation.setUserKey("userID");

        InMemoryUserDataStore correlatedStore = new InMemoryUserDataStore(
                List.of(), Map.of("https://issuer-y.example.com", correlation));

        correlatedStore.upsert(new UserIdentityData("internal-id-2",
                Map.of("userID", "sub-456", "name", "Bob")));

        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("iss", "https://issuer-y.example.com")
                .claim("sub", "sub-456")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_USER"))));

        assertThat(correlatedStore.getCurrent())
                .isPresent()
                .hasValueSatisfying(d -> {
                    assertThat(d.getUserId()).isEqualTo("internal-id-2");
                    assertThat(d.getAttribute("userID")).isEqualTo("sub-456");
                });
    }

    @Test
    void getCurrent_fallsBackToDefault_whenJwtIssuerNotConfigured() {
        IssuerCorrelation correlation = new IssuerCorrelation();
        correlation.setClaimName("preferred_username");
        correlation.setUserKey("loginName");

        InMemoryUserDataStore correlatedStore = new InMemoryUserDataStore(
                List.of(), Map.of("https://issuer-x.example.com", correlation));

        // Store a user with userId matching the sub claim
        correlatedStore.upsert(new UserIdentityData("sub-from-unknown-issuer", Map.of("name", "Carol")));

        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("iss", "https://unknown-issuer.example.com")
                .claim("sub", "sub-from-unknown-issuer")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_USER"))));

        // Falls back to default: authentication.getName() → findByUserId()
        assertThat(correlatedStore.getCurrent())
                .isPresent()
                .hasValueSatisfying(d -> assertThat(d.getUserId()).isEqualTo("sub-from-unknown-issuer"));
    }

    @Test
    void getCurrent_fallsBackToDefault_whenNonJwtAuthentication() {
        IssuerCorrelation correlation = new IssuerCorrelation();
        correlation.setClaimName("preferred_username");
        correlation.setUserKey("loginName");

        InMemoryUserDataStore correlatedStore = new InMemoryUserDataStore(
                List.of(), Map.of("https://issuer-x.example.com", correlation));

        correlatedStore.upsert(new UserIdentityData("alice", Map.of("loginName", "alice")));

        // Use a non-JWT authentication (e.g. form login)
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("alice", null, "ROLE_USER"));

        // Falls back to default: authentication.getName() → findByUserId()
        assertThat(correlatedStore.getCurrent())
                .isPresent()
                .hasValueSatisfying(d -> assertThat(d.getUserId()).isEqualTo("alice"));
    }

    @Test
    void getCurrent_returnsEmpty_whenJwtClaimValueNotFoundInStore() {
        IssuerCorrelation correlation = new IssuerCorrelation();
        correlation.setClaimName("preferred_username");
        correlation.setUserKey("loginName");

        InMemoryUserDataStore correlatedStore = new InMemoryUserDataStore(
                List.of(), Map.of("https://issuer-x.example.com", correlation));

        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("iss", "https://issuer-x.example.com")
                .claim("sub", "external-sub-123")
                .claim("preferred_username", "nonexistent-user")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_USER"))));

        assertThat(correlatedStore.getCurrent()).isEmpty();
    }

    @Test
    void getCurrent_returnsEmpty_whenJwtMissingConfiguredClaim() {
        IssuerCorrelation correlation = new IssuerCorrelation();
        correlation.setClaimName("preferred_username");
        correlation.setUserKey("loginName");

        InMemoryUserDataStore correlatedStore = new InMemoryUserDataStore(
                List.of(), Map.of("https://issuer-x.example.com", correlation));

        // JWT does not contain the "preferred_username" claim
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("iss", "https://issuer-x.example.com")
                .claim("sub", "external-sub-123")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_USER"))));

        assertThat(correlatedStore.getCurrent()).isEmpty();
    }

    @Test
    void getCurrent_multipleIssuers_selectsCorrectCorrelation() {
        IssuerCorrelation correlationX = new IssuerCorrelation();
        correlationX.setClaimName("preferred_username");
        correlationX.setUserKey("loginName");

        IssuerCorrelation correlationY = new IssuerCorrelation();
        correlationY.setClaimName("sub");
        correlationY.setUserKey("userID");

        InMemoryUserDataStore correlatedStore = new InMemoryUserDataStore(
                List.of(), Map.of(
                        "https://issuer-x.example.com", correlationX,
                        "https://issuer-y.example.com", correlationY));

        correlatedStore.upsert(new UserIdentityData("id-1",
                Map.of("loginName", "alice", "userID", "unrelated")));
        correlatedStore.upsert(new UserIdentityData("id-2",
                Map.of("loginName", "unrelated", "userID", "sub-bob")));

        // Authenticate as issuer X → should match by loginName
        Jwt jwtX = Jwt.withTokenValue("token-x")
                .header("alg", "RS256")
                .claim("iss", "https://issuer-x.example.com")
                .claim("sub", "ignored-sub")
                .claim("preferred_username", "alice")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwtX, List.of(new SimpleGrantedAuthority("ROLE_USER"))));

        assertThat(correlatedStore.getCurrent())
                .isPresent()
                .hasValueSatisfying(d -> assertThat(d.getUserId()).isEqualTo("id-1"));

        // Switch to issuer Y → should match by userID
        Jwt jwtY = Jwt.withTokenValue("token-y")
                .header("alg", "RS256")
                .claim("iss", "https://issuer-y.example.com")
                .claim("sub", "sub-bob")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwtY, List.of(new SimpleGrantedAuthority("ROLE_USER"))));

        assertThat(correlatedStore.getCurrent())
                .isPresent()
                .hasValueSatisfying(d -> assertThat(d.getUserId()).isEqualTo("id-2"));
    }
}
