package com.fitfam.api.training;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fitfam.api.training.ExerciseService.ExerciseInfo;

import tools.jackson.databind.JsonNode;

/**
 * Turns a validated workout into the flat list of steps the guided "walk me through it" player plays: one step per
 * set / repeat / round, with explicit rest steps in between. This is the single place that knows how blocks unfold, so
 * the app only has to play what it receives.
 *
 * <ul>
 * <li>{@code straight}: every line, set after set; the line's {@code restSec} follows each set.</li>
 * <li>{@code circuit}: rounds of all lines; rest between lines and between rounds comes from the block.</li>
 * <li>{@code amrap} / {@code emom} / {@code for_time}: one timed step that lists the lines to do.</li>
 * </ul>
 */
@Component
public class StepExpander {

	public static final String WORK = "work";
	public static final String REST = "rest";
	public static final String TIMED_BLOCK = "timed_block";

	public record ExerciseRef(String id, String nameHe, String measure, String youtubeId, String descriptionHe,
			String cuesHe) {
	}

	/** One exercise with its targets, as the coach entered them. */
	public record Target(ExerciseRef exercise, Integer reps, Integer repsMax, Integer durationSec, Integer distanceM,
			Integer calories, Integer capSec, Integer zone, Integer rpe, String loadHe, String notesHe) {
	}

	public record Step(int index, String type, String sectionTitle, String blockStyle, String blockNotes,
			Integer setNumber, Integer setCount, Integer roundNumber, Integer roundCount, Integer durationSec,
			Integer intervalSec, Integer capSec, Target target, List<Target> targets) {

		Step withIndex(int newIndex) {
			return new Step(newIndex, type, sectionTitle, blockStyle, blockNotes, setNumber, setCount, roundNumber,
					roundCount, durationSec, intervalSec, capSec, target, targets);
		}
	}

	private final ExerciseService exercises;

	public StepExpander(ExerciseService exercises) {
		this.exercises = exercises;
	}

	/** Looks up the exercises used by {@code content} and expands it. */
	public List<Step> expand(JsonNode content) {
		Set<UUID> ids = new LinkedHashSet<>();
		for (JsonNode section : content.path("sections")) {
			for (JsonNode block : section.path("blocks")) {
				for (JsonNode line : block.path("lines")) {
					if (line.path("exerciseId").isString()) {
						ids.add(UUID.fromString(line.path("exerciseId").stringValue()));
					}
				}
			}
		}
		return expand(content, exercises.lookup(ids));
	}

	public List<Step> expand(JsonNode content, Map<UUID, ExerciseInfo> known) {
		List<Step> steps = new ArrayList<>();
		for (JsonNode section : content.path("sections")) {
			String sectionTitle = textOrNull(section, "title");
			for (JsonNode block : section.path("blocks")) {
				String style = block.path("style").stringValue();
				String notes = textOrNull(block, "notes");
				switch (style) {
					case "endurance" -> endurance(block, sectionTitle, notes, steps);
						case "circuit" -> circuit(block, sectionTitle, notes, known, steps);
					case "amrap", "emom", "for_time" -> timed(block, style, sectionTitle, notes, known, steps);
					default -> straight(block, sectionTitle, notes, known, steps);
				}
			}
		}
		// a rest at the very end of the workout is pointless
		if (!steps.isEmpty() && REST.equals(steps.get(steps.size() - 1).type())) {
			steps.remove(steps.size() - 1);
		}
		List<Step> numbered = new ArrayList<>(steps.size());
		for (int i = 0; i < steps.size(); i++) {
			numbered.add(steps.get(i).withIndex(i));
		}
		return numbered;
	}

	private void straight(JsonNode block, String sectionTitle, String notes, Map<UUID, ExerciseInfo> known,
			List<Step> out) {
		for (JsonNode line : block.path("lines")) {
			Target target = target(line, known);
			int count = repeatCount(line, target.exercise().measure());
			int rest = line.path("restSec").intValue(0);
			for (int i = 1; i <= count; i++) {
				out.add(new Step(0, WORK, sectionTitle, "straight", notes, i, count, null, null, null, null, null,
						target, null));
				if (rest > 0) {
					out.add(restStep(rest, sectionTitle, "straight", notes));
				}
			}
		}
	}

	/** Plain running / swimming segments: each line repeats {@code reps} times, its rest follows every repeat. */
	private void endurance(JsonNode block, String sectionTitle, String notes, List<Step> out) {
		for (JsonNode line : block.path("lines")) {
			Target target = enduranceTarget(line);
			int count = line.path("reps").intValue(1);
			int rest = line.path("restSec").intValue(0);
			for (int i = 1; i <= count; i++) {
				out.add(new Step(0, WORK, sectionTitle, "endurance", notes, i, count, null, null, null, null, null,
						target, null));
				if (rest > 0) {
					out.add(restStep(rest, sectionTitle, "endurance", notes));
				}
			}
		}
	}

	private static Target enduranceTarget(JsonNode line) {
		boolean swim = "swim".equals(line.path("activity").stringValue());
		boolean byDistance = line.has("distanceM");
		ExerciseRef ref = new ExerciseRef(swim ? "builtin:swim" : "builtin:run", swim ? "שחייה" : "ריצה",
				byDistance ? "distance" : "duration", null, null, null);
		return new Target(ref, null, null, intOrNull(line, "durationSec"), intOrNull(line, "distanceM"), null, null,
				intOrNull(line, "zone"), intOrNull(line, "rpe"), null, textOrNull(line, "notesHe"));
	}

	private void circuit(JsonNode block, String sectionTitle, String notes, Map<UUID, ExerciseInfo> known,
			List<Step> out) {
		int rounds = block.path("rounds").intValue(1);
		int betweenLines = block.path("restBetweenLinesSec").intValue(0);
		int betweenRounds = block.path("restBetweenRoundsSec").intValue(0);
		JsonNode lines = block.path("lines");
		for (int round = 1; round <= rounds; round++) {
			for (int l = 0; l < lines.size(); l++) {
				Target target = target(lines.get(l), known);
				out.add(new Step(0, WORK, sectionTitle, "circuit", notes, null, null, round, rounds, null, null, null,
						target, null));
				boolean lastLine = l == lines.size() - 1;
				boolean lastRound = round == rounds;
				if (!lastLine && betweenLines > 0) {
					out.add(restStep(betweenLines, sectionTitle, "circuit", notes));
				}
				else if (lastLine && !lastRound && betweenRounds > 0) {
					out.add(restStep(betweenRounds, sectionTitle, "circuit", notes));
				}
			}
		}
	}

	private void timed(JsonNode block, String style, String sectionTitle, String notes,
			Map<UUID, ExerciseInfo> known, List<Step> out) {
		List<Target> targets = new ArrayList<>();
		for (JsonNode line : block.path("lines")) {
			targets.add(target(line, known));
		}
		Integer rounds = "for_time".equals(style) ? block.path("rounds").intValue(1) : null;
		Integer duration = "for_time".equals(style) ? null : block.path("durationSec").intValue();
		Integer interval = "emom".equals(style) ? block.path("intervalSec").intValue(60) : null;
		Integer cap = "for_time".equals(style) && block.has("capSec") ? block.path("capSec").intValue() : null;
		out.add(new Step(0, TIMED_BLOCK, sectionTitle, style, notes, null, null, null, rounds, duration, interval, cap,
				null, targets));
	}

	private static Step restStep(int seconds, String sectionTitle, String style, String notes) {
		return new Step(0, REST, sectionTitle, style, notes, null, null, null, null, seconds, null, null, null, null);
	}

	private static int repeatCount(JsonNode line, String measure) {
		return switch (measure) {
			case "reps", "hold_time", "max_effort" -> line.path("sets").intValue(1);
			case "distance", "duration" -> line.path("reps").intValue(1);
			default -> 1;
		};
	}

	private static Target target(JsonNode line, Map<UUID, ExerciseInfo> known) {
		ExerciseInfo info = known.get(UUID.fromString(line.path("exerciseId").stringValue()));
		ExerciseRef ref = info == null ? null
				: new ExerciseRef(info.id().toString(), info.nameHe(), info.measure(), info.youtubeId(),
						info.descriptionHe(), info.cuesHe());
		String measure = info == null ? "" : info.measure();
		// "reps" on a distance/duration line is the repeat count, not a rep target
		boolean repsAreTarget = "reps".equals(measure);
		return new Target(ref, repsAreTarget ? intOrNull(line, "reps") : null,
				repsAreTarget ? intOrNull(line, "repsMax") : null, intOrNull(line, "durationSec"),
				intOrNull(line, "distanceM"), intOrNull(line, "calories"), intOrNull(line, "capSec"),
				intOrNull(line, "zone"), intOrNull(line, "rpe"), textOrNull(line, "loadHe"),
				textOrNull(line, "notesHe"));
	}

	private static Integer intOrNull(JsonNode node, String key) {
		JsonNode value = node.get(key);
		return value == null || value.isNull() ? null : value.intValue();
	}

	private static String textOrNull(JsonNode node, String key) {
		JsonNode value = node.get(key);
		return value == null || value.isNull() ? null : value.stringValue();
	}

}
