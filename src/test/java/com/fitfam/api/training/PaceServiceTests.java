package com.fitfam.api.training;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fitfam.api.training.PaceService.Pace;
import com.fitfam.api.training.PaceService.PaceProfile;
import com.fitfam.api.training.PaceService.Range;

class PaceServiceTests {

	private static Pace pace(String kind, Integer fiveKSec, Integer distanceM) {
		return PaceService.pace(kind, PaceProfile.of(fiveKSec), distanceM);
	}

	@Test
	void pacesComeFromTheFitnessScoreAndAreRoundedToFiveSeconds() {
		// 24:30 for 5 km: score 39.2, 5 km pace 4:54
		assertThat(PaceService.vdot(1470)).isBetween(39.0, 39.4);
		Pace easy = pace("easy", 1470, null);
		assertThat(easy.fastSecPerKm()).isEqualTo(390); // 6:30
		assertThat(easy.slowSecPerKm()).isEqualTo(410); // 6:50
		Pace threshold = pace("threshold", 1470, null);
		assertThat(threshold.fastSecPerKm()).isEqualTo(310); // 5:10
		assertThat(threshold.slowSecPerKm()).isEqualTo(315);
		Pace interval = pace("interval", 1470, null);
		assertThat(interval.fastSecPerKm()).isEqualTo(285); // 4:45
		assertThat(interval.slowSecPerKm()).isEqualTo(290);
		Pace fiveK = pace("fivek", 1470, null);
		assertThat(fiveK.fastSecPerKm()).isEqualTo(295); // 4:54 rounded
		assertThat(fiveK.slowSecPerKm()).isEqualTo(295);
		assertThat(easy.custom()).isFalse();
	}

	@Test
	void fasterRunnersGetFasterPacesAndEveryRangeIsOrdered() {
		for (int sec = PaceService.MIN_FIVE_K_SEC; sec <= PaceService.MAX_FIVE_K_SEC; sec += 60) {
			Pace easy = pace("easy", sec, null);
			Pace threshold = pace("threshold", sec, null);
			Pace interval = pace("interval", sec, null);
			assertThat(easy.fastSecPerKm()).isLessThanOrEqualTo(easy.slowSecPerKm());
			assertThat(threshold.fastSecPerKm()).isLessThanOrEqualTo(threshold.slowSecPerKm());
			assertThat(interval.fastSecPerKm()).isLessThanOrEqualTo(interval.slowSecPerKm());
			assertThat(easy.fastSecPerKm()).isGreaterThan(threshold.slowSecPerKm());
			assertThat(threshold.fastSecPerKm()).isGreaterThan(interval.slowSecPerKm() - 15);
		}
		assertThat(pace("easy", 1200, null).slowSecPerKm()).isLessThan(pace("easy", 1800, null).fastSecPerKm());
	}

	@Test
	void anOwnRangeReplacesTheCalculatedOneAndKeepsItNearby() {
		PaceProfile profile = new PaceProfile(1470, Map.of("easy", new Range(400, 430)));
		Pace easy = PaceService.pace("easy", profile, 1000);
		assertThat(easy.custom()).isTrue();
		assertThat(easy.fastSecPerKm()).isEqualTo(400);
		assertThat(easy.slowSecPerKm()).isEqualTo(430);
		assertThat(easy.repFastSec()).isEqualTo(400);
		assertThat(easy.calcFastSecPerKm()).isEqualTo(390);
		assertThat(easy.calcSlowSecPerKm()).isEqualTo(410);
		Pace threshold = PaceService.pace("threshold", profile, null);
		assertThat(threshold.custom()).isFalse();
		assertThat(threshold.fastSecPerKm()).isEqualTo(310);
	}

	@Test
	void aDistanceRepeatGetsATargetTime() {
		Pace fiveK = pace("fivek", 1500, 400); // 5:00 per km
		assertThat(fiveK.repFastSec()).isEqualTo(120);
		assertThat(fiveK.repSlowSec()).isEqualTo(120);
		assertThat(pace("fivek", 1500, 1000).repFastSec()).isEqualTo(300);
		assertThat(pace("fivek", 1500, 1200).repFastSec()).isEqualTo(360);
		assertThat(pace("fivek", 1500, null).repFastSec()).isNull();
	}

	@Test
	void withoutAFiveKTimeOnlyTheDescriptionIsFilledIn() {
		Pace pace = pace("threshold", null, 800);
		assertThat(pace.fastSecPerKm()).isNull();
		assertThat(pace.repFastSec()).isNull();
		assertThat(pace.labelHe()).isNotBlank();
		assertThat(pace.feelHe()).isNotBlank();
		assertThat(PaceService.pace("threshold", null, 800).fastSecPerKm()).isNull();
	}

	@Test
	void onlyTheFourKindsExistAndThreeCanBeEdited() {
		assertThat(PaceService.KINDS).allMatch(PaceService::isKind);
		assertThat(PaceService.isKind("sprint")).isFalse();
		assertThat(PaceService.isKind(null)).isFalse();
		assertThat(PaceService.EDITABLE_KINDS).containsExactly("easy", "threshold", "interval");
	}
}
