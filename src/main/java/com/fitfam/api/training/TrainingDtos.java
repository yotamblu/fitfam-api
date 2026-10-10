package com.fitfam.api.training;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class TrainingDtos {

	private TrainingDtos() {
	}

	// ---- exercise bank ----

	public record ExerciseRequest(
			@NotBlank @Size(max = 100) String nameHe,
			@NotBlank String sport,
			@NotBlank String measure,
			@Size(max = 20) List<@NotBlank @Size(max = 50) String> equipment,
			@Size(max = 20) List<@NotBlank @Size(max = 50) String> muscleGroups,
			@Size(max = 2000) String descriptionHe,
			@Size(max = 2000) String cuesHe,
			@Size(max = 300) String videoUrl) {
	}

	public record ExerciseDto(String id, String nameHe, String sport, String measure, List<String> equipment,
			List<String> muscleGroups, String descriptionHe, String cuesHe, String youtubeId, String videoUrl,
			boolean archived, Instant createdAt, Instant updatedAt) {
	}

	// ---- plan tree / level lists (admin) ----

	public record LevelSummaryDto(String id, int levelNumber, String slug, String nameHe, int workoutCount,
			int publishedCount, boolean hasChallenge) {
	}

	public record PlanTreeDto(String slug, String nameHe, List<LevelSummaryDto> levels) {
	}

	public record WorkoutSummaryDto(String id, int sortOrder, String type, String sport, String status, String titleHe,
			Integer estMinutes, int lineCount, Instant updatedAt) {
	}

	public record LevelWorkoutsDto(String levelId, String planSlug, String planNameHe, int levelNumber,
			String levelNameHe, List<WorkoutSummaryDto> workouts) {
	}

	// ---- workouts (admin) ----

	public record WorkoutRequest(
			@NotBlank String sport,
			@Size(max = 150) String titleHe,
			@Size(max = 2000) String descriptionHe,
			@Size(max = 1000) String goalHe,
			Integer estMinutes,
			String type,
			JsonNode content) {
	}

	public record WorkoutDto(String id, String levelId, int sortOrder, String type, String sport, String status,
			String titleHe, String descriptionHe, String goalHe, Integer estMinutes, JsonNode content,
			Instant updatedAt) {
	}

	public record OrderRequest(@NotEmpty List<@NotNull UUID> ids) {
	}

	public record DuplicateRequest(UUID levelId) {
	}

	// ---- customer ----

	public record RoadmapWorkoutDto(String id, int sortOrder, String type, String sport, String titleHe,
			Integer estMinutes, String state, boolean canAttempt) {
	}

	public record RoadmapLevelDto(String id, int levelNumber, String nameHe, String state,
			List<RoadmapWorkoutDto> workouts) {
	}

	public record RoadmapDto(String enrollmentId, String planSlug, String planNameHe, int currentLevelNumber,
			boolean planCompleted, List<RoadmapLevelDto> levels) {
	}

	public record CustomerWorkoutDto(String id, String type, String sport, String titleHe, String descriptionHe,
			String goalHe, Integer estMinutes, String state, int resumeStep, JsonNode content) {
	}

	public record ProgressRequest(@Min(0) @Max(10000) int resumeStep) {
	}

	public record MyPlanDto(String enrollmentId, String planSlug, String planNameHe, int levelNumber,
			String levelNameHe, String status) {
	}

	public record StepsDto(String workoutId, int resumeStep, List<StepExpander.Step> steps) {
	}

	public record AttemptRequest(@NotNull Boolean passed) {
	}

	public record AttemptResultDto(boolean passed, int currentLevelNumber, boolean levelChanged,
			boolean planCompleted) {
	}

}
