package com.fitfam.api.auth;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.WebUtils;

import com.fitfam.api.domain.User;
import com.fitfam.api.domain.UserRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Authenticates a request from the session cookie. The user is loaded from the database on every request, so removing
 * or demoting someone takes effect immediately. No valid cookie simply means "anonymous"; the security rules decide
 * what that may access.
 *
 * <p>Customer sessions slide: when a valid cookie is more than a day old, the response carries a fresh one with a new full
 * lifetime, so someone who keeps using the app is never signed out. Admin sessions never slide (they are deliberately
 * short), and logout is left alone.
 */
public class SessionAuthFilter extends OncePerRequestFilter {

	/** A cookie younger than this is not re-issued, so most requests carry no Set-Cookie. */
	static final Duration RENEW_AFTER = Duration.ofDays(1);

	private final SessionTokenService tokens;
	private final UserRepository users;
	private final SessionCookies cookies;

	public SessionAuthFilter(SessionTokenService tokens, UserRepository users, SessionCookies cookies) {
		this.tokens = tokens;
		this.users = users;
		this.cookies = cookies;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Cookie cookie = WebUtils.getCookie(request, SessionCookies.NAME);
		if (cookie != null && !cookie.getValue().isEmpty()) {
			tokens.parseWithIssuedAt(cookie.getValue()).ifPresent(parsed -> users.findById(parsed.userId()).ifPresent(user -> {
				var authority = new SimpleGrantedAuthority("ROLE_" + user.getRole().toUpperCase());
				var authentication = new UsernamePasswordAuthenticationToken(AuthenticatedUser.of(user), null,
						List.of(authority));
				SecurityContextHolder.getContext().setAuthentication(authentication);
				if (shouldRenew(request, user, parsed.issuedAt())) {
					Duration lifetime = tokens.lifetimeFor(user.getRole());
					response.addHeader(HttpHeaders.SET_COOKIE,
							cookies.create(tokens.issue(user.getId(), lifetime), lifetime).toString());
				}
			}));
		}
		chain.doFilter(request, response);
	}

	private static boolean shouldRenew(HttpServletRequest request, User user, Instant issuedAt) {
		return !User.ROLE_ADMIN.equals(user.getRole()) && !request.getRequestURI().equals("/auth/logout")
				&& issuedAt.isBefore(Instant.now().minus(RENEW_AFTER));
	}

}
