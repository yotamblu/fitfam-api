package com.fitfam.api.legal;

/**
 * The versions of the legal texts a customer must have accepted. The texts themselves live in the web app; when one
 * changes, bump its version here AND there, and every customer is asked again. A health declaration is valid for two
 * years (the period Israeli gym regulations use; set in ConsentService), then a new one is due.
 */
public final class LegalVersions {

	public static final String TERMS = "2026-10-29";
	public static final String PRIVACY = "2026-10-29";
	public static final String HEALTH = "2026-10-30";

	private LegalVersions() {
	}

}
