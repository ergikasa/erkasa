package com.erkasa.backend.auth;

/**
 * The facts we take from a verified Google ID token.
 *
 * @param sub           Google's permanent id for the person
 * @param email         their Google email
 * @param emailVerified whether Google has verified that email
 * @param name          their display name (may be null)
 */
public record GoogleIdentity(String sub, String email, boolean emailVerified, String name) {
}