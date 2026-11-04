package com.fitfam.api.training;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class WorkoutLogServiceTests {

	@Test
	void collectsEveryExerciseUsedByTheWorkoutOnce() throws Exception {
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		String json = """
				{"sections":[{"blocks":[{"style":"straight","lines":[{"exerciseId":"%s"},{"exerciseId":"%s"}]},
				{"style":"straight","lines":[{"exerciseId":"%s"}]}]}]}
				""".formatted(a, b, a);
		assertThat(WorkoutLogService.exerciseIds(new ObjectMapper().readTree(json))).containsExactly(a, b);
	}

	@Test
	void enduranceLinesHaveNoExerciseToLog() throws Exception {
		String json = "{\"sections\":[{\"blocks\":[{\"style\":\"endurance\",\"lines\":[{\"activity\":\"run\"}]}]}]}";
		assertThat(WorkoutLogService.exerciseIds(new ObjectMapper().readTree(json))).isEmpty();
	}
}
