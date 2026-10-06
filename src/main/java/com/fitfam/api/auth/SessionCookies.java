package com.fitfam.api.auth;

import java.time.Duration;

import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import com.fitfam.api.config.AppProperties;

/** Builds the HttpOnly session cookie (never readable from JavaScript, never stored in localStorage). */
@Component
public class SessionCookies {

	public static final String NAME = "ff_session";

	private final boolean secure;

	public SessionCookies(AppProperties props) {
		this.secure = props.cookie().secure();
	}

	public ResponseCookie create(String token, Duration lifetime) {
		return base(token).maxAge(lifetime).build();
	}

	public ResponseCookie clear() {
		return base("").maxAge(Duration.ZERO).build();
	}

	private ResponseCookie.ResponseCookieBuilder base(String value) {
		return ResponseCookie.from(NAME, value)
				.httpOnly(true)
				.secure(secure)
				.sameSite("Lax")
				.path("/");
	}

}
