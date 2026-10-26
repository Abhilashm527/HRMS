package com.ivoyant.hrms.org.service;

import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.security.ModuleAccess;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.org.domain.Department;
import com.ivoyant.hrms.org.domain.Team;
import com.ivoyant.hrms.org.dto.TeamDtos;
import com.ivoyant.hrms.org.repo.DepartmentRepository;
import com.ivoyant.hrms.org.repo.TeamRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Everything that reads teams: list, pickers, detail, members and usage. */
@Service
public class TeamQueryService {

	private static final String SEP = " › ";

	private final TeamRepository repo;
	private final DepartmentRepository deptRepo;
	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final ModuleAccess access;
	private final People people;

	public TeamQueryService(TeamRepository repo, DepartmentRepository deptRepo, JdbcClient jdbc, CurrentUser currentUser,
			ModuleAccess access, People people) {
		this.repo = repo;
		this.deptRepo = deptRepo;
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.access = access;
		this.people = people;
	}

	TeamDtos.Node node(Team t, DeptTree depts, Map<UUID, String> names, Map<UUID, Integer> counts) {
		Department d = t.getDepartmentId() == null ? null : depts.get(t.getDepartmentId());
		String lead = t.getLeadEmployeeId() == null ? null : names.get(t.getLeadEmployeeId());
		List<String> warnings = new ArrayList<>();
		if (d != null && !d.isActive()) {
			warnings.add("Department inactive");
		}
		if (t.isActive() && t.getLeadEmployeeId() == null) {
			warnings.add("No team lead");
		}
		return new TeamDtos.Node(t.getId(), t.getName(),
				d == null ? null : new TeamDtos.Department(d.getId(), d.getName(), String.join(SEP, depts.path(d.getId()))),
				lead == null ? null : new TeamDtos.Person(t.getLeadEmployeeId(), lead, People.initials(lead)), t.getDescription(),
				counts == null ? null : counts.getOrDefault(t.getId(), 0), t.isActive(), t.getVersion(), warnings);
	}

	Map<UUID, Integer> memberCounts() {
		Map<UUID, Integer> out = new HashMap<>();
		jdbc.sql("select team_id, count(*) from team_member where org_id = :org and until is null group by team_id")
				.param("org", currentUser.orgId()).query((rs, i) -> {
					out.put(rs.getObject(1, UUID.class), rs.getInt(2));
					return 0;
				}).list();
		return out;
	}

	@Transactional(readOnly = true)
	public Object list(Boolean active, UUID departmentId, boolean includeChildren, String q, boolean stats) {
		UUID org = currentUser.orgId();
		List<Team> all = repo.findByOrgId(org);
		DeptTree depts = new DeptTree(deptRepo.findByOrgId(org));
		Set<UUID> inDepts = departmentId == null ? null : includeChildren ? depts.descendants(departmentId) : Set.of(departmentId);
		Predicate<Team> keep = t -> (active == null || t.isActive() == active)
				&& (inDepts == null || t.getDepartmentId() != null && inDepts.contains(t.getDepartmentId()))
				&& (q == null || q.isBlank() || t.getName().toLowerCase(Locale.ROOT).contains(q.trim().toLowerCase(Locale.ROOT)));
		List<Team> rows = all.stream().filter(keep).sorted(Comparator.comparing(Team::getName, String.CASE_INSENSITIVE_ORDER)).toList();
		if (!access.has("org", "VIEW")) {
			return rows.stream().map(t -> new TeamDtos.Basic(t.getId(), t.getName(), t.getDepartmentId(), t.isActive())).toList();
		}
		Map<UUID, String> names = people.names(rows.stream().map(Team::getLeadEmployeeId).filter(x -> x != null).toList());
		Map<UUID, Integer> counts = stats ? memberCounts() : null;
		return rows.stream().map(t -> node(t, depts, names, counts)).toList();
	}

	/** Active teams in the department and the departments above it (all active teams without a department). */
	@Transactional(readOnly = true)
	public List<TeamDtos.Option> options(UUID departmentId) {
		UUID org = currentUser.orgId();
		DeptTree depts = new DeptTree(deptRepo.findByOrgId(org));
		Set<UUID> allowed = new HashSet<>();
		if (departmentId != null) {
			allowed.add(departmentId);
			depts.ancestors(departmentId).forEach(a -> allowed.add(a.getId()));
		}
		return repo.findByOrgId(org).stream().filter(Team::isActive)
				.filter(t -> departmentId == null || t.getDepartmentId() != null && allowed.contains(t.getDepartmentId()))
				.sorted(Comparator.comparing(Team::getName, String.CASE_INSENSITIVE_ORDER))
				.map(t -> new TeamDtos.Option(t.getId(), t.getName(), t.getDepartmentId(),
						t.getDepartmentId() == null || depts.get(t.getDepartmentId()) == null ? null : depts.get(t.getDepartmentId()).getName(),
						t.getLeadEmployeeId())).toList();
	}

	@Transactional(readOnly = true)
	public TeamDtos.Node get(UUID id) {
		UUID org = currentUser.orgId();
		Team t = repo.findByIdAndOrgId(id, org).orElseThrow(() -> new NotFoundException("Team " + id + " not found"));
		DeptTree depts = new DeptTree(deptRepo.findByOrgId(org));
		Map<UUID, Integer> counts = new HashMap<>();
		counts.put(id, jdbc.sql("select count(*) from team_member where team_id = :t and until is null").param("t", id)
				.query(Integer.class).single());
		return node(t, depts, people.names(t.getLeadEmployeeId() == null ? List.of() : List.of(t.getLeadEmployeeId())), counts);
	}

	@Transactional(readOnly = true)
	public List<TeamDtos.Member> members(UUID id, boolean includePast) {
		Team t = repo.findByIdAndOrgId(id, currentUser.orgId()).orElseThrow(() -> new NotFoundException("Team " + id + " not found"));
		return jdbc.sql("select m.employee_id, e.employee_code, e.display_name, m.is_primary, m.since, m.until from team_member m "
				+ "join employee e on e.id = m.employee_id where m.team_id = :t" + (includePast ? "" : " and m.until is null")
				+ " order by (m.until is not null), e.display_name").param("t", id)
				.query((rs, i) -> new TeamDtos.Member(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getBoolean(4),
						rs.getObject(1, UUID.class).equals(t.getLeadEmployeeId()), rs.getObject(5, LocalDate.class),
						rs.getObject(6, LocalDate.class)))
				.list();
	}

	@Transactional(readOnly = true)
	public TeamDtos.Usage usage(UUID id) {
		Team t = repo.findByIdAndOrgId(id, currentUser.orgId()).orElseThrow(() -> new NotFoundException("Team " + id + " not found"));
		long current = jdbc.sql("select count(*) from team_member where team_id = :t and until is null").param("t", id).query(Long.class).single();
		long past = jdbc.sql("select count(*) from team_member where team_id = :t and until is not null").param("t", id).query(Long.class).single();
		String message = null;
		if (current > 0) {
			message = current + (current == 1 ? " person is" : " people are") + " in " + t.getName()
					+ ". Move them first, or mark the team inactive.";
		}
		else if (past > 0) {
			message = t.getName() + " has past members in its history. Mark it inactive instead of deleting it.";
		}
		// recruiting jobs, onboarding drafts and leave audiences have no tables yet
		return new TeamDtos.Usage(current, past, 0, 0, 0, message);
	}
}
