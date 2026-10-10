package com.fitfam.api.training;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fitfam.api.training.ProgressService.Computed;
import com.fitfam.api.training.ProgressService.WorkoutRow;
import com.fitfam.api.training.TrainingDtos.RoadmapLevelDto;
import com.fitfam.api.training.TrainingDtos.RoadmapWorkoutDto;

/** The sequencing, skipping and challenge rules, without a database. Three levels with two workouts and a challenge. */
class RoadmapRulesTests {

	private final Map<Integer, String[]> levels = new LinkedHashMap<>();
	private final List<WorkoutRow> workouts = new ArrayList<>();
	private final Map<String, UUID> ids = new LinkedHashMap<>();

	@BeforeEach
	void setUp() {
		for (int level = 1; level <= 3; level++) {
			UUID levelId = UUID.randomUUID();
			levels.put(level, new String[] { levelId.toString(), "level " + level });
			add(levelId, level, 1, "w" + level + "a", Vocabulary.TYPE_REGULAR);
			add(levelId, level, 2, "w" + level + "b", Vocabulary.TYPE_REGULAR);
			add(levelId, level, 3, "c" + level, Vocabulary.TYPE_CHALLENGE);
		}
	}

	private void add(UUID levelId, int level, int order, String name, String type) {
		UUID id = UUID.randomUUID();
		ids.put(name, id);
		workouts.add(new WorkoutRow(id, levelId, level, order, type, "gym", name, 30));
	}

	private Set<UUID> done(String... names) {
		Set<UUID> done = new HashSet<>();
		for (String name : names) {
			done.add(ids.get(name));
		}
		return done;
	}

	private RoadmapWorkoutDto find(Computed computed, String name) {
		return computed.levels().stream().flatMap(l -> l.workouts().stream())
				.filter(w -> w.id().equals(ids.get(name).toString())).findFirst().orElseThrow();
	}

	private RoadmapLevelDto level(Computed computed, int number) {
		return computed.levels().get(number - 1);
	}

	@Test
	void aNewCustomerDoesTheCurrentLevelStrictlyInOrder() {
		Computed c = ProgressService.compute(levels, workouts, done(), 1);
		assertThat(find(c, "w1a").state()).isEqualTo("current");
		assertThat(find(c, "w1b").state()).isEqualTo("locked");
		assertThat(find(c, "c1").state()).isEqualTo("locked");
		assertThat(find(c, "w2a").state()).isEqualTo("locked");
		assertThat(level(c, 1).state()).isEqualTo("current");
		assertThat(level(c, 2).state()).isEqualTo("locked");
	}

	@Test
	void finishingWorkoutsAdvancesTheCurrentOne() {
		Computed c = ProgressService.compute(levels, workouts, done("w1a"), 1);
		assertThat(find(c, "w1a").state()).isEqualTo("done");
		assertThat(find(c, "w1b").state()).isEqualTo("current");
	}

	@Test
	void theCurrentChallengeCanBeAttemptedOnlyAfterEverythingBeforeIt() {
		assertThat(find(ProgressService.compute(levels, workouts, done("w1a"), 1), "c1").canAttempt()).isFalse();
		Computed c = ProgressService.compute(levels, workouts, done("w1a", "w1b"), 1);
		assertThat(find(c, "c1").state()).isEqualTo("current");
		assertThat(find(c, "c1").canAttempt()).isTrue();
	}

	@Test
	void higherChallengesCanBeAttemptedToSkipAheadExceptTheTopOne() {
		Computed c = ProgressService.compute(levels, workouts, done(), 1);
		assertThat(find(c, "c2").canAttempt()).isTrue();
		assertThat(find(c, "c3").canAttempt()).isFalse();
		assertThat(find(c, "w2a").canAttempt()).isFalse();
	}

	@Test
	void startingHigherMarksTheLevelsBelowAsSkippedAndNeverAsDone() {
		Computed c = ProgressService.compute(levels, workouts, done("c2"), 3);
		assertThat(level(c, 1).state()).isEqualTo("skipped");
		assertThat(find(c, "w1a").state()).isEqualTo("skipped");
		assertThat(find(c, "c1").canAttempt()).isFalse();
		assertThat(find(c, "w3a").state()).isEqualTo("current");
		assertThat(c.planCompleted()).isFalse();
	}

	@Test
	void completedLevelsShowAsDoneAndTheTopChallengeCompletesThePlan() {
		Computed c = ProgressService.compute(levels, workouts,
				done("w1a", "w1b", "c1", "w2a", "w2b", "c2", "w3a", "w3b"), 3);
		assertThat(level(c, 1).state()).isEqualTo("done");
		assertThat(level(c, 2).state()).isEqualTo("done");
		assertThat(find(c, "c3").canAttempt()).isTrue();
		assertThat(c.planCompleted()).isFalse();

		Computed finished = ProgressService.compute(levels, workouts,
				done("w1a", "w1b", "c1", "w2a", "w2b", "c2", "w3a", "w3b", "c3"), 3);
		assertThat(finished.planCompleted()).isTrue();
		assertThat(find(finished, "c3").canAttempt()).isFalse();
	}

	@Test
	void aDemotedCustomerKeepsTheirDoneWorkoutsAndLosesAccessToUnfinishedHigherOnes() {
		Computed c = ProgressService.compute(levels, workouts, done("w1a", "w1b", "c1", "w2a"), 1);
		assertThat(find(c, "w2a").state()).isEqualTo("done");
		assertThat(find(c, "w2b").state()).isEqualTo("locked");
		assertThat(find(c, "w1a").state()).isEqualTo("done");
	}

}
