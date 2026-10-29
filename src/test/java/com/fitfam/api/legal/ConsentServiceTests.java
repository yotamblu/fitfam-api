package com.fitfam.api.legal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fitfam.api.web.ApiException;

class ConsentServiceTests {

	private static Map<String, Boolean> answers(boolean value) {
		Map<String, Boolean> map = new LinkedHashMap<>();
		LegalVersions.HEALTH_QUESTION_IDS.forEach(id -> map.put(id, value));
		return map;
	}

	private static ConsentService.HealthRequest request(Map<String, Boolean> answers, boolean clearance, boolean confirm,
			String name) {
		return new ConsentService.HealthRequest(LegalVersions.HEALTH, answers, clearance, confirm, confirm, confirm, confirm,
				name);
	}

	private static String codeOf(Runnable action) {
		try {
			action.run();
			return null;
		}
		catch (ApiException e) {
			return e.getCode();
		}
	}

	@Test
	void aCompleteDeclarationWithOnlyNoAnswersIsValid() {
		assertThatCode(() -> ConsentService.validateHealth(request(answers(false), false, true, "Dana Test")))
				.doesNotThrowAnyException();
	}

	@Test
	void aYesAnswerNeedsMedicalClearance() {
		Map<String, Boolean> yes = answers(false);
		yes.put("q3", true);
		assertThat(codeOf(() -> ConsentService.validateHealth(request(yes, false, true, "Dana Test"))))
				.isEqualTo("medical_clearance_required");
		assertThatCode(() -> ConsentService.validateHealth(request(yes, true, true, "Dana Test"))).doesNotThrowAnyException();
	}

	@Test
	void everyQuestionMustBeAnswered() {
		Map<String, Boolean> partial = answers(false);
		partial.remove("q4");
		assertThat(codeOf(() -> ConsentService.validateHealth(request(partial, false, true, "Dana Test"))))
				.isEqualTo("answers_incomplete");
		Map<String, Boolean> unknown = answers(false);
		unknown.remove("q4");
		unknown.put("other", false);
		assertThat(codeOf(() -> ConsentService.validateHealth(request(unknown, false, true, "Dana Test"))))
				.isEqualTo("answers_incomplete");
	}

	@Test
	void everyConfirmationAndANameAreRequired() {
		assertThat(codeOf(() -> ConsentService.validateHealth(request(answers(false), false, false, "Dana Test"))))
				.isEqualTo("confirmations_required");
		assertThat(codeOf(() -> ConsentService.validateHealth(request(answers(false), false, true, " "))))
				.isEqualTo("name_required");
	}

	@Test
	void anOldVersionIsRefused() {
		ConsentService.HealthRequest old = new ConsentService.HealthRequest("1999-01-01", answers(false), false, true, true,
				true, true, "Dana Test");
		assertThatThrownBy(() -> ConsentService.validateHealth(old)).isInstanceOf(ApiException.class);
		assertThat(codeOf(() -> ConsentService.validateHealth(old))).isEqualTo("version_outdated");
	}

}
