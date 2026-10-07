package com.fitfam.api.admin;

import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fitfam.api.admin.AdminDtos.WaitlistEntryDto;
import com.fitfam.api.admin.AdminDtos.WaitlistPageDto;
import com.fitfam.api.web.ApiException;

/**
 * Read-only view of the public waitlist. The waitlist site writes {@code waitlist_signups} in this same database; this
 * service only ever reads it, with plain SQL so a change to that table cannot stop the API from starting.
 */
@Service
public class WaitlistService {

	static final int MAX_SIZE = 200;

	private static final String LIST_SQL = """
			select w.id::text as id, w.email, w.favorite_sport, w.created_at,
			       exists (select 1 from users u where u.email = lower(w.email)) as already_user
			from waitlist_signups w
			order by w.created_at desc, w.id
			limit ? offset ?
			""";

	private final JdbcTemplate jdbc;

	public WaitlistService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public WaitlistPageDto list(int page, int size) {
		int safePage = clampPage(page);
		int safeSize = clampSize(size);
		try {
			Long total = jdbc.queryForObject("select count(*) from waitlist_signups", Long.class);
			List<WaitlistEntryDto> items = jdbc.query(LIST_SQL,
					(rs, row) -> new WaitlistEntryDto(
							rs.getString("id"),
							rs.getString("email"),
							rs.getString("favorite_sport"),
							rs.getObject("created_at", OffsetDateTime.class).toInstant(),
							rs.getBoolean("already_user")),
					safeSize, (long) safePage * safeSize);
			return new WaitlistPageDto(total == null ? 0 : total, safePage, safeSize, items);
		}
		catch (BadSqlGrammarException e) {
			// the waitlist table is missing or was changed incompatibly
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "waitlist_unavailable");
		}
	}

	static int clampPage(int page) {
		return Math.max(0, page);
	}

	static int clampSize(int size) {
		return Math.min(MAX_SIZE, Math.max(1, size));
	}

}
