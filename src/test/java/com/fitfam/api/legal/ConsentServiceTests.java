package com.fitfam.api.legal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

import com.fitfam.api.web.ApiException;

class ConsentServiceTests {

	private static ConsentService.HealthRequest request(String version, boolean declaration, boolean confirm, String name) {
		return new ConsentService.HealthRequest(version, declaration, confirm, confirm, confirm, confirm, name);
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
	void aCompleteDeclarationIsValid() {
		assertThatCode(() -> ConsentService.validateHealth(request(LegalVersions.HEALTH, true, true, "Dana Test")))
				.doesNotThrowAnyException();
	}

	@Test
	void theConditionsDeclarationAndEveryConfirmationAreRequired() {
		assertThat(codeOf(() -> ConsentService.validateHealth(request(LegalVersions.HEALTH, false, true, "Dana Test"))))
				.isEqualTo("confirmations_required");
		assertThat(codeOf(() -> ConsentService.validateHealth(request(LegalVersions.HEALTH, true, false, "Dana Test"))))
				.isEqualTo("confirmations_required");
	}

	@Test
	void aNameIsRequired() {
		assertThat(codeOf(() -> ConsentService.validateHealth(request(LegalVersions.HEALTH, true, true, " ")))).isEqualTo("name_required");
		assertThat(codeOf(() -> ConsentService.validateHealth(request(LegalVersions.HEALTH, true, true, null)))).isEqualTo("name_required");
	}

	@Test
	void anOldVersionIsRefused() {
		assertThat(codeOf(() -> ConsentService.validateHealth(request("1999-01-01", true, true, "Dana Test"))))
				.isEqualTo("version_outdated");
	}

}
