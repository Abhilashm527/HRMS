package com.ivoyant.hrms.org.service;

import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.org.ProjectView;
import com.ivoyant.hrms.org.Projects;
import com.ivoyant.hrms.org.TeamView;
import com.ivoyant.hrms.org.Teams;
import com.ivoyant.hrms.org.domain.Project;
import com.ivoyant.hrms.org.domain.ProjectStatus;
import com.ivoyant.hrms.org.domain.Team;
import com.ivoyant.hrms.org.dto.TeamDtos;
import com.ivoyant.hrms.org.repo.ProjectRepository;
import com.ivoyant.hrms.org.repo.TeamRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The team and project facades other modules use (hire form pre-fill, timesheets, trace). */
@Service
class TeamsProjectsFacade implements Teams, Projects {

	private final TeamRepository teamRepo;
	private final ProjectRepository projectRepo;
	private final TeamQueryService teamQuery;
	private final JdbcClient jdbc;
	private final CurrentUser currentUser;

	TeamsProjectsFacade(TeamRepository teamRepo, ProjectRepository projectRepo, TeamQueryService teamQuery, JdbcClient jdbc,
			CurrentUser currentUser) {
		this.teamRepo = teamRepo;
		this.projectRepo = projectRepo;
		this.teamQuery = teamQuery;
		this.jdbc = jdbc;
		this.currentUser = currentUser;
	}

	private static TeamView view(Team t) {
		return new TeamView(t.getId(), t.getName(), t.getDepartmentId(), t.getLeadEmployeeId(), t.isActive());
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<TeamView> get(UUID id) {
		return teamRepo.findByIdAndOrgId(id, currentUser.orgId()).map(TeamsProjectsFacade::view);
	}

	@Override
	@Transactional(readOnly = true)
	public List<TeamView> ofEmployee(UUID employeeId) {
		return jdbc.sql("select team_id from team_member where employee_id = :e and until is null order by is_primary desc, since")
				.param("e", employeeId).query(UUID.class).list().stream()
				.map(id -> teamRepo.findByIdAndOrgId(id, currentUser.orgId()).map(TeamsProjectsFacade::view).orElse(null))
				.filter(v -> v != null).toList();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<UUID> leadOf(UUID teamId) {
		return teamRepo.findByIdAndOrgId(teamId, currentUser.orgId()).map(Team::getLeadEmployeeId);
	}

	@Override
	@Transactional(readOnly = true)
	public Set<UUID> members(UUID teamId) {
		return new HashSet<>(jdbc.sql("select employee_id from team_member where team_id = :t and until is null").param("t", teamId)
				.query(UUID.class).list());
	}

	@Override
	@Transactional(readOnly = true)
	public List<TeamView> optionsFor(UUID departmentId) {
		return teamQuery.options(departmentId).stream().map(o -> new TeamView(o.id(), o.name(), o.departmentId(), o.leadEmployeeId(), true)).toList();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<ProjectView> getProject(UUID id) {
		return projectRepo.findByIdAndOrgId(id, currentUser.orgId()).map(TeamsProjectsFacade::view);
	}

	private static ProjectView view(Project p) {
		return new ProjectView(p.getId(), p.getName(), p.getCode(), p.getClient(), p.getOwnerEmployeeId(), p.getStatus(), p.isBillable(), p.isActive());
	}

	@Override
	@Transactional(readOnly = true)
	public List<Projects.Membership> ofEmployeeProjects(UUID employeeId) {
		return jdbc.sql("select pr.id, pr.name, pr.code, m.role, m.allocation from project_member m join project pr on pr.id = m.project_id "
				+ "where m.employee_id = :e and m.until is null order by m.allocation desc, pr.name").param("e", employeeId)
				.query((rs, i) -> new Projects.Membership(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5)))
				.list();
	}

	@Override
	@Transactional(readOnly = true)
	public int totalAllocation(UUID employeeId) {
		return jdbc.sql("select coalesce(sum(m.allocation), 0) from project_member m join project pr on pr.id = m.project_id "
				+ "where m.employee_id = :e and m.until is null and pr.status in " + ProjectQueryService.LIVE).param("e", employeeId)
				.query(Integer.class).single();
	}

	@Override
	@Transactional(readOnly = true)
	public boolean acceptsTimesheets(UUID projectId) {
		return projectRepo.findByIdAndOrgId(projectId, currentUser.orgId())
				.map(p -> p.isActive() && ProjectStatus.ACTIVE.name().equals(p.getStatus())).orElse(false);
	}

	@Override
	@Transactional(readOnly = true)
	public Set<UUID> projectMembers(UUID projectId) {
		return new HashSet<>(jdbc.sql("select employee_id from project_member where project_id = :p and until is null").param("p", projectId)
				.query(UUID.class).list());
	}
}
