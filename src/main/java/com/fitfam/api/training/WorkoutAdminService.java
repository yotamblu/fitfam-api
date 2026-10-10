package com.fitfam.api.training;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fitfam.api.admin.AuditLogService;
import com.fitfam.api.auth.AuthenticatedUser;
import com.fitfam.api.training.TrainingDtos.LevelSummaryDto;
import com.fitfam.api.training.TrainingDtos.LevelWorkoutsDto;
import com.fitfam.api.training.TrainingDtos.PlanTreeDto;
import com.fitfam.api.training.TrainingDtos.WorkoutDto;
import com.fitfam.api.training.TrainingDtos.WorkoutRequest;
import com.fitfam.api.training.TrainingDtos.WorkoutSummaryDto;
import com.fitfam.api.web.ApiException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * What coaches do with workouts: browse plans and levels, add, edit, publish, duplicate, archive and reorder. The
 * order of a level is {@code sort_order} 1..n with the challenge always last and archived workouts after it.
 */
@Service
public class WorkoutAdminService {

	private static final String WORKOUT_COLUMNS = "id::text as id, level_id::text as level_id, sort_order, type, sport, "
			+ "status, title_he, description_he, goal_he, est_minutes, details::text as details, updated_at";

	private final JdbcTemplate jdbc;
	private final AuditLogService audit;
	private final WorkoutContentValidator validator;
	private final ObjectMapper mapper;

	public WorkoutAdminService(JdbcTemplate jdbc, AuditLogService audit, WorkoutContentValidator validator,
			ObjectMapper mapper) {
		this.jdbc = jdbc;
		this.audit = audit;
		this.validator = validator;
		this.mapper = mapper;
	}

	// ---- browsing ----

	@Transactional(readOnly = true)
	public List<PlanTreeDto> planTree() {
		record Row(String planSlug, String planName, LevelSummaryDto level) {
		}
		List<Row> rows = jdbc.query("""
				select p.slug as plan_slug, p.name_he as plan_name, l.id::text as level_id, l.level_number,
				       l.slug as level_slug, l.name_he as level_name,
				       count(w.id) filter (where w.status <> 'archived') as workout_count,
				       count(w.id) filter (where w.status = 'published') as published_count,
				       coalesce(bool_or(w.type = 'challenge' and w.status <> 'archived'), false) as has_challenge
				from plans p
				join plan_levels l on l.plan_id = p.id
				left join workouts w on w.level_id = l.id
				group by p.slug, p.name_he, p.sort_order, l.id, l.level_number, l.slug, l.name_he
				order by p.sort_order, l.level_number
				""", (rs, i) -> new Row(rs.getString("plan_slug"), rs.getString("plan_name"),
				new LevelSummaryDto(rs.getString("level_id"), rs.getInt("level_number"), rs.getString("level_slug"),
						rs.getString("level_name"), rs.getInt("workout_count"), rs.getInt("published_count"),
						rs.getBoolean("has_challenge"))));
		Map<String, PlanTreeDto> byPlan = new LinkedHashMap<>();
		for (Row row : rows) {
			byPlan.computeIfAbsent(row.planSlug(), slug -> new PlanTreeDto(slug, row.planName(), new ArrayList<>()))
					.levels().add(row.level());
		}
		return new ArrayList<>(byPlan.values());
	}

	@Transactional(readOnly = true)
	public LevelWorkoutsDto levelWorkouts(UUID levelId) {
		var level = jdbc.query("""
				select p.slug, p.name_he as plan_name, l.level_number, l.name_he as level_name
				from plan_levels l join plans p on p.id = l.plan_id where l.id = ?
				""", (rs, i) -> new String[] { rs.getString("slug"), rs.getString("plan_name"),
				String.valueOf(rs.getInt("level_number")), rs.getString("level_name") }, levelId).stream().findFirst()
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "level_not_found"));
		List<WorkoutSummaryDto> workouts = jdbc.query("select " + WORKOUT_COLUMNS
				+ " from workouts where level_id = ? order by (status = 'archived'), sort_order", (rs, i) -> {
					JsonNode content = mapper.readTree(rs.getString("details"));
					return new WorkoutSummaryDto(rs.getString("id"), rs.getInt("sort_order"), rs.getString("type"),
							rs.getString("sport"), rs.getString("status"), rs.getString("title_he"),
							(Integer) rs.getObject("est_minutes"), WorkoutContentValidator.countLines(content),
							rs.getObject("updated_at", OffsetDateTime.class).toInstant());
				}, levelId);
		return new LevelWorkoutsDto(levelId.toString(), level[0], level[1], Integer.parseInt(level[2]), level[3],
				workouts);
	}

	@Transactional(readOnly = true)
	public WorkoutDto get(UUID id) {
		return jdbc.query("select " + WORKOUT_COLUMNS + " from workouts where id = ?", (rs, i) -> toDto(rs), id)
				.stream().findFirst().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "workout_not_found"));
	}

	// ---- changes ----

	@Transactional
	public WorkoutDto create(AuthenticatedUser admin, UUID levelId, WorkoutRequest request) {
		requireLevel(levelId);
		String type = request.type() == null || request.type().isBlank() ? Vocabulary.TYPE_REGULAR : request.type();
		if (!Vocabulary.TYPE_REGULAR.equals(type) && !Vocabulary.TYPE_CHALLENGE.equals(type)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_type");
		}
		if (Vocabulary.TYPE_CHALLENGE.equals(type) && liveChallengeExists(levelId)) {
			throw new ApiException(HttpStatus.CONFLICT, "challenge_exists");
		}
		Fields fields = fields(request, false);
		UUID id = UUID.randomUUID();
		int next = nextSortOrder(levelId);
		jdbc.update("insert into workouts (id, level_id, sort_order, type, sport, status, title_he, description_he, "
				+ "goal_he, est_minutes, details, updated_by) values (?, ?, ?, ?, ?, 'draft', ?, ?, ?, ?, ?::jsonb, ?)",
				id, levelId, next, type, fields.sport, fields.titleHe, fields.descriptionHe, fields.goalHe,
				fields.estMinutes, fields.contentJson, admin.id());
		normalizeOrder(levelId);
		audit.record(admin.id(), "workout.created", "workout", id.toString(), Map.of("type", type));
		return get(id);
	}

	@Transactional
	public WorkoutDto update(AuthenticatedUser admin, UUID id, WorkoutRequest request) {
		WorkoutDto current = get(id);
		if (request.type() != null && !request.type().isBlank() && !request.type().equals(current.type())) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "type_immutable");
		}
		boolean live = Vocabulary.STATUS_PUBLISHED.equals(current.status());
		Fields fields = fields(request, live);
		if (live) {
			requirePublishable(current.type(), fields);
		}
		jdbc.update("update workouts set sport = ?, title_he = ?, description_he = ?, goal_he = ?, est_minutes = ?, "
				+ "details = ?::jsonb, updated_at = now(), updated_by = ? where id = ?", fields.sport, fields.titleHe,
				fields.descriptionHe, fields.goalHe, fields.estMinutes, fields.contentJson, admin.id(), id);
		audit.record(admin.id(), "workout.updated", "workout", id.toString(), Map.of());
		return get(id);
	}

	@Transactional
	public WorkoutDto publish(AuthenticatedUser admin, UUID id) {
		WorkoutDto current = get(id);
		if (Vocabulary.STATUS_ARCHIVED.equals(current.status())) {
			throw new ApiException(HttpStatus.CONFLICT, "workout_archived");
		}
		Fields fields = new Fields(current.sport(), blankToNull(current.titleHe()), blankToNull(current.descriptionHe()),
				blankToNull(current.goalHe()), current.estMinutes(), null);
		requirePublishable(current.type(), fields);
		// re-validate for publishing: the content must be complete and use only live exercises
		JsonNode cleaned = validator.validate(current.content(), true);
		setStatus(id, Vocabulary.STATUS_PUBLISHED, cleaned, admin.id());
		audit.record(admin.id(), "workout.published", "workout", id.toString(), Map.of());
		return get(id);
	}

	@Transactional
	public WorkoutDto unpublish(AuthenticatedUser admin, UUID id) {
		WorkoutDto current = get(id);
		if (Vocabulary.STATUS_ARCHIVED.equals(current.status())) {
			throw new ApiException(HttpStatus.CONFLICT, "workout_archived");
		}
		setStatus(id, Vocabulary.STATUS_DRAFT, null, admin.id());
		audit.record(admin.id(), "workout.unpublished", "workout", id.toString(), Map.of());
		return get(id);
	}

	@Transactional
	public WorkoutDto archive(AuthenticatedUser admin, UUID id) {
		WorkoutDto current = get(id);
		setStatus(id, Vocabulary.STATUS_ARCHIVED, null, admin.id());
		normalizeOrder(UUID.fromString(current.levelId()));
		audit.record(admin.id(), "workout.archived", "workout", id.toString(), Map.of());
		return get(id);
	}

	/** Brings an archived workout back as a draft (a second live challenge in the level is refused). */
	@Transactional
	public WorkoutDto restore(AuthenticatedUser admin, UUID id) {
		WorkoutDto current = get(id);
		if (!Vocabulary.STATUS_ARCHIVED.equals(current.status())) {
			return current;
		}
		UUID levelId = UUID.fromString(current.levelId());
		if (Vocabulary.TYPE_CHALLENGE.equals(current.type()) && liveChallengeExists(levelId)) {
			throw new ApiException(HttpStatus.CONFLICT, "challenge_exists");
		}
		setStatus(id, Vocabulary.STATUS_DRAFT, null, admin.id());
		normalizeOrder(levelId);
		audit.record(admin.id(), "workout.restored", "workout", id.toString(), Map.of());
		return get(id);
	}

	/** Only drafts can be deleted; anything customers may have seen is archived instead. */
	@Transactional
	public void delete(AuthenticatedUser admin, UUID id) {
		WorkoutDto current = get(id);
		if (!Vocabulary.STATUS_DRAFT.equals(current.status())) {
			throw new ApiException(HttpStatus.CONFLICT, "not_a_draft");
		}
		jdbc.update("delete from workouts where id = ?", id);
		normalizeOrder(UUID.fromString(current.levelId()));
		audit.record(admin.id(), "workout.deleted", "workout", id.toString(), Map.of());
	}

	/** Copies a workout as a new draft at the end of {@code targetLevelId} (default: the same level). */
	@Transactional
	public WorkoutDto duplicate(AuthenticatedUser admin, UUID id, UUID targetLevelId) {
		WorkoutDto source = get(id);
		UUID levelId = targetLevelId == null ? UUID.fromString(source.levelId()) : targetLevelId;
		requireLevel(levelId);
		UUID copyId = UUID.randomUUID();
		String title = source.titleHe() == null ? null : source.titleHe() + " (עותק)";
		// a copy is always a regular workout: a level has one challenge and that is made on purpose
		jdbc.update("insert into workouts (id, level_id, sort_order, type, sport, status, title_he, description_he, "
				+ "est_minutes, details, updated_by) values (?, ?, ?, 'regular', ?, 'draft', ?, ?, ?, ?::jsonb, ?)",
				copyId, levelId, nextSortOrder(levelId), source.sport(), title, source.descriptionHe(),
				source.estMinutes(), mapper.writeValueAsString(source.content()), admin.id());
		normalizeOrder(levelId);
		audit.record(admin.id(), "workout.duplicated", "workout", copyId.toString(),
				Map.of("from", id.toString()));
		return get(copyId);
	}

	/**
	 * Sets the order of a level. {@code ids} must be exactly the level's live (not archived) workouts; the challenge is
	 * always put last, wherever it appears in the list.
	 */
	@Transactional
	public LevelWorkoutsDto reorder(AuthenticatedUser admin, UUID levelId, List<UUID> ids) {
		requireLevel(levelId);
		List<String> live = jdbc.queryForList(
				"select id::text from workouts where level_id = ? and status <> 'archived' for update", String.class,
				levelId);
		Set<String> requested = new LinkedHashSet<>();
		ids.forEach(i -> requested.add(i.toString()));
		if (requested.size() != ids.size() || !requested.equals(new LinkedHashSet<>(live))) {
			// the list changed under the coach (or the request is wrong): they must reload
			throw new ApiException(HttpStatus.CONFLICT, "order_mismatch");
		}
		assignOrder(levelId, ids);
		audit.record(admin.id(), "workouts.reordered", "level", levelId.toString(), Map.of());
		return levelWorkouts(levelId);
	}

	// ---- order helpers ----

	private int nextSortOrder(UUID levelId) {
		Integer max = jdbc.queryForObject("select coalesce(max(sort_order), 0) from workouts where level_id = ?",
				Integer.class, levelId);
		return (max == null ? 0 : max) + 1;
	}

	/** Renumbers 1..n: live regular workouts in their order, then the live challenge, then archived ones. */
	private void normalizeOrder(UUID levelId) {
		List<UUID> ordered = jdbc.query("""
				select id from workouts where level_id = ? and status <> 'archived'
				order by (type = 'challenge'), sort_order, created_at
				""", (rs, i) -> rs.getObject("id", UUID.class), levelId);
		assignOrder(levelId, ordered);
	}

	private void assignOrder(UUID levelId, List<UUID> liveIds) {
		List<UUID> regular = new ArrayList<>();
		UUID challenge = null;
		for (UUID id : liveIds) {
			String type = jdbc.queryForObject("select type from workouts where id = ?", String.class, id);
			if (Vocabulary.TYPE_CHALLENGE.equals(type)) {
				challenge = id;
			}
			else {
				regular.add(id);
			}
		}
		List<UUID> all = new ArrayList<>(regular);
		if (challenge != null) {
			all.add(challenge);
		}
		// archived workouts (not in liveIds) go after everything else, in their current order
		List<UUID> archived = jdbc.query(
				"select id from workouts where level_id = ? and status = 'archived' order by sort_order, created_at",
				(rs, i) -> rs.getObject("id", UUID.class), levelId);
		all.addAll(archived);
		int position = 1;
		for (UUID id : all) {
			jdbc.update("update workouts set sort_order = ? where id = ?", position++, id);
		}
	}

	// ---- helpers ----

	private record Fields(String sport, String titleHe, String descriptionHe, String goalHe, Integer estMinutes,
			String contentJson) {
	}

	private Fields fields(WorkoutRequest request, boolean forPublish) {
		if (!Vocabulary.SPORTS.contains(request.sport())) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_sport");
		}
		Integer minutes = request.estMinutes();
		if (minutes != null && (minutes < 1 || minutes > 600)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_minutes");
		}
		JsonNode content = validator.validate(request.content(), forPublish);
		return new Fields(request.sport(), blankToNull(request.titleHe()), blankToNull(request.descriptionHe()),
				blankToNull(request.goalHe()), minutes, mapper.writeValueAsString(content));
	}

	private static void requirePublishable(String type, Fields fields) {
		if (fields.titleHe == null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "title_required");
		}
		if (Vocabulary.TYPE_CHALLENGE.equals(type) && fields.goalHe == null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "goal_required");
		}
	}

	private void setStatus(UUID id, String status, JsonNode cleanedContent, UUID adminId) {
		if (cleanedContent != null) {
			jdbc.update("update workouts set status = ?, details = ?::jsonb, updated_at = now(), updated_by = ? "
					+ "where id = ?", status, mapper.writeValueAsString(cleanedContent), adminId, id);
		}
		else {
			jdbc.update("update workouts set status = ?, updated_at = now(), updated_by = ? where id = ?", status,
					adminId, id);
		}
	}

	private boolean liveChallengeExists(UUID levelId) {
		Boolean exists = jdbc.queryForObject("select exists (select 1 from workouts where level_id = ? "
				+ "and type = 'challenge' and status <> 'archived')", Boolean.class, levelId);
		return Boolean.TRUE.equals(exists);
	}

	private void requireLevel(UUID levelId) {
		Boolean exists = jdbc.queryForObject("select exists (select 1 from plan_levels where id = ?)", Boolean.class,
				levelId);
		if (!Boolean.TRUE.equals(exists)) {
			throw new ApiException(HttpStatus.NOT_FOUND, "level_not_found");
		}
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	private WorkoutDto toDto(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new WorkoutDto(rs.getString("id"), rs.getString("level_id"), rs.getInt("sort_order"),
				rs.getString("type"), rs.getString("sport"), rs.getString("status"), rs.getString("title_he"),
				rs.getString("description_he"), rs.getString("goal_he"), (Integer) rs.getObject("est_minutes"),
				mapper.readTree(rs.getString("details")), rs.getObject("updated_at", OffsetDateTime.class).toInstant());
	}

}
