package com.erkasa.backend.auth;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.erkasa.backend.common.error.ApiException;

/**
 * GET /api/me: the logged-in user. The first protected endpoint.
 *
 * Spring Security has already checked the token before this runs; @AuthenticationPrincipal
 * gives us its claims. "sub" (subject) is the user's id.
 */
@RestController
public class MeController {

    private final UserRepository users;

    public MeController(UserRepository users) {
        this.users = users;
    }

    @GetMapping("/api/me")
    public UserResponse me(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = UUID.fromString(jwt.getSubject());
        return users.findById(userId)
                .map(UserResponse::from)
                // The token is valid but the account was deleted after it was issued.
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "USER_NOT_FOUND",
                        "This account no longer exists"));
    }
}