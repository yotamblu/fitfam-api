package com.fitfam.api.auth;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

@RestController
public class AuthController {

	public record GoogleLoginRequest(@NotBlank String credential) {
	}

	public record CurrentUserResponse(String id, String email, String role, String displayName, String avatarUrl) {

		static CurrentUserResponse of(AuthenticatedUser user) {
			return new CurrentUserResponse(user.id().toString(), user.email(), user.role(), user.displayName(),
					user.avatarUrl());
		}
	}

	private final AuthService authService;
	private final SessionCookies cookies;

	public AuthController(AuthService authService, SessionCookies cookies) {
		this.authService = authService;
		this.cookies = cookies;
	}

	@PostMapping("/auth/google")
	public ResponseEntity<CurrentUserResponse> google(@Valid @RequestBody GoogleLoginRequest request) {
		AuthService.LoginResult result = authService.login(request.credential());
		return ResponseEntity.ok()
				.header(HttpHeaders.SET_COOKIE, cookies.create(result.token(), result.lifetime()).toString())
				.body(CurrentUserResponse.of(AuthenticatedUser.of(result.user())));
	}

	@GetMapping("/auth/me")
	public CurrentUserResponse me(@AuthenticationPrincipal AuthenticatedUser user) {
		return CurrentUserResponse.of(user);
	}

	@PostMapping("/auth/logout")
	public ResponseEntity<Void> logout() {
		return ResponseEntity.noContent()
				.header(HttpHeaders.SET_COOKIE, cookies.clear().toString())
				.build();
	}

}
