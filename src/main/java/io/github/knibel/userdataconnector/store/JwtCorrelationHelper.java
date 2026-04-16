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
     * <p>This method uses a three-state return convention:
     * <ul>
     *   <li>{@code null} – this helper could not handle the authentication (not a JWT,
     *       no issuer claim, or no matching issuer correlation configured).  The caller
     *       should fall back to default resolution.</li>
     *   <li>{@link Optional#empty()} – the correlation was attempted but the configured
     *       claim was missing from the token, or no user record matched.</li>
     *   <li>A present {@link Optional} – the user was successfully resolved.</li>
     * </ul>
     *
     * @param authentication     the current Spring Security authentication
     * @param issuerCorrelations the configured issuer-to-correlation mappings
     * @param store              the in-memory store to search
     * @return the resolved user data, an empty {@link Optional} if correlation was
     *         attempted but no match was found, or {@code null} to signal that the
     *         caller should fall back to default resolution
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
