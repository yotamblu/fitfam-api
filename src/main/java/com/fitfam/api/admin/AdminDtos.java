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

	/** Totals over the whole waitlist (not just the current filter). {@code bySport} keys are sport ids or "none". */
	public record WaitlistSummaryDto(long total, long alreadyUsers, java.util.Map<String, Long> bySport) {
	}

	/** {@code total} is the number of rows matching the current search/filter, for paging. */
	public record WaitlistPageDto(long total, int page, int size, List<WaitlistEntryDto> items,
			WaitlistSummaryDto summary) {
	}

	public record CustomerDto(String id, String email, String role, String status, String displayName,
			Instant firstLoginAt, List<EnrollmentDto> enrollments) {
	}

}
