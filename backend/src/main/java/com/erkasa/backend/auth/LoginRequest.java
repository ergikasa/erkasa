package com.erkasa.backend.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** JSON body of POST /api/auth/login. Example: {"email": "ergi@example.com", "password": "secret123"} */
public record LoginRequest(

        @NotBlank(message = "Email is required")
        @Email(message = "Email is not valid")
        String email,

        @NotBlank(message = "Password is required")
        @Size(max = 72, message = "Password is too long")
        String password
) {
}