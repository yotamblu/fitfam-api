package com.fitfam.api.training;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.fitfam.api.training.ExerciseService.ExerciseInfo;
import com.fitfam.api.web.ApiException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Checks the JSON content tree of a workout and returns a cleaned copy: only known fields, ids filled in, values in
 * range. Which fields a line may carry depends on how its exercise is measured. Errors are reported as
 * {@code invalid_content} with a detail such as {@code sections[0].blocks[1].lines[0].reps:required}.
 *
 * <pre>
 * { "sections": [ { "id", "title", "blocks": [ { "id", "style", ..., "lines": [ { "id", "exerciseId", ... } ] } ] } ] }
 * </pre>
 */
@Component
public class WorkoutContentValidator {

	static final int MAX_SECTIONS = 10;
	static final int MAX_BLOCKS = 20;
	static final int MAX_LINES = 30;

	static final Set<String> STYLES = Set.of("straight", "circuit", "amrap", "emom", "for_time");

	private static final Set<String> LINE_BASE = Set.of("id", "exerciseId", "notesHe", "restSec");
	private static final Map<String, Set<String>> LINE_EXTRA = Map.of(
			"reps", Set.of("sets", "reps", "repsMax", "loadHe", "rpe"),
			"hold_time", Set.of("sets", "durationSec", "rpe"),
			"distance", Set.of("reps", "distanceM", "zone", "rpe"),
			"duration", Set.of("reps", "durationSec", "zone", "rpe"),
			"calories", Set.of("calories", "zone", "rpe"),
			"max_effort", Set.of("sets", "capSec", "rpe"));
	private static final Map<String, String> LINE_REQUIRED = Map.of(
			"reps", "reps",
			"hold_time", "durationSec",
			"distance", "distanceM",
			"duration", "durationSec",
			"calories", "calories");

	private static final Map<String, Set<String>> BLOCK_EXTRA = Map.of(
			"straight", Set.of(),
			"circuit", Set.of("rounds", "restBetweenLinesSec", "restBetweenRoundsSec"),
			"amrap", Set.of("durationSec"),
			"emom", Set.of("durationSec", "intervalSec"),
			"for_time", Set.of("rounds", "capSec"));
	private static final Set<String> BLOCK_BASE = Set.of("id", "style", "notes", "lines");

	private final ExerciseService exercises;
	private final ObjectMapper mapper;

	public WorkoutContentValidator(ExerciseService exercises, ObjectMapper mapper) {
		this.exercises = exercises;
		this.mapper = mapper;
	}

	public ObjectNode emptyContent() {
		ObjectNode root = mapper.createObjectNode();
		root.putArray("sections");
		return root;
	}

	/** Validates and cleans {@code input}. With {@code forPublish}, the workout must also be complete and usable. */
	public ObjectNode validate(JsonNode input, boolean forPublish) {
		if (input == null || input.isNull() || input.isMissingNode()
				|| (input.isObject() && input.size() == 0)) {
			if (forPublish) {
				throw invalid("sections", "empty");
			}
			return emptyContent();
		}
		if (!input.isObject()) {
			throw invalid("", "object_expected");
		}
		rejectUnknown(input, Set.of("sections"), "");
		JsonNode sectionsIn = input.get("sections");
		if (sectionsIn == null || sectionsIn.isNull()) {
			return forPublish ? failEmpty() : emptyContent();
		}
		if (!sectionsIn.isArray() || sectionsIn.size() > MAX_SECTIONS) {
			throw invalid("sections", "invalid");
		}

		Set<UUID> exerciseIds = new LinkedHashSet<>();
		collectExerciseIds(sectionsIn, exerciseIds);
		Map<UUID, ExerciseInfo> known = exercises.lookup(exerciseIds);

		Set<String> usedIds = new HashSet<>();
		ObjectNode root = mapper.createObjectNode();
		ArrayNode sectionsOut = root.putArray("sections");
		int lines = 0;
		for (int s = 0; s < sectionsIn.size(); s++) {
			lines += section(sectionsIn.get(s), "sections[" + s + "]", sectionsOut.addObject(), known, usedIds,
					forPublish);
		}
		if (forPublish && lines == 0) {
			throw invalid("sections", "empty");
		}
		return root;
	}

	/** Number of exercise lines in an already validated content tree. */
	public static int countLines(JsonNode content) {
		int count = 0;
		if (content == null) {
			return 0;
		}
		for (JsonNode section : content.path("sections")) {
			for (JsonNode block : section.path("blocks")) {
				count += block.path("lines").size();
			}
		}
		return count;
	}

	private ObjectNode failEmpty() {
		throw invalid("sections", "empty");
	}

	private int section(JsonNode in, String path, ObjectNode out, Map<UUID, ExerciseInfo> known, Set<String> usedIds,
			boolean forPublish) {
		if (!in.isObject()) {
			throw invalid(path, "object_expected");
		}
		rejectUnknown(in, Set.of("id", "title", "blocks"), path);
		out.put("id", id(in, path, usedIds));
		String title = optionalString(in, "title", path, 100);
		if (title != null) {
			out.put("title", title);
		}
		JsonNode blocks = in.get("blocks");
		ArrayNode blocksOut = out.putArray("blocks");
		if (blocks == null || blocks.isNull()) {
			return 0;
		}
		if (!blocks.isArray() || blocks.size() > MAX_BLOCKS) {
			throw invalid(path + ".blocks", "invalid");
		}
		int lines = 0;
		for (int b = 0; b < blocks.size(); b++) {
			lines += block(blocks.get(b), path + ".blocks[" + b + "]", blocksOut.addObject(), known, usedIds,
					forPublish);
		}
		return lines;
	}

	private int block(JsonNode in, String path, ObjectNode out, Map<UUID, ExerciseInfo> known, Set<String> usedIds,
			boolean forPublish) {
		if (!in.isObject()) {
			throw invalid(path, "object_expected");
		}
		JsonNode styleNode = in.get("style");
		String style = styleNode != null && styleNode.isString() ? styleNode.stringValue() : null;
		if (style == null || !STYLES.contains(style)) {
			throw invalid(path + ".style", "invalid");
		}
		Set<String> allowed = new HashSet<>(BLOCK_BASE);
		allowed.addAll(BLOCK_EXTRA.get(style));
		rejectUnknown(in, allowed, path);

		out.put("id", id(in, path, usedIds));
		out.put("style", style);
		String notes = optionalString(in, "notes", path, 500);
		if (notes != null) {
			out.put("notes", notes);
		}
		switch (style) {
			case "circuit" -> {
				copyInt(in, out, "rounds", path, 1, 20, false);
				copyInt(in, out, "restBetweenLinesSec", path, 0, 3600, false);
				copyInt(in, out, "restBetweenRoundsSec", path, 0, 3600, false);
			}
			case "amrap" -> copyInt(in, out, "durationSec", path, 1, 7200, true);
			case "emom" -> {
				copyInt(in, out, "durationSec", path, 1, 7200, true);
				copyInt(in, out, "intervalSec", path, 10, 600, false);
			}
			case "for_time" -> {
				copyInt(in, out, "rounds", path, 1, 20, false);
				copyInt(in, out, "capSec", path, 1, 7200, false);
			}
			default -> {
			}
		}

		JsonNode lines = in.get("lines");
		ArrayNode linesOut = out.putArray("lines");
		if (lines == null || lines.isNull()) {
			return 0;
		}
		if (!lines.isArray() || lines.size() > MAX_LINES) {
			throw invalid(path + ".lines", "invalid");
		}
		for (int l = 0; l < lines.size(); l++) {
			line(lines.get(l), path + ".lines[" + l + "]", linesOut.addObject(), known, usedIds, forPublish);
		}
		return lines.size();
	}

	private void line(JsonNode in, String path, ObjectNode out, Map<UUID, ExerciseInfo> known, Set<String> usedIds,
			boolean forPublish) {
		if (!in.isObject()) {
			throw invalid(path, "object_expected");
		}
		UUID exerciseId = exerciseId(in);
		if (exerciseId == null) {
			throw invalid(path + ".exerciseId", "invalid");
		}
		ExerciseInfo exercise = known.get(exerciseId);
		if (exercise == null) {
			throw invalid(path + ".exerciseId", "not_found");
		}
		if (forPublish && exercise.archived()) {
			throw invalid(path + ".exerciseId", "archived");
		}
		String measure = exercise.measure();
		Set<String> allowed = new HashSet<>(LINE_BASE);
		allowed.addAll(LINE_EXTRA.get(measure));
		rejectUnknown(in, allowed, path);

		out.put("id", id(in, path, usedIds));
		out.put("exerciseId", exerciseId.toString());
		String notes = optionalString(in, "notesHe", path, 500);
		if (notes != null) {
			out.put("notesHe", notes);
		}
		copyInt(in, out, "restSec", path, 0, 3600, false);

		String required = LINE_REQUIRED.get(measure);
		switch (measure) {
			case "reps" -> {
				copyInt(in, out, "sets", path, 1, 20, false);
				copyInt(in, out, "reps", path, 1, 1000, true);
				copyInt(in, out, "repsMax", path, 1, 1000, false);
				if (out.has("repsMax") && out.get("repsMax").intValue() < out.get("reps").intValue()) {
					throw invalid(path + ".repsMax", "below_reps");
				}
				String load = optionalString(in, "loadHe", path, 100);
				if (load != null) {
					out.put("loadHe", load);
				}
				copyInt(in, out, "rpe", path, 1, 10, false);
			}
			case "hold_time" -> {
				copyInt(in, out, "sets", path, 1, 20, false);
				copyInt(in, out, "durationSec", path, 1, 7200, true);
				copyInt(in, out, "rpe", path, 1, 10, false);
			}
			case "distance" -> {
				copyInt(in, out, "reps", path, 1, 100, false);
				copyInt(in, out, "distanceM", path, 1, 100000, true);
				copyInt(in, out, "zone", path, 1, 5, false);
				copyInt(in, out, "rpe", path, 1, 10, false);
			}
			case "duration" -> {
				copyInt(in, out, "reps", path, 1, 100, false);
				copyInt(in, out, "durationSec", path, 1, 7200, true);
				copyInt(in, out, "zone", path, 1, 5, false);
				copyInt(in, out, "rpe", path, 1, 10, false);
			}
			case "calories" -> {
				copyInt(in, out, "calories", path, 1, 2000, true);
				copyInt(in, out, "zone", path, 1, 5, false);
				copyInt(in, out, "rpe", path, 1, 10, false);
			}
			case "max_effort" -> {
				copyInt(in, out, "sets", path, 1, 20, false);
				copyInt(in, out, "capSec", path, 1, 7200, false);
				copyInt(in, out, "rpe", path, 1, 10, false);
			}
			default -> throw invalid(path, "unknown_measure");
		}
		if (required != null && !out.has(required)) {
			throw invalid(path + "." + required, "required");
		}
	}

	private void collectExerciseIds(JsonNode sections, Set<UUID> into) {
		if (!sections.isArray()) {
			return;
		}
		for (JsonNode section : sections) {
			JsonNode blocks = section.path("blocks");
			if (!blocks.isArray()) {
				continue;
			}
			for (JsonNode block : blocks) {
				JsonNode lines = block.path("lines");
				if (!lines.isArray()) {
					continue;
				}
				for (JsonNode line : lines) {
					UUID id = exerciseId(line);
					if (id != null) {
						into.add(id);
					}
				}
			}
		}
	}

	private static UUID exerciseId(JsonNode line) {
		JsonNode node = line.path("exerciseId");
		if (!node.isString()) {
			return null;
		}
		try {
			return UUID.fromString(node.stringValue());
		}
		catch (IllegalArgumentException e) {
			return null;
		}
	}

	private String id(JsonNode in, String path, Set<String> usedIds) {
		JsonNode node = in.get("id");
		String id;
		if (node == null || node.isNull()) {
			id = UUID.randomUUID().toString();
		}
		else {
			try {
				id = UUID.fromString(node.isString() ? node.stringValue() : "").toString();
			}
			catch (IllegalArgumentException e) {
				throw invalid(path + ".id", "invalid");
			}
		}
		if (!usedIds.add(id)) {
			throw invalid(path + ".id", "duplicate");
		}
		return id;
	}

	private static String optionalString(JsonNode in, String key, String path, int maxLength) {
		JsonNode node = in.get(key);
		if (node == null || node.isNull()) {
			return null;
		}
		if (!node.isString() || node.stringValue().length() > maxLength) {
			throw invalid(path + "." + key, "invalid");
		}
		String value = node.stringValue().trim();
		return value.isEmpty() ? null : value;
	}

	private static void copyInt(JsonNode in, ObjectNode out, String key, String path, int min, int max,
			boolean required) {
		JsonNode node = in.get(key);
		if (node == null || node.isNull()) {
			if (required) {
				throw invalid(path + "." + key, "required");
			}
			return;
		}
		if (!node.isIntegralNumber() || !node.canConvertToInt()) {
			throw invalid(path + "." + key, "integer_expected");
		}
		int value = node.intValue();
		if (value < min || value > max) {
			throw invalid(path + "." + key, "out_of_range");
		}
		out.put(key, value);
	}

	private static void rejectUnknown(JsonNode in, Set<String> allowed, String path) {
		for (String name : in.propertyNames()) {
			if (!allowed.contains(name)) {
				throw invalid(path.isEmpty() ? name : path + "." + name, "not_allowed");
			}
		}
	}

	private static ApiException invalid(String path, String reason) {
		return new ApiException(HttpStatus.BAD_REQUEST, "invalid_content", path + ":" + reason);
	}

}
