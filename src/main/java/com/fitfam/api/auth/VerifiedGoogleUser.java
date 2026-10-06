package com.fitfam.api.auth;

/** The few fields we use from a verified Google ID token. */
public record VerifiedGoogleUser(String email, boolean emailVerified, String name, String pictureUrl) {
}
