package com.erkasa.backend.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** JSON body of POST /api/auth/reset-password: {"token": "<from the link>", "newPassword": "..."} */
public record ResetPasswordRequest(

        @NotBlank(message = "Token is required")
        @Size(max = 200, message = "Token is too long")
        String token,

        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 72, message = "Password must be 8 to 72 characters")
        String newPassword
) {
}