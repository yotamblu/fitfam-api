package com.fitfam.api.fitbot;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fitfam.api.admin.AuditLogService;
import com.fitfam.api.auth.AuthenticatedUser;
import com.fitfam.api.web.ApiException;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The text sections the coaches write for Fitbot (how the founders talk, how the method works, frequent questions).
 * They are added to the model's instructions on every message, so the total size is capped to keep each answer cheap.
 */
@Service
public class KnowledgeService {

	/** All enabled sections together may not be longer than this (a rough cap on the tokens added to every message). */
	static final int MAX_TOTAL_CHARS = 24_000;
	private static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9-]{0,39}");

	public record Section(String id, String slug, String titleHe, String contentHe, boolean enabled, int sortOrder,
			String updatedAt) {
	}

	public record SectionRequest(@NotBlank @Size(max = 40) String slug, @NotBlank @Size(max = 120) String titleHe,
			@NotBlank @Size(max = 12_000) String contentHe, Boolean enabled, Integer sortOrder) {
	}

	private final JdbcTemplate jdbc;
	private final AuditLogService audit;

	public KnowledgeService(JdbcTemplate jdbc, AuditLogService audit) {
		this.jdbc = jdbc;
		this.audit = audit;
	}

	private static final String COLUMNS = "id::text as id, slug, title_he, content_he, enabled, sort_order, updated_at::text as updated_at";

	private static Section map(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new Section(rs.getString("id"), rs.getString("slug"), rs.getString("title_he"), rs.getString("content_he"),
				rs.getBoolean("enabled"), rs.getInt("sort_order"), rs.getString("updated_at"));
	}

	@Transactional(readOnly = true)
	public List<Section> listAll() {
		return jdbc.query("select " + COLUMNS + " from fitbot_knowledge order by sort_order, title_he", (rs, i) -> map(rs));
	}

	@Transactional(readOnly = true)
	public List<Section> listEnabled() {
		return jdbc.query("select " + COLUMNS + " from fitbot_knowledge where enabled order by sort_order, title_he",
				(rs, i) -> map(rs));
	}

	@Transactional
	public Section create(AuthenticatedUser admin, SectionRequest request) {
		String slug = checkedSlug(request.slug());
		boolean enabled = request.enabled() == null || request.enabled();
		requireRoom(null, enabled ? request.contentHe().length() : 0);
		try {
			UUID id = jdbc.queryForObject("""
					insert into fitbot_knowledge (slug, title_he, content_he, enabled, sort_order, updated_by)
					values (?, ?, ?, ?, ?, ?) returning id
					""", UUID.class, slug, request.titleHe().trim(), request.contentHe().strip(), enabled,
					request.sortOrder() == null ? 0 : request.sortOrder(), admin.id());
			audit.record(admin.id(), "fitbot_knowledge.created", "fitbot_knowledge", id.toString(), Map.of("slug", slug));
			return get(id);
		}
		catch (DuplicateKeyException e) {
			throw new ApiException(HttpStatus.CONFLICT, "slug_taken");
		}
	}

	@Transactional
	public Section update(AuthenticatedUser admin, UUID id, SectionRequest request) {
		Section old = get(id);
		String slug = checkedSlug(request.slug());
		boolean enabled = request.enabled() == null ? old.enabled() : request.enabled();
		requireRoom(id, enabled ? request.contentHe().length() : 0);
		try {
			jdbc.update("""
					update fitbot_knowledge set slug = ?, title_he = ?, content_he = ?, enabled = ?, sort_order = ?,
					       updated_at = now(), updated_by = ? where id = ?
					""", slug, request.titleHe().trim(), request.contentHe().strip(), enabled,
					request.sortOrder() == null ? old.sortOrder() : request.sortOrder(), admin.id(), id);
		}
		catch (DuplicateKeyException e) {
			throw new ApiException(HttpStatus.CONFLICT, "slug_taken");
		}
		audit.record(admin.id(), "fitbot_knowledge.updated", "fitbot_knowledge", id.toString(), Map.of("slug", slug));
		return get(id);
	}

	@Transactional
	public void delete(AuthenticatedUser admin, UUID id) {
		Section old = get(id);
		jdbc.update("delete from fitbot_knowledge where id = ?", id);
		audit.record(admin.id(), "fitbot_knowledge.deleted", "fitbot_knowledge", id.toString(), Map.of("slug", old.slug()));
	}

	private Section get(UUID id) {
		return jdbc.query("select " + COLUMNS + " from fitbot_knowledge where id = ?", (rs, i) -> map(rs), id).stream()
				.findFirst().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "not_found"));
	}

	static String checkedSlug(String raw) {
		String slug = raw == null ? "" : raw.trim().toLowerCase();
		if (!SLUG.matcher(slug).matches()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_slug");
		}
		return slug;
	}

	/** The enabled sections other than {@code exceptId}, plus the new text, must fit in {@link #MAX_TOTAL_CHARS}. */
	private void requireRoom(UUID exceptId, int newChars) {
		Long others = jdbc.queryForObject(
				"select coalesce(sum(length(content_he)), 0) from fitbot_knowledge where enabled and (?::uuid is null or id <> ?::uuid)",
				Long.class, exceptId, exceptId);
		if ((others == null ? 0 : others) + newChars > MAX_TOTAL_CHARS) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "knowledge_too_long");
		}
	}

}
