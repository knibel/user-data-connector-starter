package io.github.knibel.userdataconnector.store;

import io.github.knibel.userdataconnector.UserDataConnectorProperties.IssuerCorrelation;
import io.github.knibel.userdataconnector.api.UserIdentityData;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Map;
import java.util.Optional;

/**
 * Helper that resolves the current user from the in-memory store using issuer-based
 * JWT correlation rules.
 *
 * <p>This class is <b>only loaded</b> when {@code spring-security-oauth2-resource-server}
 * is on the classpath.  Callers must guard access with a class-presence check.
 */
final class JwtCorrelationHelper {

    private JwtCorrelationHelper() { /* utility */ }

    /**
     * Attempts to resolve a {@link UserIdentityData} from the store by inspecting the
     * JWT token in the given authentication.
     *
     * @param authentication     the current Spring Security authentication
     * @param issuerCorrelations the configured issuer-to-correlation mappings
     * @param store              the in-memory store to search
     * @return the resolved user data, or {@code null} if this helper cannot handle the
     *         authentication (e.g. it is not a JWT authentication, or no matching issuer
     *         correlation is configured)
     */
    static Optional<UserIdentityData> resolve(
            Authentication authentication,
            Map<String, IssuerCorrelation> issuerCorrelations,
            InMemoryUserDataStore store) {

        if (!(authentication instanceof JwtAuthenticationToken jwtAuth)) {
            return null; // not a JWT – signal caller to fall back
        }

        Jwt jwt = jwtAuth.getToken();
        String issuer = jwt.getClaimAsString("iss");
        if (issuer == null) {
            return null; // no issuer claim – fall back
        }

        IssuerCorrelation correlation = issuerCorrelations.get(issuer);
        if (correlation == null) {
            return null; // no correlation configured for this issuer – fall back
        }

        String claimValue = jwt.getClaimAsString(correlation.getClaimName());
        if (claimValue == null) {
            return Optional.empty(); // claim not present in token
        }

        return store.findByAttribute(correlation.getUserKey(), claimValue);
    }
}
