package com.fitfam.api.auth;

import java.util.Optional;

public interface GoogleTokenVerifier {

	/**
	 * Verifies a Google ID token (signature, audience, issuer, expiry).
	 *
	 * @return the verified user, or empty if the token is invalid
	 */
	Optional<VerifiedGoogleUser> verify(String credential);

}
