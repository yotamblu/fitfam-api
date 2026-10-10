package com.fitfam.api.training;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fitfam.api.training.TrainingDtos.AttemptResultDto;
import com.fitfam.api.training.TrainingDtos.CustomerWorkoutDto;
import com.fitfam.api.training.TrainingDtos.MyPlanDto;
import com.fitfam.api.training.TrainingDtos.RoadmapDto;
import com.fitfam.api.training.TrainingDtos.RoadmapLevelDto;
import com.fitfam.api.training.TrainingDtos.RoadmapWorkoutDto;
import com.fitfam.api.web.ApiException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The customer side: the roadmap of a plan, opening a workout, saving progress, finishing a workout and attempting a
 * level challenge. All rules live here:
 * <ul>
 * <li>Workouts of the current level are done strictly in order. Levels above the current one are locked.</li>
 * <li>Completed workouts stay readable. Levels below the current one that were not completed show as skipped, and a
 * skipped workout never has a progress row, so points and streaks cannot count it.</li>
 * <li>Passing the challenge of the current level moves up one level. To start higher, a customer passes the challenge
 * of the level directly below the target (any challenge above the current level, except the top one).</li>
 * </ul>
 */
@Service
public class ProgressService {

	public static final String DONE = "done";
	public static final String CURRENT = "current";
	public static final String LOCKED = "locked";
	public static final String SKIPPED = "skipped";

	private static final String REASON_CHALLENGE_PASSED = "challenge_passed";
	private static final String REASON_SKIPPED = "skipped";

	private final JdbcTemplate jdbc;
	private final ObjectMapper mapper;

	public ProgressService(JdbcTemplate jdbc, ObjectMapper mapper) {
		this.jdbc = jdbc;
		this.mapper = mapper;
	}

	private record Enrollment(UUID id, UUID userId, UUID planId, String planSlug, String planNameHe,
			UUID currentLevelId, int currentLevelNumber, String currentLevelNameHe, String status) {
	}

	record WorkoutRow(UUID id, UUID levelId, int levelNumber, int sortOrder, String type, String sport,
			String titleHe, Integer estMinutes) {
	}

	// ---- reading ----

	@Transactional(readOnly = true)
	public List<MyPlanDto> myPlans(UUID userId) {
		return jdbc.query("""
				select e.id::text as id, p.slug, p.name_he, l.level_number, l.name_he as level_name, e.status
				from enrollments e
				join plans p on p.id = e.plan_id
				join plan_levels l on l.id = e.current_level_id
				where e.user_id = ? and e.status <> 'ended'
				order by p.sort_order
				""", (rs, i) -> new MyPlanDto(rs.getString("id"), rs.getString("slug"), rs.getString("name_he"),
				rs.getInt("level_number"), rs.getString("level_name"), rs.getString("status")), userId);
	}

	@Transactional(readOnly = true)
	public RoadmapDto roadmap(UUID userId, String planSlug) {
		return buildRoadmap(enrollment(userId, planSlug, false));
	}

	/** Opens a workout for reading or playing; only workouts that are done or current can be opened. */
	@Transactional(readOnly = true)
	public CustomerWorkoutDto openWorkout(UUID userId, UUID workoutId) {
		WorkoutRow workout = publishedWorkout(workoutId);
		Enrollment enrollment = enrollmentForLevel(userId, workout.levelId(), false);
		RoadmapWorkoutDto entry = findInRoadmap(buildRoadmap(enrollment), workoutId);
		boolean challengeAttempt = Vocabulary.TYPE_CHALLENGE.equals(workout.type()) && entry.canAttempt();
		if (!DONE.equals(entry.state()) && !CURRENT.equals(entry.state()) && !challengeAttempt) {
			throw new ApiException(HttpStatus.FORBIDDEN, "workout_locked");
		}
		return jdbc.query("""
				select w.description_he, w.goal_he, w.details::text as details,
				       coalesce((select resume_step from workout_progress
				                 where enrollment_id = ? and workout_id = w.id and status = 'in_progress'), 0) as resume_step
				from workouts w where w.id = ?
				""", (rs, i) -> {
					JsonNode content = mapper.readTree(rs.getString("details"));
					return new CustomerWorkoutDto(workoutId.toString(), workout.type(), workout.sport(),
							workout.titleHe(), rs.getString("description_he"), rs.getString("goal_he"),
							workout.estMinutes(), entry.state(), rs.getInt("resume_step"), content);
				}, enrollment.id(), workoutId).get(0);
	}

	// ---- writing ----

	@Transactional
	public void saveProgress(UUID userId, UUID workoutId, int resumeStep) {
		WorkoutRow workout = playableWorkout(userId, workoutId);
		Enrollment enrollment = enrollmentForLevel(userId, workout.levelId(), true);
		requireCurrent(enrollment, workoutId);
		jdbc.update("""
				insert into workout_progress (user_id, enrollment_id, workout_id, resume_step)
				values (?, ?, ?, ?)
				on conflict (enrollment_id, workout_id)
				do update set resume_step = excluded.resume_step where workout_progress.status = 'in_progress'
				""", userId, enrollment.id(), workoutId, resumeStep);
	}

	/** Marks the workout finished (the same whether or not the guided player was used). Doing it twice is harmless. */
	@Transactional
	public RoadmapDto complete(UUID userId, UUID workoutId) {
		WorkoutRow workout = playableWorkout(userId, workoutId);
		Enrollment enrollment = enrollmentForLevel(userId, workout.levelId(), true);
		RoadmapWorkoutDto entry = findInRoadmap(buildRoadmap(enrollment), workoutId);
		if (!DONE.equals(entry.state())) {
			requireCurrent(enrollment, workoutId);
			markCompleted(userId, enrollment.id(), workoutId);
		}
		return buildRoadmap(enrollment);
	}

	/**
	 * A customer reports, by tapping, whether they passed a level challenge. Attempts are unlimited and trusted. A pass
	 * moves the enrollment up a level, or ends the plan when it was the top level's challenge.
	 */
	@Transactional
	public AttemptResultDto attemptChallenge(UUID userId, UUID workoutId, boolean passed) {
		WorkoutRow workout = publishedWorkout(workoutId);
		if (!Vocabulary.TYPE_CHALLENGE.equals(workout.type())) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "not_a_challenge");
		}
		Enrollment enrollment = enrollmentForLevel(userId, workout.levelId(), true);
		RoadmapDto roadmap = buildRoadmap(enrollment);
		RoadmapWorkoutDto entry = findInRoadmap(roadmap, workoutId);
		if (!entry.canAttempt()) {
			throw new ApiException(HttpStatus.FORBIDDEN, "challenge_not_available");
		}
		jdbc.update("insert into challenge_attempts (user_id, enrollment_id, workout_id, passed) values (?, ?, ?, ?)",
				userId, enrollment.id(), workoutId, passed);
		if (!passed) {
			return new AttemptResultDto(false, enrollment.currentLevelNumber(), false, false);
		}
		markCompleted(userId, enrollment.id(), workoutId);

		int challengeLevel = workout.levelNumber();
		int top = topLevelNumber(enrollment.planId());
		if (challengeLevel == top) {
			return new AttemptResultDto(true, enrollment.currentLevelNumber(), false, true);
		}
		String reason = challengeLevel == enrollment.currentLevelNumber() ? REASON_CHALLENGE_PASSED : REASON_SKIPPED;
		UUID target = levelId(enrollment.planId(), challengeLevel + 1);
		jdbc.update("update enrollments set current_level_id = ? where id = ?", target, enrollment.id());
		jdbc.update("insert into enrollment_level_history (enrollment_id, from_level_id, to_level_id, reason, changed_by) "
				+ "values (?, ?, ?, ?, ?)", enrollment.id(), enrollment.currentLevelId(), target, reason, userId);
		return new AttemptResultDto(true, challengeLevel + 1, true, false);
	}

	// ---- roadmap ----

	private RoadmapDto buildRoadmap(Enrollment enrollment) {
		List<WorkoutRow> workouts = new ArrayList<>();
		Map<Integer, String[]> levels = new LinkedHashMap<>();
		jdbc.query("""
				select l.id as level_id, l.level_number, l.name_he as level_name,
				       w.id as workout_id, w.sort_order, w.type, w.sport, w.title_he, w.est_minutes
				from plan_levels l
				left join workouts w on w.level_id = l.id and w.status = 'published'
				where l.plan_id = ?
				order by l.level_number, w.sort_order
				""", rs -> {
					int number = rs.getInt("level_number");
					levels.putIfAbsent(number, new String[] { rs.getString("level_id"), rs.getString("level_name") });
					UUID workoutId = rs.getObject("workout_id", UUID.class);
					if (workoutId != null) {
						workouts.add(new WorkoutRow(workoutId, rs.getObject("level_id", UUID.class), number,
								rs.getInt("sort_order"), rs.getString("type"), rs.getString("sport"),
								rs.getString("title_he"), (Integer) rs.getObject("est_minutes")));
					}
				}, enrollment.planId());
		Set<UUID> done = new HashSet<>(jdbc.query(
				"select workout_id from workout_progress where enrollment_id = ? and status = 'completed'",
				(rs, i) -> rs.getObject("workout_id", UUID.class), enrollment.id()));

		Computed computed = compute(levels, workouts, done, enrollment.currentLevelNumber());
		return new RoadmapDto(enrollment.id().toString(), enrollment.planSlug(), enrollment.planNameHe(),
				enrollment.currentLevelNumber(), computed.planCompleted(), computed.levels());
	}

	record Computed(List<RoadmapLevelDto> levels, boolean planCompleted) {
	}

	/**
	 * The roadmap rules, kept free of the database so they can be tested. {@code levels} maps level number to
	 * [level id, level name]; {@code workouts} are the published workouts ordered by level and position.
	 */
	static Computed compute(Map<Integer, String[]> levels, List<WorkoutRow> workouts, Set<UUID> done, int current) {
		int top = levels.keySet().stream().mapToInt(Integer::intValue).max().orElse(1);
		List<RoadmapLevelDto> out = new ArrayList<>();
		boolean planCompleted = false;
		for (Map.Entry<Integer, String[]> level : levels.entrySet()) {
			int number = level.getKey();
			List<WorkoutRow> inLevel = workouts.stream().filter(w -> w.levelNumber() == number).toList();
			boolean allDone = inLevel.stream().allMatch(w -> done.contains(w.id()));
			boolean currentFound = false;
			boolean previousDone = true;
			List<RoadmapWorkoutDto> items = new ArrayList<>();
			for (WorkoutRow w : inLevel) {
				boolean isDone = done.contains(w.id());
				boolean isChallenge = Vocabulary.TYPE_CHALLENGE.equals(w.type());
				String state;
				if (isDone) {
					state = DONE;
				}
				else if (number < current) {
					state = SKIPPED;
				}
				else if (number == current && !currentFound) {
					state = CURRENT;
					currentFound = true;
				}
				else {
					state = LOCKED;
				}
				boolean canAttempt = isChallenge && !isDone && ((number == current && previousDone)
						|| (number > current && number < top));
				items.add(new RoadmapWorkoutDto(w.id().toString(), w.sortOrder(), w.type(), w.sport(), w.titleHe(),
						w.estMinutes(), state, canAttempt));
				previousDone = previousDone && isDone;
				if (isChallenge && isDone && number == top) {
					planCompleted = true;
				}
			}
			String levelState = number == current ? CURRENT
					: number < current ? (allDone ? DONE : SKIPPED) : (allDone && !inLevel.isEmpty() ? DONE : LOCKED);
			out.add(new RoadmapLevelDto(level.getValue()[0], number, level.getValue()[1], levelState, items));
		}
		return new Computed(out, planCompleted);
	}

	private static RoadmapWorkoutDto findInRoadmap(RoadmapDto roadmap, UUID workoutId) {
		String id = workoutId.toString();
		for (RoadmapLevelDto level : roadmap.levels()) {
			for (RoadmapWorkoutDto w : level.workouts()) {
				if (w.id().equals(id)) {
					return w;
				}
			}
		}
		throw new ApiException(HttpStatus.NOT_FOUND, "workout_not_found");
	}

	private void requireCurrent(Enrollment enrollment, UUID workoutId) {
		RoadmapWorkoutDto entry = findInRoadmap(buildRoadmap(enrollment), workoutId);
		if (!CURRENT.equals(entry.state())) {
			throw new ApiException(HttpStatus.FORBIDDEN, "workout_locked");
		}
	}

	// ---- lookups ----

	private void markCompleted(UUID userId, UUID enrollmentId, UUID workoutId) {
		jdbc.update("""
				insert into workout_progress (user_id, enrollment_id, workout_id, status, completed_at)
				values (?, ?, ?, 'completed', now())
				on conflict (enrollment_id, workout_id)
				do update set status = 'completed', completed_at = coalesce(workout_progress.completed_at, now())
				""", userId, enrollmentId, workoutId);
	}

	private WorkoutRow publishedWorkout(UUID workoutId) {
		return jdbc.query("""
				select w.id, w.level_id, l.level_number, w.sort_order, w.type, w.sport, w.title_he, w.est_minutes
				from workouts w join plan_levels l on l.id = w.level_id
				where w.id = ? and w.status = 'published'
				""", (rs, i) -> new WorkoutRow(rs.getObject("id", UUID.class), rs.getObject("level_id", UUID.class),
				rs.getInt("level_number"), rs.getInt("sort_order"), rs.getString("type"), rs.getString("sport"),
				rs.getString("title_he"), (Integer) rs.getObject("est_minutes")), workoutId).stream().findFirst()
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "workout_not_found"));
	}

	/** A regular workout: challenges are finished through the attempt endpoint instead. */
	private WorkoutRow playableWorkout(UUID userId, UUID workoutId) {
		WorkoutRow workout = publishedWorkout(workoutId);
		if (Vocabulary.TYPE_CHALLENGE.equals(workout.type())) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "use_challenge_attempt");
		}
		return workout;
	}

	private Enrollment enrollment(UUID userId, String planSlug, boolean lock) {
		return query("where e.user_id = ? and p.slug = ? and e.status <> 'ended'", lock, userId, planSlug);
	}

	private Enrollment enrollmentForLevel(UUID userId, UUID levelId, boolean lock) {
		return query("where e.user_id = ? and e.status <> 'ended' and e.plan_id = "
				+ "(select plan_id from plan_levels where id = ?)", lock, userId, levelId);
	}

	private Enrollment query(String where, boolean lock, Object... params) {
		Enrollment enrollment = jdbc.query("""
				select e.id, e.user_id, e.plan_id, p.slug, p.name_he, e.current_level_id, l.level_number,
				       l.name_he as level_name, e.status
				from enrollments e
				join plans p on p.id = e.plan_id
				join plan_levels l on l.id = e.current_level_id
				""" + where + (lock ? " for update of e" : ""),
				(rs, i) -> new Enrollment(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
						rs.getObject("plan_id", UUID.class), rs.getString("slug"), rs.getString("name_he"),
						rs.getObject("current_level_id", UUID.class), rs.getInt("level_number"),
						rs.getString("level_name"), rs.getString("status")),
				params).stream().findFirst()
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not_enrolled"));
		if ("paused".equals(enrollment.status())) {
			throw new ApiException(HttpStatus.FORBIDDEN, "enrollment_paused");
		}
		return enrollment;
	}

	private int topLevelNumber(UUID planId) {
		Integer top = jdbc.queryForObject("select max(level_number) from plan_levels where plan_id = ?", Integer.class,
				planId);
		return top == null ? 1 : top;
	}

	private UUID levelId(UUID planId, int levelNumber) {
		return jdbc.queryForObject("select id from plan_levels where plan_id = ? and level_number = ?", UUID.class,
				planId, levelNumber);
	}

}
