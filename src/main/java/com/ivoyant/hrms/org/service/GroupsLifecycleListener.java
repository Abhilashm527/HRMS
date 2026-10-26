package com.ivoyant.hrms.org.service;

import com.ivoyant.hrms.common.event.EmployeeDepartmentChangedEvent;
import com.ivoyant.hrms.common.event.EmployeeExitedEvent;
import java.time.LocalDate;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Keeps teams and projects true to the people in them. A transfer ends the memberships of teams outside the new
 * department (projects are unaffected); leaving ends every team and project membership and clears team leads.
 */
@Component
class GroupsLifecycleListener {

	private final TeamService teams;
	private final ProjectService projects;
	private final JdbcClient jdbc;

	GroupsLifecycleListener(TeamService teams, ProjectService projects, JdbcClient jdbc) {
		this.teams = teams;
		this.projects = projects;
		this.jdbc = jdbc;
	}

	@EventListener
	void onTransfer(EmployeeDepartmentChangedEvent e) {
		teams.onTransfer(e.employeeId(), e.to(), LocalDate.now());
	}

	@EventListener
	void onExit(EmployeeExitedEvent e) {
		LocalDate exit = jdbc.sql("select exit_date from employee where id = :id").param("id", e.employeeId())
				.query(LocalDate.class).optional().orElse(null);
		LocalDate until = exit == null || exit.isAfter(LocalDate.now()) ? LocalDate.now() : exit;
		teams.releaseEmployee(e.employeeId(), until);
		projects.releaseEmployee(e.employeeId(), until);
	}
}
