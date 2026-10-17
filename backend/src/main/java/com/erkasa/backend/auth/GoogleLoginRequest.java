package com.erkasa.backend.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** JSON body of POST /api/auth/google: {"idToken": "<credential from the Google button>"} */
public record GoogleLoginRequest(

        @NotBlank(message = "Google ID token is required")
        @Size(max = 4096, message = "Google ID token is too long")
        String idToken
) {
}