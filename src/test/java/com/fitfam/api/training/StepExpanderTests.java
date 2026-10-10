package com.fitfam.api.training;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fitfam.api.training.ExerciseService.ExerciseInfo;
import com.fitfam.api.training.StepExpander.Step;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class StepExpanderTests {

	private final ObjectMapper mapper = JsonMapper.builder().build();
	private final StepExpander expander = new StepExpander(mock(ExerciseService.class));

	private final UUID pushUp = UUID.randomUUID();
	private final UUID run = UUID.randomUUID();
	private final Map<UUID, ExerciseInfo> known = Map.of(
			pushUp, new ExerciseInfo(pushUp, "push", "calisthenics", "reps", false, "dQw4w9WgXcQ", null, "cue"),
			run, new ExerciseInfo(run, "run", "running", "distance", false, null, null, null));

	private List<Step> expand(String json) {
		return expander.expand(mapper.readTree(json), known);
	}

	private String block(String style, String extra, String... lines) {
		return "{\"sections\":[{\"title\":\"main\",\"blocks\":[{\"style\":\"" + style + "\"" + extra + ",\"lines\":["
				+ String.join(",", lines) + "]}]}]}";
	}

	private String repsLine(UUID id, int sets, int reps, int rest) {
		return "{\"exerciseId\":\"" + id + "\",\"sets\":" + sets + ",\"reps\":" + reps + ",\"restSec\":" + rest + "}";
	}

	@Test
	void straightSetsAlternateWorkAndRestAndTheFinalRestIsDropped() {
		List<Step> steps = expand(block("straight", "", repsLine(pushUp, 3, 10, 60)));
		assertThat(steps).extracting(Step::type).containsExactly("work", "rest", "work", "rest", "work");
		assertThat(steps).extracting(Step::index).containsExactly(0, 1, 2, 3, 4);
		assertThat(steps.get(0).setNumber()).isEqualTo(1);
		assertThat(steps.get(0).setCount()).isEqualTo(3);
		assertThat(steps.get(0).target().reps()).isEqualTo(10);
		assertThat(steps.get(0).target().exercise().youtubeId()).isEqualTo("dQw4w9WgXcQ");
		assertThat(steps.get(1).durationSec()).isEqualTo(60);
		assertThat(steps.get(0).sectionTitle()).isEqualTo("main");
	}

	@Test
	void restBetweenLinesIsKeptButNotAfterTheLastStepOfTheWorkout() {
		List<Step> steps = expand(block("straight", "", repsLine(pushUp, 1, 10, 30), repsLine(pushUp, 1, 8, 45)));
		assertThat(steps).extracting(Step::type).containsExactly("work", "rest", "work");
		assertThat(steps.get(1).durationSec()).isEqualTo(30);
	}

	@Test
	void distanceLinesRepeatByTheirRepeatCountAndKeepZoneAndRpe() {
		String line = "{\"exerciseId\":\"" + run + "\",\"reps\":4,\"distanceM\":400,\"zone\":4,\"rpe\":8,\"restSec\":90}";
		List<Step> steps = expand(block("straight", "", line));
		assertThat(steps).hasSize(7);
		Step first = steps.get(0);
		assertThat(first.setCount()).isEqualTo(4);
		assertThat(first.target().distanceM()).isEqualTo(400);
		assertThat(first.target().zone()).isEqualTo(4);
		assertThat(first.target().rpe()).isEqualTo(8);
		// reps on a distance line is the repeat count, not a rep target
		assertThat(first.target().reps()).isNull();
	}

	@Test
	void circuitsGoRoundByRoundWithBlockRests() {
		List<Step> steps = expand(block("circuit",
				",\"rounds\":2,\"restBetweenLinesSec\":10,\"restBetweenRoundsSec\":60",
				repsLine(pushUp, 1, 10, 0), repsLine(pushUp, 1, 8, 0)));
		assertThat(steps).extracting(Step::type).containsExactly("work", "rest", "work", "rest", "work", "rest", "work");
		assertThat(steps).extracting(Step::durationSec).containsExactly(null, 10, null, 60, null, 10, null);
		assertThat(steps.get(0).roundNumber()).isEqualTo(1);
		assertThat(steps.get(6).roundNumber()).isEqualTo(2);
		assertThat(steps.get(6).roundCount()).isEqualTo(2);
	}

	@Test
	void timedBlocksAreASingleStepListingTheirLines() {
		List<Step> amrap = expand(block("amrap", ",\"durationSec\":600", repsLine(pushUp, 1, 10, 0),
				repsLine(pushUp, 1, 5, 0)));
		assertThat(amrap).hasSize(1);
		assertThat(amrap.get(0).type()).isEqualTo("timed_block");
		assertThat(amrap.get(0).blockStyle()).isEqualTo("amrap");
		assertThat(amrap.get(0).durationSec()).isEqualTo(600);
		assertThat(amrap.get(0).targets()).hasSize(2);

		Step emom = expand(block("emom", ",\"durationSec\":480", repsLine(pushUp, 1, 10, 0))).get(0);
		assertThat(emom.intervalSec()).isEqualTo(60);

		Step forTime = expand(block("for_time", ",\"rounds\":3,\"capSec\":900", repsLine(pushUp, 1, 10, 0))).get(0);
		assertThat(forTime.roundCount()).isEqualTo(3);
		assertThat(forTime.capSec()).isEqualTo(900);
	}

	@Test
	void emptyWorkoutHasNoSteps() {
		assertThat(expand("{\"sections\":[]}")).isEmpty();
	}

}
