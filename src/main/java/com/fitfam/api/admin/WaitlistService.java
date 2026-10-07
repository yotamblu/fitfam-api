package com.fitfam.api.admin;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fitfam.api.admin.AdminDtos.WaitlistEntryDto;
import com.fitfam.api.admin.AdminDtos.WaitlistPageDto;
import com.fitfam.api.admin.AdminDtos.WaitlistSummaryDto;
import com.fitfam.api.web.ApiException;

/**
 * Read-only view of the public waitlist. The waitlist site writes {@code waitlist_signups} in this same database; this
 * service only ever reads it, with plain SQL so a change to that table cannot stop the API from starting.
 */
@Service
public class WaitlistService {

	static final int MAX_SIZE = 200;
	static final String STATUS_WAITING = "waiting";

	private static final String ALREADY_USER = "exists (select 1 from users u where u.email = lower(w.email))";

	private static final String LIST_SQL = "select w.id::text as id, w.email, w.favorite_sport, w.created_at, "
			+ ALREADY_USER + " as already_user from waitlist_signups w ";

	/** A WHERE clause made only of fixed fragments and "?" placeholders: user input is always bound, never concatenated. */
	record Filter(String where, List<Object> params) {
	}

	private final JdbcTemplate jdbc;

	public WaitlistService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public WaitlistPageDto list(int page, int size, String query, String status, String sport) {
		int safePage = clampPage(page);
		int safeSize = clampSize(size);
		Filter filter = buildFilter(query, status, sport);
		try {
			Long total = jdbc.queryForObject("select count(*) from waitlist_signups w " + filter.where(), Long.class,
					filter.params().toArray());

			List<Object> pageParams = new ArrayList<>(filter.params());
			pageParams.add(safeSize);
			pageParams.add((long) safePage * safeSize);
			List<WaitlistEntryDto> items = jdbc.query(
					LIST_SQL + filter.where() + " order by w.created_at desc, w.id limit ? offset ?",
					(rs, row) -> new WaitlistEntryDto(
							rs.getString("id"),
							rs.getString("email"),
							rs.getString("favorite_sport"),
							rs.getObject("created_at", OffsetDateTime.class).toInstant(),
							rs.getBoolean("already_user")),
					pageParams.toArray());

			return new WaitlistPageDto(total == null ? 0 : total, safePage, safeSize, items, summary());
		}
		catch (BadSqlGrammarException e) {
			// the waitlist table is missing or was changed incompatibly
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "waitlist_unavailable");
		}
	}

	/** Totals over the whole waitlist, whatever the current search or filter is. */
	private WaitlistSummaryDto summary() {
		long[] totals = jdbc.queryForObject(
				"select count(*), count(*) filter (where " + ALREADY_USER + ") from waitlist_signups w",
				(rs, row) -> new long[] { rs.getLong(1), rs.getLong(2) });
		Map<String, Long> bySport = new LinkedHashMap<>();
		jdbc.query("select coalesce(favorite_sport, 'none') as sport, count(*) as n from waitlist_signups group by 1 order by 2 desc",
				(RowCallbackHandler) rs -> bySport.put(rs.getString("sport"), rs.getLong("n")));
		return new WaitlistSummaryDto(totals[0], totals[1], bySport);
	}

	static Filter buildFilter(String query, String status, String sport) {
		List<String> conditions = new ArrayList<>();
		List<Object> params = new ArrayList<>();
		if (query != null && !query.isBlank()) {
			// position() instead of LIKE so '%' and '_' typed by the user are not wildcards
			conditions.add("position(lower(?) in lower(w.email)) > 0");
			params.add(query.trim());
		}
		if (STATUS_WAITING.equals(status)) {
			conditions.add("not " + ALREADY_USER);
		}
		if (sport != null && !sport.isBlank()) {
			conditions.add("coalesce(w.favorite_sport, 'none') = ?");
			params.add(sport.trim());
		}
		String where = conditions.isEmpty() ? "" : "where " + String.join(" and ", conditions);
		return new Filter(where, params);
	}

	static int clampPage(int page) {
		return Math.max(0, page);
	}

	static int clampSize(int size) {
		return Math.min(MAX_SIZE, Math.max(1, size));
	}

}
