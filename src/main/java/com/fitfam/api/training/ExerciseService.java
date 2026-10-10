package com.fitfam.api.training;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fitfam.api.admin.AuditLogService;
import com.fitfam.api.auth.AuthenticatedUser;
import com.fitfam.api.training.TrainingDtos.ExerciseDto;
import com.fitfam.api.training.TrainingDtos.ExerciseRequest;
import com.fitfam.api.web.ApiException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The exercise bank. Plain SQL with bound parameters only. */
@Service
public class ExerciseService {

	private static final String COLUMNS = "id::text as id, name_he, sport, measure, equipment::text as equipment, "
			+ "muscle_groups::text as muscle_groups, description_he, cues_he, youtube_id, archived, created_at, updated_at";

	/** What the workout validator and the step expander need to know about an exercise. */
	public record ExerciseInfo(UUID id, String nameHe, String sport, String measure, boolean archived,
			String youtubeId, String descriptionHe, String cuesHe) {
	}

	private final JdbcTemplate jdbc;
	private final AuditLogService audit;
	private final ObjectMapper mapper;

	public ExerciseService(JdbcTemplate jdbc, AuditLogService audit, ObjectMapper mapper) {
		this.jdbc = jdbc;
		this.audit = audit;
		this.mapper = mapper;
	}

	@Transactional(readOnly = true)
	public List<ExerciseDto> list(String sport, String query, boolean includeArchived) {
		List<String> conditions = new ArrayList<>();
		List<Object> params = new ArrayList<>();
		if (sport != null && !sport.isBlank()) {
			conditions.add("sport = ?");
			params.add(sport.trim());
		}
		if (query != null && !query.isBlank()) {
			// position() instead of LIKE so percent and underscore typed by the user are not wildcards
			conditions.add("position(lower(?) in lower(name_he)) > 0");
			params.add(query.trim());
		}
		if (!includeArchived) {
			conditions.add("not archived");
		}
		String where = conditions.isEmpty() ? "" : " where " + String.join(" and ", conditions);
		return jdbc.query("select " + COLUMNS + " from exercises" + where + " order by name_he limit 1000",
				(rs, row) -> toDto(rs), params.toArray());
	}

	@Transactional(readOnly = true)
	public ExerciseDto get(UUID id) {
		return jdbc.query("select " + COLUMNS + " from exercises where id = ?", (rs, row) -> toDto(rs), id).stream()
				.findFirst().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "exercise_not_found"));
	}

	@Transactional
	public ExerciseDto create(AuthenticatedUser admin, ExerciseRequest request) {
		Clean clean = clean(request);
		UUID id = UUID.randomUUID();
		try {
			jdbc.update("insert into exercises (id, name_he, sport, measure, equipment, muscle_groups, description_he, "
					+ "cues_he, youtube_id, created_by) values (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?)", id,
					clean.nameHe, clean.sport, clean.measure, clean.equipmentJson, clean.musclesJson,
					clean.descriptionHe, clean.cuesHe, clean.youtubeId, admin.id());
		}
		catch (DuplicateKeyException e) {
			throw new ApiException(HttpStatus.CONFLICT, "exercise_exists");
		}
		audit.record(admin.id(), "exercise.created", "exercise", id.toString(), Map.of("name", clean.nameHe));
		return get(id);
	}

	@Transactional
	public ExerciseDto update(AuthenticatedUser admin, UUID id, ExerciseRequest request) {
		ExerciseDto current = get(id);
		Clean clean = clean(request);
		if (!current.measure().equals(clean.measure) && usedByAnyWorkout(id)) {
			// changing how an exercise is measured would invalidate the workouts that already use it
			throw new ApiException(HttpStatus.CONFLICT, "exercise_in_use");
		}
		try {
			jdbc.update("update exercises set name_he = ?, sport = ?, measure = ?, equipment = ?::jsonb, "
					+ "muscle_groups = ?::jsonb, description_he = ?, cues_he = ?, youtube_id = ?, updated_at = now() "
					+ "where id = ?", clean.nameHe, clean.sport, clean.measure, clean.equipmentJson, clean.musclesJson,
					clean.descriptionHe, clean.cuesHe, clean.youtubeId, id);
		}
		catch (DuplicateKeyException e) {
			throw new ApiException(HttpStatus.CONFLICT, "exercise_exists");
		}
		audit.record(admin.id(), "exercise.updated", "exercise", id.toString(), Map.of("name", clean.nameHe));
		return get(id);
	}

	@Transactional
	public ExerciseDto setArchived(AuthenticatedUser admin, UUID id, boolean archived) {
		get(id);
		jdbc.update("update exercises set archived = ?, updated_at = now() where id = ?", archived, id);
		audit.record(admin.id(), archived ? "exercise.archived" : "exercise.restored", "exercise", id.toString(),
				Map.of());
		return get(id);
	}

	/** Looks up the given exercises; ids that do not exist are simply missing from the result. */
	@Transactional(readOnly = true)
	public Map<UUID, ExerciseInfo> lookup(Set<UUID> ids) {
		Map<UUID, ExerciseInfo> found = new LinkedHashMap<>();
		if (ids.isEmpty()) {
			return found;
		}
		String placeholders = String.join(",", ids.stream().map(i -> "?").toList());
		jdbc.query("select id, name_he, sport, measure, archived, youtube_id, description_he, cues_he from exercises "
				+ "where id in (" + placeholders + ")", (ResultSet rs) -> {
					UUID id = rs.getObject("id", UUID.class);
					found.put(id, new ExerciseInfo(id, rs.getString("name_he"), rs.getString("sport"),
							rs.getString("measure"), rs.getBoolean("archived"), rs.getString("youtube_id"),
							rs.getString("description_he"), rs.getString("cues_he")));
				}, ids.toArray());
		return found;
	}

	private boolean usedByAnyWorkout(UUID exerciseId) {
		Boolean used = jdbc.queryForObject(
				"select exists (select 1 from workouts where position(? in details::text) > 0)", Boolean.class,
				exerciseId.toString());
		return Boolean.TRUE.equals(used);
	}

	private record Clean(String nameHe, String sport, String measure, String equipmentJson, String musclesJson,
			String descriptionHe, String cuesHe, String youtubeId) {
	}

	private Clean clean(ExerciseRequest request) {
		if (!Vocabulary.SPORTS.contains(request.sport())) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_sport");
		}
		if (!Vocabulary.MEASURES.contains(request.measure())) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_measure");
		}
		String youtubeId = null;
		if (request.videoUrl() != null && !request.videoUrl().isBlank()) {
			youtubeId = YouTubeLinks.extractId(request.videoUrl());
			if (youtubeId == null) {
				throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_video_url");
			}
		}
		return new Clean(request.nameHe().trim(), request.sport(), request.measure(),
				json(tidy(request.equipment())), json(tidy(request.muscleGroups())),
				blankToNull(request.descriptionHe()), blankToNull(request.cuesHe()), youtubeId);
	}

	private static List<String> tidy(List<String> values) {
		if (values == null) {
			return List.of();
		}
		Set<String> unique = new LinkedHashSet<>();
		for (String value : values) {
			if (value != null && !value.isBlank()) {
				unique.add(value.trim());
			}
		}
		return List.copyOf(unique);
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	private String json(List<String> values) {
		return mapper.writeValueAsString(values);
	}

	private ExerciseDto toDto(ResultSet rs) throws SQLException {
		String youtubeId = rs.getString("youtube_id");
		return new ExerciseDto(rs.getString("id"), rs.getString("name_he"), rs.getString("sport"),
				rs.getString("measure"), stringList(rs.getString("equipment")),
				stringList(rs.getString("muscle_groups")), rs.getString("description_he"), rs.getString("cues_he"),
				youtubeId, YouTubeLinks.canonicalUrl(youtubeId), rs.getBoolean("archived"),
				rs.getObject("created_at", OffsetDateTime.class).toInstant(),
				rs.getObject("updated_at", OffsetDateTime.class).toInstant());
	}

	private List<String> stringList(String jsonArray) {
		List<String> out = new ArrayList<>();
		JsonNode node = mapper.readTree(jsonArray);
		for (JsonNode item : node) {
			out.add(item.asString());
		}
		return out;
	}

}
