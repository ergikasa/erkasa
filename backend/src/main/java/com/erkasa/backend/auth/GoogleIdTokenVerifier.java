package com.erkasa.backend.auth;

import java.util.Collection;
import java.util.Set;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import com.erkasa.backend.common.error.ApiException;

/**
 * Verifies Google ID tokens.
 *
 * A Google ID token is a JWT signed with Google's private key. We check:
 * 1. Signature: with Google's public keys from GOOGLE_CERTS (downloaded and cached on first use).
 * 2. Issuer: really accounts.google.com.
 * 3. Audience: issued for OUR client id, not for some other app.
 * 4. Expiry: not expired.
 *
 * The decoder here is a private field, not a Spring bean, on purpose: SecurityConfig has its own
 * JwtDecoder bean for our access tokens, and two beans of the same type would clash.
 */
@Component
@EnableConfigurationProperties(GoogleProperties.class)
public class GoogleIdTokenVerifier implements GoogleTokenVerifier {

    private static final String GOOGLE_CERTS = "https://www.googleapis.com/oauth2/v3/certs";
    private static final Set<String> GOOGLE_ISSUERS = Set.of("accounts.google.com", "https://accounts.google.com");

    private final String clientId;
    private final JwtDecoder decoder;

    public GoogleIdTokenVerifier(GoogleProperties props) {
        this.clientId = props.clientId() == null ? "" : props.clientId().trim();

        NimbusJwtDecoder nimbus = NimbusJwtDecoder.withJwkSetUri(GOOGLE_CERTS).build();
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(),
                new JwtClaimValidator<Object>("iss", iss -> iss != null && GOOGLE_ISSUERS.contains(iss.toString())),
                new JwtClaimValidator<Object>("aud", this::audienceIsUs));
        nimbus.setJwtValidator(validator);
        this.decoder = nimbus;
    }

    /** "aud" may be a single string or a list, depending on the token. */
    private boolean audienceIsUs(Object aud) {
        if (aud instanceof Collection<?> list) {
            return list.stream().anyMatch(a -> clientId.equals(String.valueOf(a)));
        }
        return aud != null && clientId.equals(aud.toString());
    }

    @Override
    public GoogleIdentity verify(String idToken) {
        if (clientId.isEmpty()) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "GOOGLE_LOGIN_NOT_CONFIGURED",
                    "Google login is not configured on this server");
        }
        try {
            Jwt jwt = decoder.decode(idToken);
            return new GoogleIdentity(
                    jwt.getSubject(),
                    jwt.getClaimAsString("email"),
                    Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified")),
                    jwt.getClaimAsString("name"));
        } catch (JwtException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_GOOGLE_TOKEN",
                    "Google sign-in failed. Please try again");
        }
    }
}