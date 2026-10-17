package com.erkasa.backend.auth;

/** What an emailed one-time link is for. Stored as text in email_tokens.purpose. */
public enum EmailTokenPurpose {
    VERIFY_EMAIL,
    RESET_PASSWORD
}