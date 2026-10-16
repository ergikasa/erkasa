package com.erkasa.backend.auth;

import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.erkasa.backend.common.error.ApiException;

/** Business logic for accounts. Login, tokens and Google come in the next sub-tasks. */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
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