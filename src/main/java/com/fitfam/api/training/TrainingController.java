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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.fitfam.api.auth.AuthenticatedUser;
import com.fitfam.api.legal.ConsentService;
import com.fitfam.api.training.TrainingDtos.AttemptRequest;
import com.fitfam.api.training.TrainingDtos.AttemptResultDto;
import com.fitfam.api.training.TrainingDtos.CustomerExerciseDto;
import com.fitfam.api.training.TrainingDtos.CustomerWorkoutDto;
import com.fitfam.api.training.TrainingDtos.ExerciseDto;
import com.fitfam.api.training.TrainingDtos.MyPlanDto;
import com.fitfam.api.training.TrainingDtos.PaceRequest;
import com.fitfam.api.training.TrainingDtos.ProgressRequest;
import com.fitfam.api.training.TrainingDtos.RoadmapDto;
import com.fitfam.api.training.TrainingDtos.StepsDto;

import jakarta.validation.Valid;

/** What a logged-in customer does: see the roadmap, open and play workouts, finish them, attempt challenges. */
@RestController
public class TrainingController {

	private final ProgressService progress;
	private final StepExpander expander;
	private final StatsService stats;
	private final ExerciseService exercises;
	private final PaceService paces;
	private final ConsentService consents;

	public TrainingController(ProgressService progress, StepExpander expander, StatsService stats,
			ExerciseService exercises, PaceService paces, ConsentService consents) {
		this.paces = paces;
		this.consents = consents;
		this.progress = progress;
		this.expander = expander;
		this.stats = stats;
		this.exercises = exercises;
	}

	/** One exercise explained: description, cues, muscles, equipment and the optional video. Any signed-in customer may read it. */
	@GetMapping("/exercises/{id}")
	public CustomerExerciseDto exercise(@PathVariable UUID id) {
		ExerciseDto e = exercises.get(id);
		return new CustomerExerciseDto(e.id(), e.nameHe(), e.sport(), e.measure(), e.equipment(), e.muscleGroups(),
				e.descriptionHe(), e.cuesHe(), e.youtubeId());
	}

	/** Points, account level and weekly streak, derived from finished workouts. */
	@GetMapping("/me/stats")
	public StatsService.StatsDto myStats(@AuthenticationPrincipal AuthenticatedUser user) {
		return stats.stats(user.id());
	}

	/** The customer's 5 km time and the personal paces computed from it. */
	@GetMapping("/me/pace")
	public PaceService.PaceStatus myPace(@AuthenticationPrincipal AuthenticatedUser user) {
		return paces.status(user.id());
	}

	@PutMapping("/me/pace")
	public PaceService.PaceStatus setPace(@AuthenticationPrincipal AuthenticatedUser user,
			@Valid @RequestBody PaceRequest request) {
		return paces.set(user.id(), request.fiveKSec());
	}

	@DeleteMapping("/me/pace")
	public PaceService.PaceStatus clearPace(@AuthenticationPrincipal AuthenticatedUser user) {
		return paces.clear(user.id());
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
		consents.requireCurrent(user);
		CustomerWorkoutDto workout = progress.openWorkout(user.id(), id);
		return new StepsDto(workout.id(), workout.resumeStep(), expander.expand(workout.content(), paces.fiveKSec(user.id())));
	}

	@PutMapping("/workouts/{id}/progress")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void saveProgress(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
			@Valid @RequestBody ProgressRequest request) {
		consents.requireCurrent(user);
		progress.saveProgress(user.id(), id, request.resumeStep());
	}

	@PostMapping("/workouts/{id}/complete")
	public RoadmapDto complete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
		consents.requireCurrent(user);
		return progress.complete(user.id(), id);
	}

	@PostMapping("/challenges/{workoutId}/attempt")
	public AttemptResultDto attempt(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID workoutId,
			@Valid @RequestBody AttemptRequest request) {
		consents.requireCurrent(user);
		return progress.attemptChallenge(user.id(), workoutId, request.passed());
	}

}
