package com.fitfam.api.admin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Records manual admin actions in {@code admin_audit_log}. Joins the caller's transaction. */
@Service
public class AuditLogService {

	private final JdbcTemplate jdbc;

	public AuditLogService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public void record(UUID adminUserId, String action, String targetType, String targetId,
			Map<String, String> details) {
		// Only "?" placeholders are generated here; every value is bound, never concatenated.
		List<Object> params = new ArrayList<>(List.of(adminUserId, action, targetType, targetId));
		StringBuilder pairs = new StringBuilder();
		for (Map.Entry<String, String> detail : details.entrySet()) {
			if (!pairs.isEmpty()) {
				pairs.append(", ");
			}
			pairs.append("?::text, ?::text");
			params.add(detail.getKey());
			params.add(detail.getValue());
		}
		jdbc.update("insert into admin_audit_log (admin_user_id, action, target_type, target_id, details) "
				+ "values (?, ?, ?, ?, jsonb_build_object(" + pairs + "))", params.toArray());
	}

}
