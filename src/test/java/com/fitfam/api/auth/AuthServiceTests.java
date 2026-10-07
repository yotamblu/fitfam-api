package com.fitfam.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import com.fitfam.api.config.AppProperties;
import com.fitfam.api.domain.User;
import com.fitfam.api.domain.UserRepository;
import com.fitfam.api.web.ApiException;

@ExtendWith(MockitoExtension.class)
class AuthServiceTests {

	@Mock
	private UserRepository users;

	private SessionTokenService tokens;
	private VerifiedGoogleUser googleUser;
	private AuthService service;

	@BeforeEach
	void setUp() {
		tokens = new SessionTokenService(new AppProperties(new AppProperties.Google("x"),
				new AppProperties.Jwt("a-test-secret-that-is-long-enough-for-hs256"), new AppProperties.Session(7, 12),
				new AppProperties.Cookie(false), new AppProperties.Cors(List.of())));
		googleUser = new VerifiedGoogleUser("Alice@Example.com ", true, "Alice", "http://img/alice.png");
		service = new AuthService(credential -> "good".equals(credential) ? Optional.of(googleUser) : Optional.empty(),
				users, tokens);
	}

	private static User invitedUser() {
		User user = new User("alice@example.com", User.ROLE_CUSTOMER, User.STATUS_INVITED);
		ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
		return user;
	}

	@Test
	void invitedUserLogsInAndIsActivated() {
		User user = invitedUser();
		when(users.findByEmail("alice@example.com")).thenReturn(Optional.of(user));

		AuthService.LoginResult result = service.login("good");

		assertThat(result.user().getStatus()).isEqualTo(User.STATUS_ACTIVE);
		assertThat(result.user().getFirstLoginAt()).isNotNull();
		assertThat(result.user().getDisplayName()).isEqualTo("Alice");
		assertThat(result.user().getAvatarUrl()).isEqualTo("http://img/alice.png");
		assertThat(tokens.parse(result.token())).contains(user.getId());
		verify(users).save(user);
	}

	@Test
	void firstLoginTimestampIsKeptOnLaterLogins() {
		User user = invitedUser();
		Instant first = Instant.parse("2026-01-01T00:00:00Z");
		user.setFirstLoginAt(first);
		user.setDisplayName("Already Set");
		when(users.findByEmail("alice@example.com")).thenReturn(Optional.of(user));

		service.login("good");

		assertThat(user.getFirstLoginAt()).isEqualTo(first);
		assertThat(user.getDisplayName()).isEqualTo("Already Set");
	}

	@Test
	void emailNotOnTheListIsRejectedAndNothingIsCreated() {
		when(users.findByEmail("alice@example.com")).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.login("good"))
				.isInstanceOfSatisfying(ApiException.class, e -> {
					assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
					assertThat(e.getCode()).isEqualTo("not_invited");
				});
		verify(users, never()).save(any());
	}

	@Test
	void unverifiedGoogleEmailIsRejected() {
		googleUser = new VerifiedGoogleUser("alice@example.com", false, "Alice", null);

		assertThatThrownBy(() -> service.login("good"))
				.isInstanceOfSatisfying(ApiException.class, e -> {
					assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
					assertThat(e.getCode()).isEqualTo("email_not_verified");
				});
		verify(users, never()).findByEmail(any());
	}

	@Test
	void invalidTokenIsRejected() {
		assertThatThrownBy(() -> service.login("garbage"))
				.isInstanceOfSatisfying(ApiException.class, e -> {
					assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
					assertThat(e.getCode()).isEqualTo("invalid_token");
				});
		verify(users, never()).findByEmail(any());
	}

}
