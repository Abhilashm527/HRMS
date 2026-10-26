package com.ivoyant.hrms.org.service;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.CodedConflictException;
import com.ivoyant.hrms.common.web.ConflictException;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.common.web.UnprocessableException;
import com.ivoyant.hrms.org.dto.ProjectDtos;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A person's teams and projects as one thing: what the profile shows and what Reporting lines saves per row. */
@Service
public class GroupsService implements com.ivoyant.hrms.org.Groups {

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final People people;
	private final TeamService teams;
	private final ProjectService projects;
	private final Audit audit;

	public GroupsService(JdbcClient jdbc, CurrentUser currentUser, People people, TeamService teams, ProjectService projects, Audit audit) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.people = people;
		this.teams = teams;
		this.projects = projects;
		this.audit = audit;
	}

	@Transactional(readOnly = true)
	public ProjectDtos.Groups get(UUID employee) {
		require(employee);
		List<ProjectDtos.GroupTeam> t = teams.teamsOf(employee).stream()
				.map(r -> new ProjectDtos.GroupTeam((UUID) r[0], (String) r[1], (Boolean) r[2])).toList();
		List<ProjectDtos.GroupProject> p = jdbc.sql("select pr.id, pr.name, pr.code, m.role, m.allocation, pr.status from project_member m "
				+ "join project pr on pr.id = m.project_id where m.employee_id = :e and m.until is null order by m.allocation desc, pr.name")
				.param("e", employee).query((rs, i) -> new ProjectDtos.GroupProject(rs.getObject(1, UUID.class), rs.getString(2),
						rs.getString(3), rs.getString(4), rs.getInt(5))).list();
		int total = jdbc.sql("select coalesce(sum(m.allocation), 0) from project_member m join project pr on pr.id = m.project_id "
				+ "where m.employee_id = :e and m.until is null and pr.status in " + ProjectQueryService.LIVE).param("e", employee)
				.query(Integer.class).single();
		Map<String, String> text = new LinkedHashMap<>();
		text.put("teams", t.isEmpty() ? "No team" : t.stream().map(ProjectDtos.GroupTeam::name).collect(Collectors.joining(", ")));
		text.put("projects", p.isEmpty() ? "No projects" : p.stream().map(x -> x.name() + " " + x.allocation() + "%").collect(Collectors.joining(", ")));
		return new ProjectDtos.Groups(t, p, total, text);
	}

	@Override
	public void replace(UUID employeeId, Map<String, Object> body) {
		put(employeeId, body);
	}

	/** Body: {@code teams: [{teamId, primary}]}, {@code projects: [{projectId, role, allocation}]}, {@code effectiveDate}; replaces the current sets. */
	@Transactional
	@SuppressWarnings("unchecked")
	public ProjectDtos.Groups put(UUID employee, Map<String, Object> body) {
		require(employee);
		ProjectDtos.Groups before = get(employee);
		LocalDate effective = body.get("effectiveDate") == null || body.get("effectiveDate").toString().isBlank()
				? LocalDate.now() : LocalDate.parse(body.get("effectiveDate").toString());
		if (body.get("teams") instanceof List<?> t) {
			teams.applyEmployeeTeams(employee, (List<Map<String, Object>>) t, effective);
		}
		if (body.get("projects") instanceof List<?> p) {
			projects.applyEmployeeProjects(employee, (List<Map<String, Object>>) p, effective);
		}
		ProjectDtos.Groups after = get(employee);
		audit.updated("org", "EmployeeGroups", employee, before.text(), after.text());
		return after;
	}

	/** Checks what a person's total would be if these projects got these allocations (nothing is saved). */
	@Transactional(readOnly = true)
	@SuppressWarnings("unchecked")
	public ProjectDtos.AllocationCheck check(UUID employee, List<Map<String, Object>> changes) {
		People.Emp e = require(employee);
		Map<UUID, Integer> alloc = new LinkedHashMap<>();
		jdbc.sql("select m.project_id, m.allocation from project_member m join project pr on pr.id = m.project_id "
				+ "where m.employee_id = :e and m.until is null and pr.status in " + ProjectQueryService.LIVE).param("e", employee)
				.query((rs, i) -> {
					alloc.put(rs.getObject(1, UUID.class), rs.getInt(2));
					return 0;
				}).list();
		for (Map<String, Object> c : changes) {
			alloc.put(UUID.fromString(String.valueOf(c.get("projectId"))), Integer.parseInt(String.valueOf(c.get("allocation"))));
		}
		int total = alloc.values().stream().mapToInt(Integer::intValue).sum();
		return new ProjectDtos.AllocationCheck(total, total <= 100, total <= 100 ? e.name() + " would be at " + total + "%"
				: e.name() + " would be at " + total + "%, which is more than 100%");
	}

	private People.Emp require(UUID employee) {
		People.Emp e = people.find(employee);
		if (e == null) {
			throw new NotFoundException("Employee " + employee + " not found");
		}
		return e;
	}
}
