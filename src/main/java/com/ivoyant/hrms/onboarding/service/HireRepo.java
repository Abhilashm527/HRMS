package com.ivoyant.hrms.onboarding.service;

import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.onboarding.domain.Hire;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Reads hire rows (org-scoped). Writes live in the services that own the change. */
@Component
public class HireRepo {

	static final String COLUMNS = """
			h.id, h.org_id, h.first_name, h.last_name, h.personal_email, h.phone, h.gender, h.dob, h.title, h.title_id,
			h.department_id, h.manager_id, h.location_id, h.employment_type_id, h.doj, h.probation_months, h.ctc_lakh,
			h.contract_end_date, h.status, h.stages, h.details_submitted_at, h.employee_id, h.joined_at, h.cancelled_at,
			h.cancel_reason, h.invite_template_id, h.version""";

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;

	HireRepo(JdbcClient jdbc, CurrentUser currentUser) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
	}

	public static Hire map(ResultSet rs) throws SQLException {
		Object[] arr = (Object[]) rs.getArray("stages").getArray();
		boolean[] stages = new boolean[arr.length];
		for (int i = 0; i < arr.length; i++) {
			stages[i] = Boolean.TRUE.equals(arr[i]);
		}
		return new Hire(rs.getObject("id", UUID.class), rs.getObject("org_id", UUID.class), rs.getString("first_name"),
				rs.getString("last_name"), rs.getString("personal_email"), rs.getString("phone"), rs.getString("gender"),
				rs.getObject("dob", LocalDate.class), rs.getString("title"), rs.getObject("title_id", UUID.class),
				rs.getObject("department_id", UUID.class), rs.getObject("manager_id", UUID.class),
				rs.getObject("location_id", UUID.class), rs.getObject("employment_type_id", UUID.class),
				rs.getObject("doj", LocalDate.class), (Integer) rs.getObject("probation_months"), rs.getBigDecimal("ctc_lakh"),
				rs.getObject("contract_end_date", LocalDate.class), rs.getString("status"), stages,
				rs.getObject("details_submitted_at", java.time.OffsetDateTime.class) == null ? null
						: rs.getObject("details_submitted_at", java.time.OffsetDateTime.class).toInstant(),
				rs.getObject("employee_id", UUID.class),
				rs.getObject("joined_at", java.time.OffsetDateTime.class) == null ? null
						: rs.getObject("joined_at", java.time.OffsetDateTime.class).toInstant(),
				rs.getObject("cancelled_at", java.time.OffsetDateTime.class) == null ? null
						: rs.getObject("cancelled_at", java.time.OffsetDateTime.class).toInstant(),
				rs.getString("cancel_reason"), rs.getObject("invite_template_id", UUID.class), rs.getLong("version"));
	}

	public Optional<Hire> find(UUID id) {
		return jdbc.sql("select " + COLUMNS + " from onboarding_hire h where h.id = :id and h.org_id = :org")
				.param("id", id).param("org", currentUser.orgId()).query((rs, i) -> map(rs)).optional();
	}

	public Hire require(UUID id) {
		return find(id).orElseThrow(() -> new NotFoundException("Hire " + id + " not found"));
	}

	/** Loads and row-locks the hire for the rest of the transaction. */
	public Hire lock(UUID id) {
		return jdbc.sql("select " + COLUMNS + " from onboarding_hire h where h.id = :id and h.org_id = :org for update")
				.param("id", id).param("org", currentUser.orgId()).query((rs, i) -> map(rs)).optional()
				.orElseThrow(() -> new NotFoundException("Hire " + id + " not found"));
	}
}
