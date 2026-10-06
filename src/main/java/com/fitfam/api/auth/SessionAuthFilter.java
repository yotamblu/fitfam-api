package com.fitfam.api.auth;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.WebUtils;

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
 */
public class SessionAuthFilter extends OncePerRequestFilter {

	private final SessionTokenService tokens;
	private final UserRepository users;

	public SessionAuthFilter(SessionTokenService tokens, UserRepository users) {
		this.tokens = tokens;
		this.users = users;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Cookie cookie = WebUtils.getCookie(request, SessionCookies.NAME);
		if (cookie != null && !cookie.getValue().isEmpty()) {
			tokens.parse(cookie.getValue())
					.flatMap(users::findById)
					.ifPresent(user -> {
						var authority = new SimpleGrantedAuthority("ROLE_" + user.getRole().toUpperCase());
						var authentication = new UsernamePasswordAuthenticationToken(AuthenticatedUser.of(user), null,
								List.of(authority));
						SecurityContextHolder.getContext().setAuthentication(authentication);
					});
		}
		chain.doFilter(request, response);
	}

}
