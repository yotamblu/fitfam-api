package com.fitfam.api.auth;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Service;

import com.fitfam.api.config.AppProperties;
import com.fitfam.api.domain.User;

import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/** Issues and parses our own signed session tokens. The token only carries the user id. */
@Service
public class SessionTokenService {

	private final SecretKey key;
	private final Duration lifetime;
	private final Duration adminLifetime;

	public SessionTokenService(AppProperties props) {
		byte[] secret = props.jwt().secret().getBytes(StandardCharsets.UTF_8);
		if (secret.length < 32) {
			throw new IllegalStateException("JWT_SECRET must be at least 32 bytes long");
		}
		this.key = Keys.hmacShaKeyFor(secret);
		this.lifetime = Duration.ofDays(props.session().days());
		this.adminLifetime = Duration.ofHours(props.session().adminHours());
	}

	/** How long a session lasts for this role. Admins get a much shorter one. */
	public Duration lifetimeFor(String role) {
		return User.ROLE_ADMIN.equals(role) ? adminLifetime : lifetime;
	}

	public String issue(UUID userId, Duration lifetime) {
		Instant now = Instant.now();
		return Jwts.builder()
				.subject(userId.toString())
				.issuedAt(Date.from(now))
				.expiration(Date.from(now.plus(lifetime)))
				.signWith(key, Jwts.SIG.HS256)
				.compact();
	}

	/** @return the user id, or empty if the token is malformed, tampered with or expired */
	public Optional<UUID> parse(String token) {
		try {
			String subject = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload().getSubject();
			return Optional.of(UUID.fromString(subject));
		}
		catch (JwtException | IllegalArgumentException e) {
			return Optional.empty();
		}
	}

}
