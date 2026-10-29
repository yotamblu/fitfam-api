package com.fitfam.api.legal;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fitfam.api.auth.AuthenticatedUser;
import com.fitfam.api.web.ApiException;

import tools.jackson.databind.ObjectMapper;

/**
 * Terms of use, privacy policy and the health declaration: what a customer has accepted, the rules for accepting, the
 * customer's own copy of their data, and account deletion. Plain SQL, values always bound.
 */
@Service
public class ConsentService {

	public record Terms(String version, String acceptedAt, boolean current) {
	}

	public record Health(String version, String declaredAt, String expiresAt, boolean current) {
	}

	/** {@code required} is true for everyone, admins included (so the team goes through exactly what customers do); {@code ok} = nothing is missing. */
	public record Status(boolean required, boolean ok, String termsVersion, String privacyVersion, String healthVersion,
			Terms terms, Health health) {
	}

	public record TermsRequest(String termsVersion, String privacyVersion, boolean ageConfirmed) {
	}

	/**
	 * {@code conditionsDeclaration}: the customer declares that none of the listed conditions applies to them, or that one does
	 * and a doctor cleared them (the list itself is part of the versioned text in the web app).
	 */
	public record HealthRequest(String version, boolean conditionsDeclaration, boolean riskAcknowledged,
			boolean accuracyConfirmed, boolean dataConsent, boolean ageConfirmed, String fullName) {
	}

	private final JdbcTemplate jdbc;
	private final ObjectMapper mapper;

	public ConsentService(JdbcTemplate jdbc, ObjectMapper mapper) {
		this.jdbc = jdbc;
		this.mapper = mapper;
	}

	// ---- status ----

	@Transactional(readOnly = true)
	public Status status(AuthenticatedUser user) {
		Terms terms = jdbc.query("""
				select accepted_at::text as at, details->>'termsVersion' as terms, details->>'privacyVersion' as privacy
				from user_consents where user_id = ? and kind = 'terms' order by accepted_at desc limit 1
				""", (rs, i) -> new Terms(rs.getString("terms") + "/" + rs.getString("privacy"), rs.getString("at"),
				LegalVersions.TERMS.equals(rs.getString("terms")) && LegalVersions.PRIVACY.equals(rs.getString("privacy"))),
				user.id()).stream().findFirst().orElse(null);
		Health health = jdbc.query("""
				select version, accepted_at::text as at, expires_at::text as until,
				       (expires_at > now()) as valid
				from user_consents where user_id = ? and kind = 'health_declaration' order by accepted_at desc limit 1
				""", (rs, i) -> new Health(rs.getString("version"), rs.getString("at"), rs.getString("until"),
				LegalVersions.HEALTH.equals(rs.getString("version")) && rs.getBoolean("valid")),
				user.id()).stream().findFirst().orElse(null);
		boolean required = true;
		boolean ok = terms != null && terms.current() && health != null && health.current();
		return new Status(required, ok, LegalVersions.TERMS, LegalVersions.PRIVACY, LegalVersions.HEALTH, terms, health);
	}

	/** Everyone, admins too, must have a current acceptance and declaration before they train. */
	public void requireCurrent(AuthenticatedUser user) {
		if (!status(user).ok()) {
			throw new ApiException(HttpStatus.FORBIDDEN, "consent_required");
		}
	}

	// ---- accepting ----

	@Transactional
	public Status acceptTerms(AuthenticatedUser user, TermsRequest request) {
		if (!LegalVersions.TERMS.equals(request.termsVersion()) || !LegalVersions.PRIVACY.equals(request.privacyVersion())) {
			throw new ApiException(HttpStatus.CONFLICT, "version_outdated");
		}
		if (!request.ageConfirmed()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "age_required");
		}
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("termsVersion", request.termsVersion());
		details.put("privacyVersion", request.privacyVersion());
		details.put("ageConfirmed", true);
		jdbc.update("insert into user_consents (user_id, kind, version, details) values (?, 'terms', ?, ?::jsonb)",
				user.id(), request.termsVersion(), mapper.writeValueAsString(details));
		return status(user);
	}

	@Transactional
	public Status declareHealth(AuthenticatedUser user, HealthRequest request) {
		validateHealth(request);
		// No medical answers are stored on purpose (data minimisation): only that this exact, versioned declaration was accepted.
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("conditionsDeclaration", true);
		details.put("riskAcknowledged", true);
		details.put("accuracyConfirmed", true);
		details.put("dataConsent", true);
		details.put("ageConfirmed", true);
		details.put("fullName", request.fullName().trim());
		jdbc.update("""
				insert into user_consents (user_id, kind, version, expires_at, details)
				values (?, 'health_declaration', ?, now() + interval '2 years', ?::jsonb)
				""", user.id(), request.version(), mapper.writeValueAsString(details));
		return status(user);
	}

	/** The rules for a valid declaration. Package-private so the tests can reach it without a database. */
	static void validateHealth(HealthRequest request) {
		if (!LegalVersions.HEALTH.equals(request.version())) {
			throw new ApiException(HttpStatus.CONFLICT, "version_outdated");
		}
		if (!request.conditionsDeclaration() || !request.ageConfirmed() || !request.riskAcknowledged()
				|| !request.accuracyConfirmed() || !request.dataConsent()) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "confirmations_required");
		}
		String name = request.fullName() == null ? "" : request.fullName().trim();
		if (name.length() < 2 || name.length() > 100) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "name_required");
		}
	}

	// ---- the customer's own data ----

	/** Everything stored about the customer, as one JSON-friendly map (right to see one's data). */
	@Transactional(readOnly = true)
	public Map<String, Object> export(UUID userId) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("exportedAt", OffsetDateTime.now().toString());
		out.put("account", jdbc.queryForList("""
				select email, display_name, avatar_url, role, status, created_at::text as created_at,
				       first_login_at::text as first_login_at, five_k_sec, five_k_updated_at::text as five_k_updated_at
				from users where id = ?""", userId));
		out.put("plans", jdbc.queryForList("""
				select p.slug as plan, l.level_number, e.status, e.started_at::text as started_at
				from enrollments e join plans p on p.id = e.plan_id join plan_levels l on l.id = e.current_level_id
				where e.user_id = ? order by p.slug""", userId));
		out.put("levelHistory", jdbc.queryForList("""
				select h.reason, l.level_number as to_level, h.changed_at::text as changed_at
				from enrollment_level_history h join plan_levels l on l.id = h.to_level_id
				where h.enrollment_id in (select id from enrollments where user_id = ?) order by h.changed_at""", userId));
		out.put("completedWorkouts", jdbc.queryForList("""
				select w.title_he as workout, wp.status, wp.started_at::text as started_at, wp.completed_at::text as completed_at
				from workout_progress wp join workouts w on w.id = wp.workout_id
				where wp.user_id = ? order by wp.started_at""", userId));
		out.put("challengeAttempts", jdbc.queryForList("""
				select w.title_he as challenge, a.passed, a.attempted_at::text as attempted_at
				from challenge_attempts a join workouts w on w.id = a.workout_id
				where a.user_id = ? order by a.attempted_at""", userId));
		out.put("consents", jdbc.queryForList("""
				select kind, version, accepted_at::text as accepted_at, expires_at::text as expires_at, details::text as details
				from user_consents where user_id = ? order by accepted_at""", userId));
		return out;
	}

	/**
	 * Deletes a customer and everything that belongs to them (the foreign keys cascade), plus their waitlist signup if
	 * there is one. Admins cannot delete themselves here (that would lock the team out by accident). Not one transaction on
	 * purpose: a failing waitlist statement must not poison the user deletion.
	 */
	public void deleteAccount(AuthenticatedUser user) {
		if (user.isAdmin()) {
			throw new ApiException(HttpStatus.FORBIDDEN, "admin_cannot_self_delete");
		}
		try {
			jdbc.update("delete from waitlist_signups where lower(email) = lower(?)", user.email());
		}
		catch (DataAccessException e) {
			// the waitlist table belongs to another site; if it is missing or not writable, the account deletion still goes on
		}
		jdbc.update("delete from users where id = ? and role = 'customer'", user.id());
	}

}
