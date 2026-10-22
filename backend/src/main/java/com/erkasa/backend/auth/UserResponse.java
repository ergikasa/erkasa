package com.erkasa.backend.auth;

import java.time.Instant;
import java.util.UUID;

/**
 * What the API returns about a user. Deliberately has no password hash:
 * we never send the entity itself to the client.
 */
public record UserResponse(
        UUID id,
        String email,
        String displayName,
        boolean emailVerified,
        Role role,
        Instant createdAt,
        /** balance: 1 credit = 1 second of video (phase 7) */
        int credits
) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.isEmailVerified(),
                user.getRole(),
                user.getCreatedAt(),
                user.getCredits());
    }
}