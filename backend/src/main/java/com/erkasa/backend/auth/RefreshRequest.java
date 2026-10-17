package com.erkasa.backend.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** JSON body of POST /api/auth/refresh and POST /api/auth/logout: {"refreshToken": "..."} */
public record RefreshRequest(

        @NotBlank(message = "Refresh token is required")
        @Size(max = 200, message = "Refresh token is too long")
        String refreshToken
) {
}