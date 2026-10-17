package com.erkasa.backend.auth;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

/** Email verification and password reset endpoints. All answer 204 No Content on success. */
@RestController
@RequestMapping("/api/auth")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    /** Public. Token from the "confirm your email" link. 400 INVALID_OR_EXPIRED_TOKEN if it is not usable. */
    @PostMapping("/verify-email")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verifyEmail(@Valid @RequestBody EmailTokenRequest request) {
        accountService.verifyEmail(request.token());
    }

    /** Needs an access token. Sends a fresh verification link; the previous one stops working. */
    @PostMapping("/resend-verification")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resendVerification(@AuthenticationPrincipal Jwt jwt) {
        accountService.resendVerificationEmail(UUID.fromString(jwt.getSubject()));
    }

    /** Public. Always 204, whether or not the email has an account. */
    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        accountService.forgotPassword(request.email());
    }

    /** Public. Token from the reset link + the new password. Logs the user out everywhere. */
    @PostMapping("/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        accountService.resetPassword(request.token(), request.newPassword());
    }
}