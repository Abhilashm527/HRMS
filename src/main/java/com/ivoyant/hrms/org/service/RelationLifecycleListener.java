package com.ivoyant.hrms.org.service;

import com.ivoyant.hrms.common.event.EmployeeExitedEvent;
import com.ivoyant.hrms.common.event.ManagerChangedEvent;
import java.time.LocalDate;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Relations follow the people: leaving ends them, and a dotted manager who becomes the direct manager needs no dotted line. */
@Component
class RelationLifecycleListener {

	private final RelationService relations;
	private final JdbcClient jdbc;

	RelationLifecycleListener(RelationService relations, JdbcClient jdbc) {
		this.relations = relations;
		this.jdbc = jdbc;
	}

	@EventListener
	void onExit(EmployeeExitedEvent e) {
		LocalDate exit = jdbc.sql("select exit_date from employee where id = :id").param("id", e.employeeId()).query(LocalDate.class)
				.optional().orElse(null);
		relations.endForEmployee(e.employeeId(), exit == null || exit.isAfter(LocalDate.now()) ? LocalDate.now() : exit);
	}

	@EventListener
	void onManagerChanged(ManagerChangedEvent e) {
		if (e.to() != null) {
			relations.endBecameDirect(e.employeeId(), e.to());
		}
	}
}
