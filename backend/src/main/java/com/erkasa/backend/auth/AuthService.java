package com.erkasa.backend.auth;

import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.erkasa.backend.common.error.ApiException;

/** Business logic for accounts: registration and login. Refresh, logout and Google come next. */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokens;

    /**
     * Hash of a random password, used when the email does not exist. We still run one
     * BCrypt check so "unknown email" takes as long as "wrong password"; otherwise an
     * attacker could find out which emails have accounts by timing the response.
     */
    private final String dummyHash;

    public AuthService(UserRepository users,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       RefreshTokenService refreshTokens) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokens = refreshTokens;
        this.dummyHash = passwordEncoder.encode("timing-protection-" + System.nanoTime());
    }

    @Transactional
    public User register(RegisterRequest request) {
        String email = normalizeEmail(request.email());

        if (users.existsByEmail(email)) {
            throw emailTaken();
        }

        String displayName = blankToNull(request.displayName());
        User user = User.newLocalUser(email, passwordEncoder.encode(request.password()), displayName);

        try {
            // saveAndFlush sends the INSERT now, so a duplicate created at the same moment
            // by another request fails here (unique index) instead of after we return.
            User saved = users.saveAndFlush(user);
            log.info("New user registered: {}", saved.getId());
            return saved;
        } catch (DataIntegrityViolationException e) {
            throw emailTaken();
        }
    }

    /**
     * Checks email + password and returns an access token and a refresh token.
     * Wrong email and wrong password give the same error on purpose.
     */
    @Transactional
    public LoginResponse login(LoginRequest request) {
        Optional<User> found = users.findByEmail(normalizeEmail(request.email()));

        // Google-only users have no password hash; they must use "Sign in with Google".
        String hash = found.map(User::getPasswordHash).orElse(null);
        boolean passwordOk = passwordEncoder.matches(request.password(), hash != null ? hash : dummyHash);

        if (found.isEmpty() || hash == null || !passwordOk) {
            log.info("Failed login attempt");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS",
                    "Email or password is incorrect");
        }

        User user = found.get();
        String accessToken = jwtService.createAccessToken(user);
        String refreshToken = refreshTokens.createForNewLogin(user.getId());
        log.info("User logged in: {}", user.getId());

        return new LoginResponse(
                accessToken,
                "Bearer",
                jwtService.accessTokenTtlSeconds(),
                refreshToken,
                refreshTokens.refreshTokenTtlSeconds(),
                UserResponse.from(user));
    }

    /** "  Ergi@Example.COM " and "ergi@example.com" are the same account. */
    static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }

    private static ApiException emailTaken() {
        return new ApiException(HttpStatus.CONFLICT, "EMAIL_TAKEN",
                "An account with this email already exists");
    }
}