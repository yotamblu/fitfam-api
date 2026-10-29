package com.fitfam.api.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import com.fitfam.api.config.AppProperties;
import com.fitfam.api.domain.User;
import com.fitfam.api.domain.UserRepository;

import jakarta.servlet.http.Cookie;

class SessionAuthFilterTests {

	private final UserRepository users = mock(UserRepository.class);
	private SessionTokenService tokens;
	private SessionAuthFilter filter;

	@BeforeEach
	void setUp() {
		AppProperties props = new AppProperties(new AppProperties.Google("x"),
				new AppProperties.Jwt("a-test-secret-that-is-long-enough-for-hs256"), new AppProperties.Session(30, 12),
				new AppProperties.Cookie(false), new AppProperties.Cors(List.of()));
		tokens = new SessionTokenService(props);
		filter = new SessionAuthFilter(tokens, users, new SessionCookies(props));
	}

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
	}

	private User user(String role) {
		User user = new User("someone@example.com", role, User.STATUS_ACTIVE);
		ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
		when(users.findById(user.getId())).thenReturn(Optional.of(user));
		return user;
	}

	private MockHttpServletResponse call(User user, Duration age, String uri) throws Exception {
		// a token issued `age` ago that is still valid
		String token = tokens.issueAt(user.getId(), Instant.now().minus(age), Duration.ofDays(30));
		MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
		request.setCookies(new Cookie(SessionCookies.NAME, token));
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(request, response, new MockFilterChain());
		return response;
	}

	@Test
	void oldCustomerCookieIsReissuedWithAFullLifetime() throws Exception {
		MockHttpServletResponse response = call(user(User.ROLE_CUSTOMER), Duration.ofDays(3), "/me/stats");
		String header = response.getHeader(HttpHeaders.SET_COOKIE);
		assertThat(header).contains(SessionCookies.NAME + "=").contains("HttpOnly").contains("Max-Age=2592000");
	}

	@Test
	void freshCustomerCookieIsLeftAlone() throws Exception {
		assertThat(call(user(User.ROLE_CUSTOMER), Duration.ofHours(2), "/me/stats").getHeader(HttpHeaders.SET_COOKIE))
				.isNull();
	}

	@Test
	void adminCookieNeverSlides() throws Exception {
		assertThat(call(user(User.ROLE_ADMIN), Duration.ofDays(3), "/admin/users").getHeader(HttpHeaders.SET_COOKIE))
				.isNull();
	}

	@Test
	void logoutIsNotRenewed() throws Exception {
		assertThat(call(user(User.ROLE_CUSTOMER), Duration.ofDays(3), "/auth/logout").getHeader(HttpHeaders.SET_COOKIE))
				.isNull();
	}

}
