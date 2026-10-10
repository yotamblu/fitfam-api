package com.fitfam.api.training;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.fitfam.api.auth.AuthenticatedUser;
import com.fitfam.api.training.TrainingDtos.AttemptRequest;
import com.fitfam.api.training.TrainingDtos.AttemptResultDto;
import com.fitfam.api.training.TrainingDtos.CustomerWorkoutDto;
import com.fitfam.api.training.TrainingDtos.MyPlanDto;
import com.fitfam.api.training.TrainingDtos.ProgressRequest;
import com.fitfam.api.training.TrainingDtos.RoadmapDto;
import com.fitfam.api.training.TrainingDtos.StepsDto;

import jakarta.validation.Valid;

/** What a logged-in customer does: see the roadmap, open and play workouts, finish them, attempt challenges. */
@RestController
public class TrainingController {

	private final ProgressService progress;
	private final StepExpander expander;

	public TrainingController(ProgressService progress, StepExpander expander) {
		this.progress = progress;
		this.expander = expander;
	}

	@GetMapping("/me/plans")
	public List<MyPlanDto> myPlans(@AuthenticationPrincipal AuthenticatedUser user) {
		return progress.myPlans(user.id());
	}

	@GetMapping("/me/plans/{planSlug}/roadmap")
	public RoadmapDto roadmap(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String planSlug) {
		return progress.roadmap(user.id(), planSlug);
	}

	@GetMapping("/workouts/{id}")
	public CustomerWorkoutDto workout(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
		return progress.openWorkout(user.id(), id);
	}

	/** The flat step list for the optional guided mode. */
	@GetMapping("/workouts/{id}/steps")
	public StepsDto steps(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
		CustomerWorkoutDto workout = progress.openWorkout(user.id(), id);
		return new StepsDto(workout.id(), workout.resumeStep(), expander.expand(workout.content()));
	}

	@PutMapping("/workouts/{id}/progress")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void saveProgress(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
			@Valid @RequestBody ProgressRequest request) {
		progress.saveProgress(user.id(), id, request.resumeStep());
	}

	@PostMapping("/workouts/{id}/complete")
	public RoadmapDto complete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
		return progress.complete(user.id(), id);
	}

	@PostMapping("/challenges/{workoutId}/attempt")
	public AttemptResultDto attempt(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID workoutId,
			@Valid @RequestBody AttemptRequest request) {
		return progress.attemptChallenge(user.id(), workoutId, request.passed());
	}

}
