package com.fitfam.api.training;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fitfam.api.training.StatsService.Completion;
import com.fitfam.api.training.StatsService.StatsDto;

class StatsServiceTests {

	private static final ZoneId ZONE = ZoneId.of("Asia/Jerusalem");
	// Wednesday 2026-10-14, noon (the week runs Sunday 2026-10-11 to Saturday 2026-10-17)
	private static final Instant NOW = LocalDate.of(2026, 10, 14).atTime(12, 0).atZone(ZONE).toInstant();

	private static Completion at(LocalDate day, boolean challenge) {
		return new Completion(day.atTime(9, 0).atZone(ZONE).toInstant(), challenge);
	}

	private static Completion at(LocalDate day) {
		return at(day, false);
	}

	@Test
	void noWorkoutsMeansLevelOneAndNoStreak() {
		StatsDto s = StatsService.compute(List.of(), NOW, ZONE);
		assertThat(s.xp()).isZero();
		assertThat(s.level()).isEqualTo(1);
		assertThat(s.xpForNextLevel()).isEqualTo(150);
		assertThat(s.weeklyStreak()).isZero();
		assertThat(s.todayIndex()).isEqualTo(3);
		assertThat(s.weekDays()).containsOnly(false);
		assertThat(s.recentWeeks()).containsExactly(0, 0, 0, 0, 0, 0);
	}

	@Test
	void workoutsAndChallengesGivePointsAndLevels() {
		List<Completion> done = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			done.add(at(LocalDate.of(2026, 10, 1)));
		}
		done.add(at(LocalDate.of(2026, 10, 2), true));
		StatsDto s = StatsService.compute(done, NOW, ZONE);
		assertThat(s.xp()).isEqualTo(4 * 100 + 300);
		assertThat(s.totalWorkouts()).isEqualTo(5);
		assertThat(s.challengesPassed()).isEqualTo(1);
		// level 3 starts at 450 points, level 4 at 900
		assertThat(s.level()).isEqualTo(3);
		assertThat(s.xpIntoLevel()).isEqualTo(250);
		assertThat(s.xpForNextLevel()).isEqualTo(450);
	}

	@Test
	void theWeeklyStreakCountsConsecutiveWeeksAndSurvivesAnEmptyCurrentWeek() {
		// the weeks of Oct 4 and Sep 27 have a workout, the current week (Oct 11) does not yet
		List<Completion> done = List.of(at(LocalDate.of(2026, 10, 6)), at(LocalDate.of(2026, 9, 29)));
		assertThat(StatsService.compute(done, NOW, ZONE).weeklyStreak()).isEqualTo(2);
		List<Completion> withCurrent = List.of(at(LocalDate.of(2026, 10, 12)), at(LocalDate.of(2026, 10, 6)),
				at(LocalDate.of(2026, 9, 29)));
		StatsDto s = StatsService.compute(withCurrent, NOW, ZONE);
		assertThat(s.weeklyStreak()).isEqualTo(3);
		assertThat(s.weekCount()).isEqualTo(1);
		assertThat(s.weekDays().get(1)).isTrue(); // Monday
	}

	@Test
	void aMissedWholeWeekBreaksTheStreak() {
		List<Completion> done = List.of(at(LocalDate.of(2026, 10, 12)), at(LocalDate.of(2026, 9, 29)));
		assertThat(StatsService.compute(done, NOW, ZONE).weeklyStreak()).isEqualTo(1);
		assertThat(StatsService.compute(List.of(at(LocalDate.of(2026, 9, 29))), NOW, ZONE).weeklyStreak()).isZero();
	}

	@Test
	void recentWeeksAreOldestFirstEndingWithTheCurrentWeek() {
		List<Completion> done = List.of(at(LocalDate.of(2026, 10, 12)), at(LocalDate.of(2026, 10, 13)),
				at(LocalDate.of(2026, 10, 5)));
		assertThat(StatsService.compute(done, NOW, ZONE).recentWeeks()).containsExactly(0, 0, 0, 0, 1, 2);
	}

}
