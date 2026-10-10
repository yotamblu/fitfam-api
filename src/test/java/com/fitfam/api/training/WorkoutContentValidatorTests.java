package com.fitfam.api.training;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fitfam.api.training.ExerciseService.ExerciseInfo;
import com.fitfam.api.web.ApiException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class WorkoutContentValidatorTests {

	private final ObjectMapper mapper = JsonMapper.builder().build();

	private final UUID repsId = UUID.randomUUID();
	private final UUID distanceId = UUID.randomUUID();
	private final UUID calId = UUID.randomUUID();
	private final UUID archivedId = UUID.randomUUID();

	private WorkoutContentValidator validator;

	@BeforeEach
	void setUp() {
		ExerciseService exercises = mock(ExerciseService.class);
		when(exercises.lookup(anySet())).thenReturn(Map.of(
				repsId, info(repsId, "reps", false),
				distanceId, info(distanceId, "distance", false),
				calId, info(calId, "calories", false),
				archivedId, info(archivedId, "reps", true)));
		validator = new WorkoutContentValidator(exercises, mapper);
	}

	private static ExerciseInfo info(UUID id, String measure, boolean archived) {
		return new ExerciseInfo(id, "name", "gym", measure, archived, null, null, null);
	}

	private JsonNode json(String text) {
		return mapper.readTree(text);
	}

	private String content(String style, String blockExtra, String line) {
		return "{\"sections\":[{\"title\":\"main\",\"blocks\":[{\"style\":\"" + style + "\"" + blockExtra
				+ ",\"lines\":[" + line + "]}]}]}";
	}

	private String repsLine(String extra) {
		return "{\"exerciseId\":\"" + repsId + "\",\"sets\":3,\"reps\":10,\"restSec\":60" + extra + "}";
	}

	private void assertInvalid(String content, boolean publish, String detail) {
		assertThatThrownBy(() -> validator.validate(json(content), publish)).isInstanceOfSatisfying(
				ApiException.class, e -> {
					assertThat(e.getCode()).isEqualTo("invalid_content");
					assertThat(e.getDetail()).isEqualTo(detail);
				});
	}

	@Test
	void validContentIsKeptAndIdsAreFilledIn() {
		JsonNode out = validator.validate(json(content("straight", "", repsLine(",\"loadHe\":\"medium\",\"rpe\":7"))),
				true);
		JsonNode line = out.at("/sections/0/blocks/0/lines/0");
		assertThat(line.get("reps").intValue()).isEqualTo(10);
		assertThat(line.get("rpe").intValue()).isEqualTo(7);
		assertThat(line.get("id").stringValue()).hasSize(36);
		assertThat(out.at("/sections/0/id").stringValue()).hasSize(36);
		assertThat(WorkoutContentValidator.countLines(out)).isEqualTo(1);
	}

	@Test
	void emptyContentIsFineForADraftButNotForPublishing() {
		assertThat(validator.validate(null, false).get("sections")).isEmpty();
		assertThat(validator.validate(json("{}"), false).get("sections")).isEmpty();
		assertThatThrownBy(() -> validator.validate(null, true)).isInstanceOf(ApiException.class);
		assertThatThrownBy(() -> validator.validate(json("{\"sections\":[]}"), true))
				.isInstanceOf(ApiException.class);
	}

	@Test
	void unknownFieldsAreRejected() {
		assertInvalid("{\"sections\":[],\"extra\":1}", false, "extra:not_allowed");
		assertInvalid(content("straight", "", repsLine(",\"pace\":\"5:30\"")), false,
				"sections[0].blocks[0].lines[0].pace:not_allowed");
	}

	@Test
	void fieldsMustFitHowTheExerciseIsMeasured() {
		String calLine = "{\"exerciseId\":\"" + calId + "\",\"calories\":15,\"reps\":5}";
		assertInvalid(content("straight", "", calLine), false, "sections[0].blocks[0].lines[0].reps:not_allowed");
		String distanceNoDistance = "{\"exerciseId\":\"" + distanceId + "\",\"reps\":4}";
		assertInvalid(content("straight", "", distanceNoDistance), false,
				"sections[0].blocks[0].lines[0].distanceM:required");
		String repsNoReps = "{\"exerciseId\":\"" + repsId + "\",\"sets\":3}";
		assertInvalid(content("straight", "", repsNoReps), false, "sections[0].blocks[0].lines[0].reps:required");
	}

	@Test
	void distanceLinesTakeZoneRpeAndRepeatCount() {
		String run = "{\"exerciseId\":\"" + distanceId + "\",\"reps\":6,\"distanceM\":400,\"zone\":4,\"rpe\":8,\"restSec\":90}";
		JsonNode out = validator.validate(json(content("straight", "", run)), true);
		JsonNode line = out.at("/sections/0/blocks/0/lines/0");
		assertThat(line.get("zone").intValue()).isEqualTo(4);
		assertThat(line.get("rpe").intValue()).isEqualTo(8);
		assertThat(line.get("distanceM").intValue()).isEqualTo(400);
	}

	@Test
	void enduranceBlocksTakePlainRunAndSwimLinesWithoutAnExercise() {
		String run = "{\"activity\":\"run\",\"reps\":5,\"durationSec\":180,\"zone\":4,\"rpe\":8,\"restSec\":120}";
		JsonNode out = validator.validate(json(content("endurance", "", run)), true);
		JsonNode line = out.at("/sections/0/blocks/0/lines/0");
		assertThat(line.get("activity").stringValue()).isEqualTo("run");
		assertThat(line.get("zone").intValue()).isEqualTo(4);
		assertThat(line.has("exerciseId")).isFalse();
		assertThat(WorkoutContentValidator.countLines(out)).isEqualTo(1);
		String swim = "{\"activity\":\"swim\",\"distanceM\":1500,\"zone\":2}";
		assertThat(validator.validate(json(content("endurance", "", swim)), true)).isNotNull();
	}

	@Test
	void enduranceLinesNeedAnActivityAndExactlyOneOfDistanceOrDuration() {
		assertInvalid(content("endurance", "", "{\"distanceM\":400}"), false,
				"sections[0].blocks[0].lines[0].activity:required");
		assertInvalid(content("endurance", "", "{\"activity\":\"bike\",\"distanceM\":400}"), false,
				"sections[0].blocks[0].lines[0].activity:invalid");
		assertInvalid(content("endurance", "", "{\"activity\":\"run\"}"), false,
				"sections[0].blocks[0].lines[0].distanceM:required");
		assertInvalid(content("endurance", "", "{\"activity\":\"run\",\"distanceM\":400,\"durationSec\":60}"), false,
				"sections[0].blocks[0].lines[0].durationSec:not_allowed_with_distance");
		assertInvalid(content("endurance", "", "{\"activity\":\"run\",\"distanceM\":400,\"zone\":6}"), false,
				"sections[0].blocks[0].lines[0].zone:out_of_range");
		assertInvalid(content("endurance", "", "{\"exerciseId\":\"" + repsId + "\",\"activity\":\"run\",\"distanceM\":400}"),
				false, "sections[0].blocks[0].lines[0].exerciseId:not_allowed");
		assertInvalid(content("straight", "", "{\"activity\":\"run\",\"distanceM\":400}"), false,
				"sections[0].blocks[0].lines[0].exerciseId:invalid");
	}

	@Test
	void zoneAndRpeStayInTheirScales() {
		String zone6 = "{\"exerciseId\":\"" + distanceId + "\",\"distanceM\":400,\"zone\":6}";
		assertInvalid(content("straight", "", zone6), false, "sections[0].blocks[0].lines[0].zone:out_of_range");
		String rpe11 = "{\"exerciseId\":\"" + distanceId + "\",\"distanceM\":400,\"rpe\":11}";
		assertInvalid(content("straight", "", rpe11), false, "sections[0].blocks[0].lines[0].rpe:out_of_range");
		String zone0 = "{\"exerciseId\":\"" + distanceId + "\",\"distanceM\":400,\"zone\":0}";
		assertInvalid(content("straight", "", zone0), false, "sections[0].blocks[0].lines[0].zone:out_of_range");
	}

	@Test
	void numbersMustBeIntegersInRange() {
		assertInvalid(content("straight", "", repsLine(",\"rpe\":\"7\"")), false,
				"sections[0].blocks[0].lines[0].rpe:integer_expected");
		String tooMany = "{\"exerciseId\":\"" + repsId + "\",\"reps\":5000}";
		assertInvalid(content("straight", "", tooMany), false, "sections[0].blocks[0].lines[0].reps:out_of_range");
		String range = "{\"exerciseId\":\"" + repsId + "\",\"reps\":10,\"repsMax\":8}";
		assertInvalid(content("straight", "", range), false, "sections[0].blocks[0].lines[0].repsMax:below_reps");
	}

	@Test
	void exercisesMustExist() {
		String ghost = "{\"exerciseId\":\"" + UUID.randomUUID() + "\",\"reps\":5}";
		assertInvalid(content("straight", "", ghost), false, "sections[0].blocks[0].lines[0].exerciseId:not_found");
		assertInvalid(content("straight", "", "{\"exerciseId\":\"nope\",\"reps\":5}"), false,
				"sections[0].blocks[0].lines[0].exerciseId:invalid");
	}

	@Test
	void archivedExercisesAreOnlyRefusedWhenPublishing() {
		String line = "{\"exerciseId\":\"" + archivedId + "\",\"reps\":5}";
		assertThat(validator.validate(json(content("straight", "", line)), false)).isNotNull();
		assertInvalid(content("straight", "", line), true, "sections[0].blocks[0].lines[0].exerciseId:archived");
	}

	@Test
	void blockStylesHaveTheirOwnSettings() {
		assertInvalid(content("amrap", "", repsLine("")), false, "sections[0].blocks[0].durationSec:required");
		assertThat(validator.validate(json(content("amrap", ",\"durationSec\":600", repsLine(""))), true)).isNotNull();
		assertThat(validator.validate(json(content("circuit",
				",\"rounds\":3,\"restBetweenLinesSec\":15,\"restBetweenRoundsSec\":90", repsLine(""))), true))
						.isNotNull();
		assertInvalid(content("straight", ",\"rounds\":3", repsLine("")), false,
				"sections[0].blocks[0].rounds:not_allowed");
		assertInvalid(content("zigzag", "", repsLine("")), false, "sections[0].blocks[0].style:invalid");
	}

	@Test
	void duplicateIdsAreRejected() {
		String id = UUID.randomUUID().toString();
		String twoLines = repsLine(",\"id\":\"" + id + "\"") + "," + repsLine(",\"id\":\"" + id + "\"");
		assertInvalid(content("straight", "", twoLines), false, "sections[0].blocks[0].lines[1].id:duplicate");
	}

	@Test
	void sizeLimitsApply() {
		StringBuilder lines = new StringBuilder();
		for (int i = 0; i <= WorkoutContentValidator.MAX_LINES; i++) {
			lines.append(i == 0 ? "" : ",").append(repsLine(""));
		}
		assertInvalid(content("straight", "", lines.toString()), false, "sections[0].blocks[0].lines:invalid");
	}

}
