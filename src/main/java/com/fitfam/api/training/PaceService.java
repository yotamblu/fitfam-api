package com.fitfam.api.training;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fitfam.api.web.ApiException;

/**
 * Personal running paces. The customer enters a recent 5 km time; the four paces (easy, threshold, interval, 5 km) are
 * computed from it on request. Only the 5 km time is stored.
 *
 * <p>
 * Each pace is a percentage band of the 5 km SPEED (not of the minutes per km): pace = 5 km pace / fraction of speed.
 * The bands (easy 77-83 %, threshold 93-96 %, interval 100-105 %, 5 km 100 %) agree with Jack Daniels' VDOT paces for
 * recreational times. Displayed paces are rounded to the nearest 5 seconds to avoid false precision; a per-repeat
 * target time for a distance repeat is rounded to the nearest second.
 */
@Service
public class PaceService {

	public static final int MIN_FIVE_K_SEC = 720;
	public static final int MAX_FIVE_K_SEC = 4200;

	public static final List<String> KINDS = List.of("easy", "threshold", "interval", "fivek");

	/** fraction of the 5 km speed: {slowest, fastest} */
	private static final Map<String, double[]> BANDS = Map.of(
			"easy", new double[] { 0.77, 0.83 },
			"threshold", new double[] { 0.93, 0.96 },
			"interval", new double[] { 1.00, 1.05 },
			"fivek", new double[] { 1.00, 1.00 });

	private static final Map<String, String> LABELS = Map.of(
			"easy", "קצב ריצה קלה",
			"threshold", "קצב סף",
			"interval", "קצב אינטרוולים",
			"fivek", "קצב 5 ק״מ");

	private static final Map<String, String> FEELS = Map.of(
			"easy", "נוח; אפשר לדבר במשפטים שלמים",
			"threshold", "קשה אבל נשלט; משפטים קצרים",
			"interval", "קשה; שומרים אותו לאורך כל החזרות בלי קריסה",
			"fivek", "הקצב הממוצע של תוצאת ה־5 ק״מ שלך");

	/** The fast and slow end of a pace (seconds per km) and, for a distance repeat, the target time of one repeat. */
	public record Pace(String kind, String labelHe, String feelHe, Integer fastSecPerKm, Integer slowSecPerKm,
			Integer repFastSec, Integer repSlowSec) {
	}

	public record PaceStatus(Integer fiveKSec, String updatedAt, boolean needed, List<Pace> paces) {
	}

	private final JdbcTemplate jdbc;

	public PaceService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	// ---- the pure calculation ----

	public static boolean isKind(String kind) {
		return kind != null && BANDS.containsKey(kind);
	}

	/** The pace for {@code kind}; with no 5 km time (null) only the name and description are filled in. */
	public static Pace pace(String kind, Integer fiveKSec, Integer distanceM) {
		if (fiveKSec == null) {
			return new Pace(kind, LABELS.get(kind), FEELS.get(kind), null, null, null, null);
		}
		double fiveKPaceSec = fiveKSec / 5.0;
		double[] band = BANDS.get(kind);
		double slow = fiveKPaceSec / band[0];
		double fast = fiveKPaceSec / band[1];
		Integer repFast = null;
		Integer repSlow = null;
		if (distanceM != null) {
			repFast = (int) Math.round(fast * distanceM / 1000.0);
			repSlow = (int) Math.round(slow * distanceM / 1000.0);
		}
		return new Pace(kind, LABELS.get(kind), FEELS.get(kind), roundTo5(fast), roundTo5(slow), repFast, repSlow);
	}

	static int roundTo5(double seconds) {
		return (int) (Math.round(seconds / 5.0) * 5);
	}

	// ---- the customer's 5 km time ----

	public Integer fiveKSec(UUID userId) {
		List<Integer> rows = jdbc.query("select five_k_sec from users where id = ?",
				(rs, i) -> (Integer) rs.getObject("five_k_sec"), userId);
		return rows.isEmpty() ? null : rows.get(0);
	}

	public PaceStatus status(UUID userId) {
		return jdbc.query("select five_k_sec, five_k_updated_at::text as updated_at from users where id = ?", (rs, i) -> {
			Integer sec = (Integer) rs.getObject("five_k_sec");
			List<Pace> paces = sec == null ? List.of() : KINDS.stream().map(k -> pace(k, sec, null)).toList();
			return new PaceStatus(sec, rs.getString("updated_at"), sec == null && usesPaces(userId), paces);
		}, userId).stream().findFirst().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not_found"));
	}

	public PaceStatus set(UUID userId, int fiveKSec) {
		if (fiveKSec < MIN_FIVE_K_SEC || fiveKSec > MAX_FIVE_K_SEC) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_five_k");
		}
		jdbc.update("update users set five_k_sec = ?, five_k_updated_at = now() where id = ?", fiveKSec, userId);
		return status(userId);
	}

	public PaceStatus clear(UUID userId) {
		jdbc.update("update users set five_k_sec = null, five_k_updated_at = null where id = ?", userId);
		return status(userId);
	}

	/**
	 * True when a 5 km time is useful to the customer: they follow a plan flagged {@code uses_paces}, or a plan with a
	 * published workout that is a running workout or contains a run segment (so a mixed plan qualifies as soon as it has one).
	 */
	boolean usesPaces(UUID userId) {
		Boolean found = jdbc.queryForObject("""
				select exists (
				    select 1 from enrollments e
				    join plans p on p.id = e.plan_id
				    where e.user_id = ? and e.status <> 'ended' and (
				        p.uses_paces
				        or exists (
				            select 1 from plan_levels l
				            join workouts w on w.level_id = l.id and w.status = 'published'
				            where l.plan_id = p.id and (
				                w.sport = 'running'
				                or jsonb_path_exists(w.details,
				                    '$.sections[*].blocks[*].lines[*] ? (@.activity == "run")')))))
				""", Boolean.class, userId);
		return Boolean.TRUE.equals(found);
	}

}
