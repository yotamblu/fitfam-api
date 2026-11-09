package com.fitfam.api.onboarding;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fitfam.api.auth.AuthenticatedUser;

/** The first-run tour: whether the signed-in user has seen (finished or skipped) it. Replaying it changes nothing. */
@RestController
public class OnboardingController {

	public record OnboardingDto(boolean done) {
	}

	private final JdbcTemplate jdbc;

	public OnboardingController(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@GetMapping("/me/onboarding")
	public OnboardingDto status(@AuthenticationPrincipal AuthenticatedUser user) {
		Boolean done = jdbc.queryForObject("select onboarded_at is not null from users where id = ?", Boolean.class,
				user.id());
		return new OnboardingDto(Boolean.TRUE.equals(done));
	}

	/** Keeps the first completion time; calling it again changes nothing. */
	@PostMapping("/me/onboarding/complete")
	public OnboardingDto complete(@AuthenticationPrincipal AuthenticatedUser user) {
		jdbc.update("update users set onboarded_at = now() where id = ? and onboarded_at is null", user.id());
		return new OnboardingDto(true);
	}
}
