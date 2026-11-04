package com.fitfam.api.training;

import java.math.BigDecimal;
import java.time.Instant;
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

import com.fitfam.api.training.TrainingDtos.CustomerWorkoutDto;
import com.fitfam.api.training.TrainingDtos.LogSetDto;
import com.fitfam.api.training.TrainingDtos.LogSetRequest;
import com.fitfam.api.training.TrainingDtos.PreviousLogDto;
import com.fitfam.api.training.TrainingDtos.WorkoutLogDto;
import com.fitfam.api.web.ApiException;

import tools.jackson.databind.JsonNode;

/**
 * The optional workout log: the weight and reps a customer writes down per set. Nothing here is required to finish a
 * workout. A log can be written and changed only while the workout is open for the customer (current, or open in a
 * skipped level), so a replay of a finished workout never overwrites what was written the first time.
 */
@Service
public class WorkoutLogService {

	private final JdbcTemplate jdbc;
	private final ProgressService progress;

	public WorkoutLogService(JdbcTemplate jdbc, ProgressService progress) {
		this.jdbc = jdbc;
		this.progress = progress;
	}

	/** The sets logged for this workout, and for each of its exercises what was logged the last time before. */
	@Transactional(readOnly = true)
	public WorkoutLogDto get(UUID userId, UUID workoutId) {
		CustomerWorkoutDto workout = progress.openWorkout(userId, workoutId);
		Set<UUID> exerciseIds = exerciseIds(workout.content());
		List<LogSetDto> sets = jdbc.query("""
				select exercise_id, set_number, weight_kg, reps from set_logs
				where user_id = ? and workout_id = ? order by exercise_id, set_number
				""", (rs, i) -> new LogSetDto(rs.getString("exercise_id"), rs.getInt("set_number"),
				rs.getBigDecimal("weight_kg"), (Integer) rs.getObject("reps")), userId, workoutId);
		return new WorkoutLogDto(workoutId.toString(), editable(workout), sets, previous(userId, workoutId, exerciseIds));
	}

	/** Saves one set (an empty one removes it). */
	@Transactional
	public void save(UUID userId, UUID workoutId, LogSetRequest request) {
		CustomerWorkoutDto workout = progress.openWorkout(userId, workoutId);
		if (!editable(workout)) {
			throw new ApiException(HttpStatus.CONFLICT, "log_closed");
		}
		if (!exerciseIds(workout.content()).contains(request.exerciseId())) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "exercise_not_in_workout");
		}
		BigDecimal weight = request.weightKg() == null ? null : request.weightKg().setScale(2, java.math.RoundingMode.HALF_UP);
		if (weight == null && request.reps() == null) {
			jdbc.update("delete from set_logs where user_id = ? and workout_id = ? and exercise_id = ? and set_number = ?",
					userId, workoutId, request.exerciseId(), request.setNumber());
			return;
		}
		jdbc.update("""
				insert into set_logs (user_id, workout_id, exercise_id, set_number, weight_kg, reps)
				values (?, ?, ?, ?, ?, ?)
				on conflict (user_id, workout_id, exercise_id, set_number)
				do update set weight_kg = excluded.weight_kg, reps = excluded.reps, updated_at = now()
				""", userId, workoutId, request.exerciseId(), request.setNumber(), weight, request.reps());
	}

	private static boolean editable(CustomerWorkoutDto workout) {
		return ProgressService.CURRENT.equals(workout.state()) || ProgressService.OPEN.equals(workout.state());
	}

	/** For every exercise: the sets of the most recent other workout in which this customer logged it. */
	private List<PreviousLogDto> previous(UUID userId, UUID workoutId, Set<UUID> exerciseIds) {
		if (exerciseIds.isEmpty()) {
			return List.of();
		}
		String marks = String.join(",", exerciseIds.stream().map(id -> "?").toList());
		List<Object> params = new ArrayList<>();
		params.add(userId);
		params.add(workoutId);
		params.addAll(exerciseIds);
		params.add(userId);
		params.add(workoutId);
		params.addAll(exerciseIds);
		record Row(String exerciseId, String title, Instant last, LogSetDto set) {
		}
		List<Row> rows = jdbc.query("""
				with sessions as (
				    select exercise_id, workout_id, max(updated_at) as last_at
				    from set_logs
				    where user_id = ? and workout_id <> ? and exercise_id in (%s)
				    group by exercise_id, workout_id
				), latest as (
				    select distinct on (exercise_id) exercise_id, workout_id, last_at
				    from sessions order by exercise_id, last_at desc
				)
				select s.exercise_id, s.set_number, s.weight_kg, s.reps, l.last_at, w.title_he
				from set_logs s
				join latest l on l.exercise_id = s.exercise_id and l.workout_id = s.workout_id
				join workouts w on w.id = s.workout_id
				where s.user_id = ? and s.workout_id <> ? and s.exercise_id in (%s)
				order by s.exercise_id, s.set_number
				""".formatted(marks, marks), (rs, i) -> new Row(rs.getString("exercise_id"), rs.getString("title_he"),
				rs.getTimestamp("last_at").toInstant(), new LogSetDto(rs.getString("exercise_id"), rs.getInt("set_number"),
						rs.getBigDecimal("weight_kg"), (Integer) rs.getObject("reps"))),
				params.toArray());
		Map<String, List<Row>> byExercise = new LinkedHashMap<>();
		for (Row row : rows) {
			byExercise.computeIfAbsent(row.exerciseId(), k -> new ArrayList<>()).add(row);
		}
		List<PreviousLogDto> out = new ArrayList<>();
		for (List<Row> group : byExercise.values()) {
			Row first = group.get(0);
			out.add(new PreviousLogDto(first.exerciseId(), first.title(), first.last(),
					group.stream().map(Row::set).toList()));
		}
		return out;
	}

	static Set<UUID> exerciseIds(JsonNode content) {
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
		return ids;
	}

}
