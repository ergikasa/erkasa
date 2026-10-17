package com.erkasa.backend.auth;

/**
 * Turns a Google ID token (from the "Sign in with Google" button) into a trusted identity.
 *
 * An interface so tests can replace it: they cannot get real tokens from Google.
 * The real implementation is {@link GoogleIdTokenVerifier}.
 */
public interface GoogleTokenVerifier {

    /** Returns the identity, or throws ApiException 401 INVALID_GOOGLE_TOKEN. */
    GoogleIdentity verify(String idToken);
}