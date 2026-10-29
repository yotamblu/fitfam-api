package com.fitfam.api.legal;

import java.util.List;

/**
 * The versions of the legal texts a customer must have accepted. The texts themselves live in the web app; when one
 * changes, bump its version here AND there, and every customer is asked again. A health declaration is valid for two
 * years (the period Israeli gym regulations use; set in ConsentService), then a new one is due.
 */
public final class LegalVersions {

	public static final String TERMS = "2026-10-29";
	public static final String PRIVACY = "2026-10-29";
	public static final String HEALTH = "2026-10-29";

	/** The yes/no questions of the health declaration, in the order the web app shows them. */
	public static final List<String> HEALTH_QUESTION_IDS = List.of("q1", "q2", "q3", "q4", "q5", "q6", "q7", "q8", "q9");

	private LegalVersions() {
	}

}
