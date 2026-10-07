package com.fitfam.api.admin;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;

public final class AdminDtos {

	private AdminDtos() {
	}

	public record AddCustomerRequest(
			@NotBlank @Pattern(regexp = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$") String email,
			@NotEmpty List<@NotBlank String> planSlugs) {
	}

	public record LevelDto(int levelNumber, String slug, String nameHe) {
	}

	public record PlanDto(String slug, String nameHe, List<LevelDto> levels) {
	}

	public record EnrollmentDto(String planSlug, String planNameHe, int levelNumber, String levelSlug,
			String levelNameHe, String status) {
	}

	/** One waitlist signup. Only what admins need: the hashed IP and user agent stored with it are never exposed. */
	public record WaitlistEntryDto(String id, String email, String favoriteSport, Instant createdAt,
			boolean alreadyUser) {
	}

	public record WaitlistPageDto(long total, int page, int size, List<WaitlistEntryDto> items) {
	}

	public record CustomerDto(String id, String email, String role, String status, String displayName,
			Instant firstLoginAt, List<EnrollmentDto> enrollments) {
	}

}
