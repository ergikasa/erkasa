package com.erkasa.backend.auth;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.erkasa.backend.common.error.ApiException;

/** Business logic for accounts: registration, password login, Google login, refresh and logout. */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokens;
    private final GoogleTokenVerifier googleVerifier;
    private final AccountService accountService;

    /**
     * Hash of a random password, used when the email does not exist. We still run one
     * BCrypt check so "unknown email" takes as long as "wrong password"; otherwise an
     * attacker could find out which emails have accounts by timing the response.
     */
    private final String dummyHash;

    public AuthService(UserRepository users,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       RefreshTokenService refreshTokens,
                       GoogleTokenVerifier googleVerifier,
                       AccountService accountService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokens = refreshTokens;
        this.googleVerifier = googleVerifier;
        this.accountService = accountService;
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

        User saved;
        try {
            // saveAndFlush sends the INSERT now, so a duplicate created at the same moment
            // by another request fails here (unique index) instead of after we return.
            saved = users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw emailTaken();
        }
        log.info("New user registered: {}", saved.getId());

        // Sent after this transaction commits (see EmailDispatcher).
        accountService.sendVerificationEmail(saved);
        return saved;
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
        String refreshToken = refreshTokens.createForNewLogin(user.getId());
        log.info("User logged in: {}", user.getId());
        return tokenResponse(user, refreshToken);
    }

    /**
     * "Sign in with Google". One button for both sign-up and login:
     *
     * 1. Known Google account (google_sub)  -> log in.
     * 2. Same email as an existing account  -> link Google to it, log in.
     * 3. Neither                            -> create a new account, log in.
     */
    @Transactional
    public LoginResponse loginWithGoogle(GoogleLoginRequest request) {
        GoogleIdentity google = googleVerifier.verify(request.idToken());

        if (!google.emailVerified() || google.email() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "GOOGLE_EMAIL_NOT_VERIFIED",
                    "Your Google email is not verified");
        }
        String email = normalizeEmail(google.email());

        User user = users.findByGoogleSub(google.sub())
                .orElseGet(() -> linkOrCreateGoogleUser(google, email));

        String refreshToken = refreshTokens.createForNewLogin(user.getId());
        log.info("User logged in with Google: {}", user.getId());
        return tokenResponse(user, refreshToken);
    }

    private User linkOrCreateGoogleUser(GoogleIdentity google, String email) {
        Optional<User> existing = users.findByEmail(email);

        if (existing.isPresent()) {
            User user = existing.get();
            if (user.getGoogleSub() != null) {
                // This email is already linked to a DIFFERENT Google account.
                throw new ApiException(HttpStatus.CONFLICT, "GOOGLE_ACCOUNT_CONFLICT",
                        "This email is linked to another Google account");
            }
            user.linkGoogle(google.sub());
            log.info("Linked Google to existing user {}", user.getId());
            return users.saveAndFlush(user);
        }

        try {
            User created = users.saveAndFlush(User.newGoogleUser(email, google.sub(), blankToNull(google.name())));
            log.info("New user registered with Google: {}", created.getId());
            return created;
        } catch (DataIntegrityViolationException e) {
            // Two sign-ins for the same new account at the same moment; the other one won.
            throw new ApiException(HttpStatus.CONFLICT, "GOOGLE_SIGNIN_RETRY", "Please try again");
        }
    }

    /**
     * New access token + new refresh token in exchange for a valid refresh token.
     *
     * Deliberately NOT @Transactional: rotate() must be able to save the "session ended"
     * revocation even though it then throws. An outer transaction here would roll that back.
     */
    public LoginResponse refresh(RefreshRequest request) {
        RefreshTokenService.Rotation rotation = refreshTokens.rotate(request.refreshToken());
        User user = users.findById(rotation.userId())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN",
                        "Session expired. Please log in again"));
        return tokenResponse(user, rotation.newRefreshToken());
    }

    /** Logout on this device. Always succeeds, even for unknown tokens. */
    public void logout(RefreshRequest request) {
        refreshTokens.revokeSession(request.refreshToken());
    }

    /** Logout on every device. */
    public void logoutEverywhere(UUID userId) {
        int revoked = refreshTokens.revokeAllForUser(userId);
        log.info("User {} logged out everywhere ({} sessions ended)", userId, revoked);
    }

    private LoginResponse tokenResponse(User user, String refreshToken) {
        return new LoginResponse(
                jwtService.createAccessToken(user),
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