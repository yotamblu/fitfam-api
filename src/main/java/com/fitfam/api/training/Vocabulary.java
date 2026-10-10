package com.fitfam.api.training;

import java.util.Set;

/** The fixed lists that exercises and workouts may use. Mirrored by CHECK constraints in the database. */
public final class Vocabulary {

	public static final Set<String> SPORTS = Set.of("running", "swimming", "gym", "calisthenics");
	public static final Set<String> MEASURES = Set.of("reps", "hold_time", "distance", "duration", "calories",
			"max_effort");

	public static final String TYPE_REGULAR = "regular";
	public static final String TYPE_CHALLENGE = "challenge";

	public static final String STATUS_DRAFT = "draft";
	public static final String STATUS_PUBLISHED = "published";
	public static final String STATUS_ARCHIVED = "archived";

	private Vocabulary() {
	}

}
