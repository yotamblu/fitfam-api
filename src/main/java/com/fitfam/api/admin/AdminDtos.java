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

	public record CustomerDto(String id, String email, String role, String status, String displayName,
			Instant firstLoginAt, List<EnrollmentDto> enrollments) {
	}

}
