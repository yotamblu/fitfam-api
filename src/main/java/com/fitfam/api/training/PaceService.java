package com.fitfam.api.training;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.fitfam.api.web.ApiException;

/**
 * Personal running paces. The customer enters a recent 5 km time; the four paces (easy, threshold, interval, 5 km) are
 * computed from it on request. The 5 km time is stored, plus, optionally, the customer's own range for easy, threshold
 * or interval (table {@code pace_overrides}); an own range replaces the calculated one until the 5 km time changes.
 *
 * <p>
 * The calculation follows Daniels and Gilbert: the 5 km time gives a fitness score (VDOT, an oxygen-uptake figure), and
 * each pace is a share of that score (easy 62-66 %, threshold 86-88 %, interval 95-98 %); the 5 km pace is simply the
 * time divided by 5. Displayed paces are rounded to the nearest 5 seconds to avoid false precision; a per-repeat target
 * time for a distance repeat is rounded to the nearest second.
 */
@Service
public class PaceService {

	public static final int MIN_FIVE_K_SEC = 720;
	public static final int MAX_FIVE_K_SEC = 4200;

	/** limits of a customer's own range, seconds per km (2:00 to 20:00) */
	public static final int MIN_ZONE_SEC = 120;
	public static final int MAX_ZONE_SEC = 1200;

	public static final List<String> KINDS = List.of("easy", "threshold", "interval", "fivek");

	/** the kinds a customer may change (the 5 km pace follows from the 5 km time itself) */
	public static final List<String> EDITABLE_KINDS = List.of("easy", "threshold", "interval");

	/** share of the VDOT oxygen figure: {slowest, fastest} */
	private static final Map<String, double[]> SHARES = Map.of(
			"easy", new double[] { 0.62, 0.66 },
			"threshold", new double[] { 0.86, 0.88 },
			"interval", new double[] { 0.95, 0.98 });

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

	/** A pace range in seconds per km. */
	public record Range(int fastSecPerKm, int slowSecPerKm) {
	}

	/** What a customer's paces are made from: the 5 km time (or null) and their own ranges, by kind. */
	public record PaceProfile(Integer fiveKSec, Map<String, Range> custom) {

		public static PaceProfile of(Integer fiveKSec) {
			return new PaceProfile(fiveKSec, Map.of());
		}
	}

	/**
	 * The fast and slow end of a pace (seconds per km) and, for a distance repeat, the target time of one repeat.
	 * {@code custom} = the customer's own range; the calculated range is kept next to it so it can be shown and restored.
	 */
	public record Pace(String kind, String labelHe, String feelHe, Integer fastSecPerKm, Integer slowSecPerKm,
			Integer repFastSec, Integer repSlowSec, boolean custom, Integer calcFastSecPerKm, Integer calcSlowSecPerKm) {
	}

	public record PaceStatus(Integer fiveKSec, String updatedAt, boolean needed, List<Pace> paces) {
	}

	private final JdbcTemplate jdbc;

	public PaceService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	// ---- the pure calculation ----

	public static boolean isKind(String kind) {
		return kind != null && LABELS.containsKey(kind);
	}

	/** The calculated range of {@code kind} for a 5 km time, rounded to 5 seconds per km. */
	public static Range calculated(String kind, int fiveKSec) {
		if ("fivek".equals(kind)) {
			int pace = roundTo5(fiveKSec / 5.0);
			return new Range(pace, pace);
		}
		double[] share = SHARES.get(kind);
		double vdot = vdot(fiveKSec);
		return new Range(roundTo5(secPerKm(vdot, share[1])), roundTo5(secPerKm(vdot, share[0])));
	}

	/** Daniels and Gilbert: oxygen cost of the 5 km speed divided by the share of it a runner can hold for that long. */
	static double vdot(int fiveKSec) {
		double minutes = fiveKSec / 60.0;
		double speed = 5000.0 / minutes; // metres per minute
		double oxygen = -4.60 + 0.182258 * speed + 0.000104 * speed * speed;
		double held = 0.8 + 0.1894393 * Math.exp(-0.012778 * minutes) + 0.2989558 * Math.exp(-0.1932605 * minutes);
		return oxygen / held;
	}

	/** The pace (seconds per km) at which the oxygen cost equals {@code share} of the VDOT figure. */
	static double secPerKm(double vdot, double share) {
		double need = share * vdot + 4.60;
		double a = 0.000104;
		double b = 0.182258;
		double speed = (-b + Math.sqrt(b * b + 4 * a * need)) / (2 * a); // metres per minute
		return 60000.0 / speed;
	}

	/** The pace for {@code kind}; with no 5 km time (null) only the name and description are filled in. */
	public static Pace pace(String kind, PaceProfile profile, Integer distanceM) {
		if (profile == null || profile.fiveKSec() == null) {
			return new Pace(kind, LABELS.get(kind), FEELS.get(kind), null, null, null, null, false, null, null);
		}
		Range calc = calculated(kind, profile.fiveKSec());
		Range own = profile.custom().get(kind);
		Range used = own == null ? calc : own;
		Integer repFast = null;
		Integer repSlow = null;
		if (distanceM != null) {
			repFast = (int) Math.round(used.fastSecPerKm() * distanceM / 1000.0);
			repSlow = (int) Math.round(used.slowSecPerKm() * distanceM / 1000.0);
		}
		return new Pace(kind, LABELS.get(kind), FEELS.get(kind), used.fastSecPerKm(), used.slowSecPerKm(), repFast,
				repSlow, own != null, calc.fastSecPerKm(), calc.slowSecPerKm());
	}

	static int roundTo5(double seconds) {
		return (int) (Math.round(seconds / 5.0) * 5);
	}

	// ---- the customer's 5 km time and own ranges ----

	public Integer fiveKSec(UUID userId) {
		List<Integer> rows = jdbc.query("select five_k_sec from users where id = ?",
				(rs, i) -> (Integer) rs.getObject("five_k_sec"), userId);
		return rows.isEmpty() ? null : rows.get(0);
	}

	/** The 5 km time and the own ranges, for unfolding a workout into personal paces. */
	public PaceProfile profile(UUID userId) {
		Integer sec = fiveKSec(userId);
		return new PaceProfile(sec, sec == null ? Map.of() : customRanges(userId));
	}

	private Map<String, Range> customRanges(UUID userId) {
		Map<String, Range> out = new HashMap<>();
		jdbc.query("select kind, fast_sec, slow_sec from pace_overrides where user_id = ?",
				rs -> {
					out.put(rs.getString("kind"), new Range(rs.getInt("fast_sec"), rs.getInt("slow_sec")));
				}, userId);
		return out;
	}

	public PaceStatus status(UUID userId) {
		return jdbc.query("select five_k_sec, five_k_updated_at::text as updated_at from users where id = ?", (rs, i) -> {
			Integer sec = (Integer) rs.getObject("five_k_sec");
			PaceProfile profile = new PaceProfile(sec, sec == null ? Map.of() : customRanges(userId));
			List<Pace> paces = sec == null ? List.of() : KINDS.stream().map(k -> pace(k, profile, null)).toList();
			return new PaceStatus(sec, rs.getString("updated_at"), sec == null && usesPaces(userId), paces);
		}, userId).stream().findFirst().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not_found"));
	}

	/** Saves the 5 km time. A different time makes the calculated zones new, so the customer's own ranges are dropped. */
	public PaceStatus set(UUID userId, int fiveKSec) {
		if (fiveKSec < MIN_FIVE_K_SEC || fiveKSec > MAX_FIVE_K_SEC) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_five_k");
		}
		Integer before = fiveKSec(userId);
		if (before != null && before != fiveKSec) {
			jdbc.update("delete from pace_overrides where user_id = ?", userId);
		}
		jdbc.update("update users set five_k_sec = ?, five_k_updated_at = now() where id = ?", fiveKSec, userId);
		return status(userId);
	}

	public PaceStatus clear(UUID userId) {
		jdbc.update("delete from pace_overrides where user_id = ?", userId);
		jdbc.update("update users set five_k_sec = null, five_k_updated_at = null where id = ?", userId);
		return status(userId);
	}

	/** Saves the customer's own range for easy, threshold or interval (needs the 5 km time first). */
	public PaceStatus setZone(UUID userId, String kind, int fastSecPerKm, int slowSecPerKm) {
		if (!EDITABLE_KINDS.contains(kind)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_pace_kind");
		}
		if (fastSecPerKm < MIN_ZONE_SEC || slowSecPerKm > MAX_ZONE_SEC || fastSecPerKm > slowSecPerKm) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_pace_range");
		}
		if (fiveKSec(userId) == null) {
			throw new ApiException(HttpStatus.CONFLICT, "five_k_required");
		}
		jdbc.update("""
				insert into pace_overrides (user_id, kind, fast_sec, slow_sec) values (?, ?, ?, ?)
				on conflict (user_id, kind) do update set fast_sec = excluded.fast_sec, slow_sec = excluded.slow_sec,
				    updated_at = now()""", userId, kind, fastSecPerKm, slowSecPerKm);
		return status(userId);
	}

	/** Back to the calculated range: one kind, or all of them when {@code kind} is null. */
	public PaceStatus resetZone(UUID userId, String kind) {
		if (kind == null) {
			jdbc.update("delete from pace_overrides where user_id = ?", userId);
		}
		else {
			jdbc.update("delete from pace_overrides where user_id = ? and kind = ?", userId, kind);
		}
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
