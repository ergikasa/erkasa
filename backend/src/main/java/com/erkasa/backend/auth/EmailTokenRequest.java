package com.erkasa.backend.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** JSON body of POST /api/auth/verify-email: {"token": "<from the link>"} */
public record EmailTokenRequest(

        @NotBlank(message = "Token is required")
        @Size(max = 200, message = "Token is too long")
        String token
) {
}