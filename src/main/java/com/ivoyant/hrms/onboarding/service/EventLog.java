package com.ivoyant.hrms.onboarding.service;

import com.ivoyant.hrms.common.security.CurrentUser;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Appends to a hire's user-facing activity timeline (the audit log is written separately). */
@Component
public class EventLog {

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;

	EventLog(JdbcClient jdbc, CurrentUser currentUser) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
	}

	public void add(UUID hireId, String kind, String title, String description) {
		add(hireId, kind, title, description, false);
	}

	public void add(UUID hireId, String kind, String title, String description, boolean byHire) {
		jdbc.sql("insert into onboarding_event (hire_id, kind, title, description, actor_id, actor_is_hire) values (:h, :k, :t, :d, :a, :b)")
				.param("h", hireId).param("k", kind).param("t", title).param("d", description)
				.param("a", byHire ? null : currentUser.employeeId(), java.sql.Types.OTHER).param("b", byHire).update();
	}
}
