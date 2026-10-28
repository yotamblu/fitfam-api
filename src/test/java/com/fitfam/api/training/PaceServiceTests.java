package com.fitfam.api.training;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fitfam.api.training.PaceService.Pace;

class PaceServiceTests {

	@Test
	void pacesComeFromTheSpeedBandsAndAreRoundedToFiveSeconds() {
		// 20:00 for 5 km = 4:00 per km
		Pace easy = PaceService.pace("easy", 1200, null);
		assertThat(easy.fastSecPerKm()).isEqualTo(290); // 240 / 0.83 = 289.2
		assertThat(easy.slowSecPerKm()).isEqualTo(310); // 240 / 0.77 = 311.7
		Pace threshold = PaceService.pace("threshold", 1200, null);
		assertThat(threshold.fastSecPerKm()).isEqualTo(250); // 240 / 0.96
		assertThat(threshold.slowSecPerKm()).isEqualTo(260); // 240 / 0.93 = 258.1
		Pace interval = PaceService.pace("interval", 1200, null);
		assertThat(interval.fastSecPerKm()).isEqualTo(230);
		assertThat(interval.slowSecPerKm()).isEqualTo(240);
		Pace fiveK = PaceService.pace("fivek", 1200, null);
		assertThat(fiveK.fastSecPerKm()).isEqualTo(240);
		assertThat(fiveK.slowSecPerKm()).isEqualTo(240);
	}

	@Test
	void aDistanceRepeatGetsATargetTime() {
		Pace fiveK = PaceService.pace("fivek", 1500, 400); // 5:00 per km
		assertThat(fiveK.repFastSec()).isEqualTo(120);
		assertThat(fiveK.repSlowSec()).isEqualTo(120);
		assertThat(PaceService.pace("fivek", 1500, 1000).repFastSec()).isEqualTo(300);
		assertThat(PaceService.pace("fivek", 1500, 1200).repFastSec()).isEqualTo(360);
		assertThat(PaceService.pace("fivek", 1500, null).repFastSec()).isNull();
	}

	@Test
	void withoutAFiveKTimeOnlyTheDescriptionIsFilledIn() {
		Pace pace = PaceService.pace("threshold", null, 800);
		assertThat(pace.fastSecPerKm()).isNull();
		assertThat(pace.repFastSec()).isNull();
		assertThat(pace.labelHe()).isNotBlank();
		assertThat(pace.feelHe()).isNotBlank();
	}

	@Test
	void onlyTheFourKindsExist() {
		assertThat(PaceService.KINDS).allMatch(PaceService::isKind);
		assertThat(PaceService.isKind("sprint")).isFalse();
		assertThat(PaceService.isKind(null)).isFalse();
	}
}
