package com.ivoyant.hrms.employee.service;

import com.ivoyant.hrms.common.security.CurrentUser;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Writes the effective-dated job history (one row per change; scheduled rows have no applied_at). */
@Component
class JobHistory {

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;

	JobHistory(JdbcClient jdbc, CurrentUser currentUser) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
	}

	/** {@code cols} holds only the columns that change (department, position, title, titleText, location, manager, type, status). */
	UUID add(UUID employeeId, String kind, LocalDate effective, boolean applied, Map<String, Object> cols, String reason) {
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				insert into employee_job_history (id, org_id, employee_id, kind, effective_from, applied_at, department_id, position_id, title_id, title,
				  location_id, manager_id, employment_type_id, status, reason, created_by)
				values (:id, :org, :e, :k, :d, case when :applied then now() else null end, :dept, :pos, :tid, :ttl, :loc, :mgr, :et, :st, :r, :by)""")
				.param("id", id).param("org", currentUser.orgId()).param("e", employeeId).param("k", kind).param("d", effective).param("applied", applied)
				.param("dept", cols.get("department"), java.sql.Types.OTHER).param("pos", cols.get("position"), java.sql.Types.OTHER)
				.param("tid", cols.get("titleId"), java.sql.Types.OTHER).param("ttl", cols.get("titleText"))
				.param("loc", cols.get("location"), java.sql.Types.OTHER).param("mgr", cols.get("manager"), java.sql.Types.OTHER)
				.param("et", cols.get("type"), java.sql.Types.OTHER).param("st", cols.get("status")).param("r", reason)
				.param("by", currentUser.employeeId(), java.sql.Types.OTHER).update();
		return id;
	}
}
