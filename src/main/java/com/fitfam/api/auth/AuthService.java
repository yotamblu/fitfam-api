package com.fitfam.api.auth;

import java.time.Instant;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fitfam.api.domain.User;
import com.fitfam.api.domain.UserRepository;
import com.fitfam.api.web.ApiException;

@Service
public class AuthService {

	public record LoginResult(User user, String token) {
	}

	private final GoogleTokenVerifier verifier;
	private final UserRepository users;
	private final SessionTokenService tokens;

	public AuthService(GoogleTokenVerifier verifier, UserRepository users, SessionTokenService tokens) {
		this.verifier = verifier;
		this.users = users;
		this.tokens = tokens;
	}

	/**
	 * Google only proves who owns the email. Access is granted only if an admin already added that email, and nothing
	 * is created here for unknown emails.
	 */
	@Transactional
	public LoginResult login(String credential) {
		VerifiedGoogleUser google = verifier.verify(credential)
				.orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "invalid_token"));
		if (!google.emailVerified() || google.email() == null || google.email().isBlank()) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "email_not_verified");
		}

		String email = google.email().trim().toLowerCase(Locale.ROOT);
		User user = users.findByEmail(email)
				.orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "not_invited"));

		if (user.getFirstLoginAt() == null) {
			user.setFirstLoginAt(Instant.now());
		}
		user.setStatus(User.STATUS_ACTIVE);
		if (isBlank(user.getDisplayName())) {
			user.setDisplayName(google.name());
		}
		if (isBlank(user.getAvatarUrl())) {
			user.setAvatarUrl(google.pictureUrl());
		}
		users.save(user);

		return new LoginResult(user, tokens.issue(user.getId()));
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

}
