package com.fitfam.api.training;

import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The game layer of the customer app, derived on the fly from completed workouts (nothing extra is stored, so the
 * numbers can never drift from the roadmap): points per workout, an account level, and a weekly streak.
 *
 * <ul>
 * <li>A finished workout is worth {@value #WORKOUT_XP} points, a passed level challenge {@value #CHALLENGE_XP}.
 * Skipped levels give nothing.</li>
 * <li>Account level n starts at {@code 150 * n * (n - 1) / 2} points.</li>
 * <li>A week runs Sunday to Saturday (Israel time). The weekly streak counts consecutive weeks with at least one
 * finished workout; the current week only breaks it once it has ended.</li>
 * </ul>
 */
@Service
public class StatsService {

	static final int WORKOUT_XP = 100;
	static final int CHALLENGE_XP = 300;
	static final int WEEKLY_GOAL = 3;
	static final int RECENT_WEEKS = 6;
	static final ZoneId ZONE = ZoneId.of("Asia/Jerusalem");

	public record Completion(Instant at, boolean challenge) {
	}

	public record Rewards(int workout, int challenge) {
	}

	public record StatsDto(int xp, int level, int xpIntoLevel, int xpForNextLevel, int totalWorkouts,
			int challengesPassed, int weeklyGoal, int weekCount, List<Boolean> weekDays, int todayIndex,
			int weeklyStreak, List<Integer> recentWeeks, Rewards rewards) {
	}

	private final JdbcTemplate jdbc;

	public StatsService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public StatsDto stats(UUID userId) {
		List<Completion> completions = jdbc.query("""
				select wp.completed_at, w.type
				from workout_progress wp
				join workouts w on w.id = wp.workout_id
				where wp.user_id = ? and wp.status = 'completed' and wp.completed_at is not null
				""", (rs, i) -> {
					Timestamp at = rs.getTimestamp("completed_at");
					return new Completion(at.toInstant(), Vocabulary.TYPE_CHALLENGE.equals(rs.getString("type")));
				}, userId);
		return compute(completions, Instant.now(), ZONE);
	}

	/** Pure function of the completions, so the rules are unit tested without a database. */
	static StatsDto compute(List<Completion> completions, Instant now, ZoneId zone) {
		int challenges = (int) completions.stream().filter(Completion::challenge).count();
		int workouts = completions.size() - challenges;
		int xp = workouts * WORKOUT_XP + challenges * CHALLENGE_XP;

		int level = 1;
		while (xpAtLevel(level + 1) <= xp) {
			level++;
		}

		LocalDate today = now.atZone(zone).toLocalDate();
		LocalDate thisWeek = weekStart(today);
		Set<LocalDate> activeDays = new HashSet<>();
		for (Completion c : completions) {
			activeDays.add(c.at().atZone(zone).toLocalDate());
		}
		Set<LocalDate> activeWeeks = new HashSet<>();
		for (LocalDate day : activeDays) {
			activeWeeks.add(weekStart(day));
		}

		List<Boolean> weekDays = new ArrayList<>();
		for (int d = 0; d < 7; d++) {
			weekDays.add(activeDays.contains(thisWeek.plusDays(d)));
		}
		int weekCount = (int) completions.stream()
				.filter(c -> weekStart(c.at().atZone(zone).toLocalDate()).equals(thisWeek)).count();

		LocalDate cursor = activeWeeks.contains(thisWeek) ? thisWeek : thisWeek.minusWeeks(1);
		int streak = 0;
		while (activeWeeks.contains(cursor)) {
			streak++;
			cursor = cursor.minusWeeks(1);
		}

		List<Integer> recent = new ArrayList<>();
		for (int w = RECENT_WEEKS - 1; w >= 0; w--) {
			LocalDate start = thisWeek.minusWeeks(w);
			recent.add((int) completions.stream()
					.filter(c -> weekStart(c.at().atZone(zone).toLocalDate()).equals(start)).count());
		}

		return new StatsDto(xp, level, xp - xpAtLevel(level), xpAtLevel(level + 1) - xpAtLevel(level),
				completions.size(), challenges, WEEKLY_GOAL, weekCount, weekDays, today.getDayOfWeek().getValue() % 7,
				streak, recent, new Rewards(WORKOUT_XP, CHALLENGE_XP));
	}

	static int xpAtLevel(int level) {
		return 150 * level * (level - 1) / 2;
	}

	private static LocalDate weekStart(LocalDate day) {
		return day.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY));
	}

}
