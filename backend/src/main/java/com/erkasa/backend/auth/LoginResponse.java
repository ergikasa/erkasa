package com.erkasa.backend.auth;

/**
 * Returned by a successful login.
 *
 * @param accessToken      send as "Authorization: Bearer <accessToken>" on every API call
 * @param tokenType        always "Bearer"
 * @param expiresIn        access token lifetime in seconds
 * @param refreshToken     use to get a new access token when it expires (sub-task 2.4)
 * @param refreshExpiresIn refresh token lifetime in seconds
 * @param user             the logged-in user
 */
public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        String refreshToken,
        long refreshExpiresIn,
        UserResponse user
) {
}