package com.fitfam.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fitfam.api.config.AppProperties;

class SessionTokenServiceTests {

	private static AppProperties props(String secret, int days) {
		return new AppProperties(new AppProperties.Google("x"), new AppProperties.Jwt(secret),
				new AppProperties.Session(days), new AppProperties.Cookie(false), new AppProperties.Cors(List.of()));
	}

	private static final String SECRET = "a-test-secret-that-is-long-enough-for-hs256";

	@Test
	void issuedTokenParsesBackToTheUserId() {
		SessionTokenService service = new SessionTokenService(props(SECRET, 7));
		UUID id = UUID.randomUUID();

		assertThat(service.parse(service.issue(id))).contains(id);
	}

	@Test
	void tamperedTokenIsRejected() {
		SessionTokenService service = new SessionTokenService(props(SECRET, 7));
		String token = service.issue(UUID.randomUUID());

		assertThat(service.parse(token.substring(0, token.length() - 2) + "xx")).isEmpty();
		assertThat(service.parse("not-a-jwt")).isEmpty();
		assertThat(service.parse("")).isEmpty();
	}

	@Test
	void tokenSignedWithAnotherSecretIsRejected() {
		String token = new SessionTokenService(props("another-secret-another-secret-another-secret", 7))
				.issue(UUID.randomUUID());

		assertThat(new SessionTokenService(props(SECRET, 7)).parse(token)).isEmpty();
	}

	@Test
	void expiredTokenIsRejected() {
		SessionTokenService service = new SessionTokenService(props(SECRET, -1));

		assertThat(service.parse(service.issue(UUID.randomUUID()))).isEmpty();
	}

	@Test
	void shortSecretIsRefused() {
		assertThatThrownBy(() -> new SessionTokenService(props("too-short", 7)))
				.isInstanceOf(IllegalStateException.class);
	}

}
