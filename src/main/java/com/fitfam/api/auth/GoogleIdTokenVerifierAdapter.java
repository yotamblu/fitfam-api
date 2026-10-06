package com.fitfam.api.auth;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.fitfam.api.config.AppProperties;
import com.fitfam.api.web.ApiException;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;

/**
 * Verifies Google ID tokens with Google's library: signature against Google's public keys, audience (our client ID),
 * issuer and expiry.
 */
@Component
public class GoogleIdTokenVerifierAdapter implements GoogleTokenVerifier {

	private static final Logger log = LoggerFactory.getLogger(GoogleIdTokenVerifierAdapter.class);

	private final GoogleIdTokenVerifier verifier;

	public GoogleIdTokenVerifierAdapter(AppProperties props) {
		this.verifier = new GoogleIdTokenVerifier.Builder(new NetHttpTransport(), GsonFactory.getDefaultInstance())
				.setAudience(List.of(props.google().clientId()))
				.build();
	}

	@Override
	public Optional<VerifiedGoogleUser> verify(String credential) {
		try {
			GoogleIdToken token = verifier.verify(credential);
			if (token == null) {
				return Optional.empty();
			}
			GoogleIdToken.Payload payload = token.getPayload();
			return Optional.of(new VerifiedGoogleUser(
					payload.getEmail(),
					Boolean.TRUE.equals(payload.getEmailVerified()),
					(String) payload.get("name"),
					(String) payload.get("picture")));
		}
		catch (GeneralSecurityException | RuntimeException e) {
			// malformed or tampered token (the library can throw unchecked exceptions for missing claims)
			return Optional.empty();
		}
		catch (IOException e) {
			// could not reach Google to fetch its public keys: not the caller's fault (log the cause, never the token)
			log.warn("Could not fetch Google's public keys: {}", e.toString());
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "google_unavailable");
		}
	}

}
