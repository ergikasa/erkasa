package com.erkasa.backend.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** JSON body of POST /api/auth/forgot-password: {"email": "ergi@example.com"} */
public record ForgotPasswordRequest(

        @NotBlank(message = "Email is required")
        @Email(message = "Email is not valid")
        @Size(max = 320, message = "Email is too long")
        String email
) {
}