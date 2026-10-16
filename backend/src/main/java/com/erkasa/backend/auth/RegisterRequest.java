package com.erkasa.backend.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * JSON body of POST /api/auth/register.
 * Example: {"email": "ergi@example.com", "password": "secret123", "displayName": "Ergi"}
 */
public record RegisterRequest(

        @NotBlank(message = "Email is required")
        @Email(message = "Email is not valid")
        @Size(max = 320, message = "Email is too long")
        String email,

        // BCrypt only uses the first 72 bytes, so we cap the length there.
        @NotBlank(message = "Password is required")
        @Size(min = 8, max = 72, message = "Password must be 8 to 72 characters")
        String password,

        @Size(max = 100, message = "Name must be at most 100 characters")
        String displayName
) {
}