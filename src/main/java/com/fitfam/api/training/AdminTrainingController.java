package com.fitfam.api.training;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.fitfam.api.auth.AuthenticatedUser;
import com.fitfam.api.training.TrainingDtos.DuplicateRequest;
import com.fitfam.api.training.TrainingDtos.ExerciseDto;
import com.fitfam.api.training.TrainingDtos.ExerciseRequest;
import com.fitfam.api.training.TrainingDtos.LevelWorkoutsDto;
import com.fitfam.api.training.TrainingDtos.OrderRequest;
import com.fitfam.api.training.TrainingDtos.PlanTreeDto;
import com.fitfam.api.training.TrainingDtos.StepsDto;
import com.fitfam.api.training.TrainingDtos.WorkoutDto;
import com.fitfam.api.training.TrainingDtos.WorkoutRequest;

import jakarta.validation.Valid;

/** Coach endpoints for the exercise bank and the workouts of each plan level (admin only, via /admin/**). */
@RestController
public class AdminTrainingController {

	private final ExerciseService exercises;
	private final WorkoutAdminService workouts;
	private final StepExpander expander;

	public AdminTrainingController(ExerciseService exercises, WorkoutAdminService workouts, StepExpander expander) {
		this.exercises = exercises;
		this.workouts = workouts;
		this.expander = expander;
	}

	// ---- exercise bank ----

	@GetMapping("/admin/exercises")
	public List<ExerciseDto> exercises(@RequestParam(required = false) String sport,
			@RequestParam(required = false) String q, @RequestParam(defaultValue = "false") boolean archived) {
		return exercises.list(sport, q, archived);
	}

	@GetMapping("/admin/exercises/{id}")
	public ExerciseDto exercise(@PathVariable UUID id) {
		return exercises.get(id);
	}

	@PostMapping("/admin/exercises")
	@ResponseStatus(HttpStatus.CREATED)
	public ExerciseDto createExercise(@AuthenticationPrincipal AuthenticatedUser admin,
			@Valid @RequestBody ExerciseRequest request) {
		return exercises.create(admin, request);
	}

	@PutMapping("/admin/exercises/{id}")
	public ExerciseDto updateExercise(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable UUID id,
			@Valid @RequestBody ExerciseRequest request) {
		return exercises.update(admin, id, request);
	}

	@PostMapping("/admin/exercises/{id}/archive")
	public ExerciseDto archiveExercise(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable UUID id) {
		return exercises.setArchived(admin, id, true);
	}

	@PostMapping("/admin/exercises/{id}/restore")
	public ExerciseDto restoreExercise(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable UUID id) {
		return exercises.setArchived(admin, id, false);
	}

	// ---- plans, levels and their ordered workouts ----

	@GetMapping("/admin/training/plans")
	public List<PlanTreeDto> planTree() {
		return workouts.planTree();
	}

	@GetMapping("/admin/levels/{levelId}/workouts")
	public LevelWorkoutsDto levelWorkouts(@PathVariable UUID levelId) {
		return workouts.levelWorkouts(levelId);
	}

	@PostMapping("/admin/levels/{levelId}/workouts")
	@ResponseStatus(HttpStatus.CREATED)
	public WorkoutDto createWorkout(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable UUID levelId,
			@Valid @RequestBody WorkoutRequest request) {
		return workouts.create(admin, levelId, request);
	}

	@PutMapping("/admin/levels/{levelId}/workouts/order")
	public LevelWorkoutsDto reorder(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable UUID levelId,
			@Valid @RequestBody OrderRequest request) {
		return workouts.reorder(admin, levelId, request.ids());
	}

	// ---- one workout ----

	@GetMapping("/admin/workouts/{id}")
	public WorkoutDto workout(@PathVariable UUID id) {
		return workouts.get(id);
	}

	@PutMapping("/admin/workouts/{id}")
	public WorkoutDto updateWorkout(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable UUID id,
			@Valid @RequestBody WorkoutRequest request) {
		return workouts.update(admin, id, request);
	}

	@DeleteMapping("/admin/workouts/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteWorkout(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable UUID id) {
		workouts.delete(admin, id);
	}

	@PostMapping("/admin/workouts/{id}/publish")
	public WorkoutDto publish(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable UUID id) {
		return workouts.publish(admin, id);
	}

	@PostMapping("/admin/workouts/{id}/unpublish")
	public WorkoutDto unpublish(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable UUID id) {
		return workouts.unpublish(admin, id);
	}

	@PostMapping("/admin/workouts/{id}/archive")
	public WorkoutDto archive(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable UUID id) {
		return workouts.archive(admin, id);
	}

	@PostMapping("/admin/workouts/{id}/restore")
	public WorkoutDto restore(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable UUID id) {
		return workouts.restore(admin, id);
	}

	@PostMapping("/admin/workouts/{id}/duplicate")
	@ResponseStatus(HttpStatus.CREATED)
	public WorkoutDto duplicate(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable UUID id,
			@RequestBody(required = false) DuplicateRequest request) {
		return workouts.duplicate(admin, id, request == null ? null : request.levelId());
	}

	/** What the guided player would walk through, so coaches can check a workout before publishing it. */
	@GetMapping("/admin/workouts/{id}/steps")
	public StepsDto steps(@PathVariable UUID id) {
		WorkoutDto workout = workouts.get(id);
		return new StepsDto(workout.id(), 0, expander.expand(workout.content()));
	}

}
