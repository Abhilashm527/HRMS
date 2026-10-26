package com.ivoyant.hrms.org.service;

import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.security.ModuleAccess;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.org.domain.Project;
import com.ivoyant.hrms.org.domain.ProjectStatus;
import com.ivoyant.hrms.org.dto.ProjectDtos;
import com.ivoyant.hrms.org.repo.ProjectRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Everything that reads projects: list, pickers, summary, detail, members, usage and the allocation views. */
@Service
public class ProjectQueryService {

	/** Statuses whose members' allocation counts toward the 100% cap. */
	static final String LIVE = "('PLANNED', 'ACTIVE', 'ON_HOLD')";

	private final ProjectRepository repo;
	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final ModuleAccess access;
	private final People people;

	public ProjectQueryService(ProjectRepository repo, JdbcClient jdbc, CurrentUser currentUser, ModuleAccess access, People people) {
		this.repo = repo;
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.access = access;
		this.people = people;
	}

	ProjectDtos.Node node(Project p, Map<UUID, String> names, Map<UUID, int[]> stats) {
		ProjectStatus status = ProjectStatus.parse(p.getStatus());
		String manager = p.getOwnerEmployeeId() == null ? null : names.get(p.getOwnerEmployeeId());
		int[] s = stats == null ? null : stats.getOrDefault(p.getId(), new int[] {0, 0});
		List<String> warnings = new ArrayList<>();
		if (p.isActive() && status == ProjectStatus.ACTIVE && p.getOwnerEmployeeId() == null) {
			warnings.add("No project manager");
		}
		return new ProjectDtos.Node(p.getId(), p.getName(), p.getCode(), p.getClient(), p.isBillable(), p.getStatus(),
				status == null ? p.getStatus() : status.label(),
				manager == null ? null : new ProjectDtos.Person(p.getOwnerEmployeeId(), manager), p.getStartDate(), p.getEndDate(),
				p.getDepartmentId(), p.getDescription(), s == null ? null : s[0], s == null ? null : s[1], p.isActive(),
				p.getVersion(), warnings);
	}

	/** Current member count and allocation sum per project. */
	Map<UUID, int[]> stats() {
		Map<UUID, int[]> out = new HashMap<>();
		jdbc.sql("select project_id, count(*), coalesce(sum(allocation), 0) from project_member where org_id = :org and until is null group by project_id")
				.param("org", currentUser.orgId()).query((rs, i) -> {
					out.put(rs.getObject(1, UUID.class), new int[] {rs.getInt(2), rs.getInt(3)});
					return 0;
				}).list();
		return out;
	}

	@Transactional(readOnly = true)
	public Object list(String status, Boolean active, String client, UUID managerId, UUID memberId, String q, boolean withStats) {
		List<Project> all = repo.findByOrgId(currentUser.orgId());
		ProjectStatus wanted = status == null || status.isBlank() ? null : ProjectStatus.parse(status);
		List<UUID> ofMember = memberId == null ? null : jdbc.sql("select project_id from project_member where employee_id = :e and until is null")
				.param("e", memberId).query(UUID.class).list();
		Predicate<Project> keep = p -> (active == null || p.isActive() == active) && (wanted == null || wanted.name().equals(p.getStatus()))
				&& (client == null || client.isBlank() || client.equalsIgnoreCase(p.getClient()))
				&& (managerId == null || managerId.equals(p.getOwnerEmployeeId())) && (ofMember == null || ofMember.contains(p.getId()))
				&& (q == null || q.isBlank() || p.getName().toLowerCase(Locale.ROOT).contains(q.trim().toLowerCase(Locale.ROOT))
						|| p.getCode().toLowerCase(Locale.ROOT).contains(q.trim().toLowerCase(Locale.ROOT)));
		List<Project> rows = all.stream().filter(keep).sorted(Comparator.comparing(Project::getName, String.CASE_INSENSITIVE_ORDER)).toList();
		if (!access.has("org", "VIEW")) {
			return rows.stream().map(p -> new ProjectDtos.Basic(p.getId(), p.getName(), p.getCode(), p.getStatus(), p.isActive())).toList();
		}
		Map<UUID, String> names = people.names(rows.stream().map(Project::getOwnerEmployeeId).filter(x -> x != null).toList());
		Map<UUID, int[]> stats = withStats ? stats() : null;
		return rows.stream().map(p -> node(p, names, stats)).toList();
	}

	/** Pickers: for timesheets only ACTIVE projects; otherwise planned, active and on hold. Archived projects never show. */
	@Transactional(readOnly = true)
	public List<ProjectDtos.Option> options(boolean forTimesheet) {
		return repo.findByOrgId(currentUser.orgId()).stream().filter(Project::isActive)
				.filter(p -> forTimesheet ? ProjectStatus.ACTIVE.name().equals(p.getStatus()) : !ProjectStatus.COMPLETED.name().equals(p.getStatus()))
				.sorted(Comparator.comparing(Project::getName, String.CASE_INSENSITIVE_ORDER))
				.map(p -> new ProjectDtos.Option(p.getId(), p.getName(), p.getCode(), p.getClient(), p.getStatus())).toList();
	}

	@Transactional(readOnly = true)
	public ProjectDtos.Summary summary() {
		List<Project> all = repo.findByOrgId(currentUser.orgId());
		Map<String, Long> by = new LinkedHashMap<>();
		for (ProjectStatus s : ProjectStatus.values()) {
			by.put(s.name(), all.stream().filter(p -> s.name().equals(p.getStatus())).count());
		}
		long archived = all.stream().filter(p -> !p.isActive()).count();
		List<ProjectDtos.AllocationRow> rows = allocation(null, null);
		long full = rows.stream().filter(r -> "FULL".equals(r.state())).count();
		long over = rows.stream().filter(r -> "OVER".equals(r.state())).count();
		return new ProjectDtos.Summary(all.size(), by, archived, full, over, all.size() + (all.size() == 1 ? " project" : " projects")
				+ " · " + by.get("ACTIVE") + " active");
	}

	@Transactional(readOnly = true)
	public ProjectDtos.Node get(UUID id) {
		Project p = repo.findByIdAndOrgId(id, currentUser.orgId()).orElseThrow(() -> new NotFoundException("Project " + id + " not found"));
		Map<UUID, String> names = people.names(p.getOwnerEmployeeId() == null ? List.of() : List.of(p.getOwnerEmployeeId()));
		return node(p, names, stats());
	}

	@Transactional(readOnly = true)
	public List<ProjectDtos.Member> members(UUID id, boolean includePast) {
		Project p = repo.findByIdAndOrgId(id, currentUser.orgId()).orElseThrow(() -> new NotFoundException("Project " + id + " not found"));
		return jdbc.sql("select m.employee_id, e.employee_code, e.display_name, m.role, m.allocation, m.since, m.until, m.note, "
				+ "(select coalesce(sum(m2.allocation), 0) from project_member m2 join project p2 on p2.id = m2.project_id "
				+ "where m2.employee_id = m.employee_id and m2.until is null and p2.status in " + LIVE + ") as total "
				+ "from project_member m join employee e on e.id = m.employee_id where m.project_id = :p"
				+ (includePast ? "" : " and m.until is null") + " order by (m.until is not null), e.display_name").param("p", id)
				.query((rs, i) -> {
					int total = rs.getInt(9);
					List<String> warnings = rs.getObject(7) == null && total > 100
							? List.of(rs.getString(3).split(" ")[0] + " is at " + total + "% across projects") : List.of();
					return new ProjectDtos.Member(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
							rs.getInt(5), rs.getObject(6, LocalDate.class), rs.getObject(7, LocalDate.class), rs.getString(8),
							rs.getObject(1, UUID.class).equals(p.getOwnerEmployeeId()), total, warnings);
				})
				.list();
	}

	@Transactional(readOnly = true)
	public ProjectDtos.Usage usage(UUID id) {
		Project p = repo.findByIdAndOrgId(id, currentUser.orgId()).orElseThrow(() -> new NotFoundException("Project " + id + " not found"));
		long current = jdbc.sql("select count(*) from project_member where project_id = :p and until is null").param("p", id).query(Long.class).single();
		long past = jdbc.sql("select count(*) from project_member where project_id = :p and until is not null").param("p", id).query(Long.class).single();
		String message = current + past > 0 ? p.getName() + " has people on it or in its history. Archive it instead of deleting it." : null;
		// timesheets and invoices do not exist yet
		return new ProjectDtos.Usage(current, past, 0, 0, message);
	}

	/**
	 * People and how much of their time is taken by planned, active and on-hold projects. State: FULL (100%), OVER (above),
	 * UNDER (1–99) or NONE (0).
	 */
	@Transactional(readOnly = true)
	public List<ProjectDtos.AllocationRow> allocation(String state, UUID departmentId) {
		String wanted = state == null || state.isBlank() ? null : state.trim().toUpperCase(Locale.ROOT);
		return jdbc.sql("select e.id, e.employee_code, e.display_name, e.department_id, "
				+ "coalesce((select sum(m.allocation) from project_member m join project p on p.id = m.project_id "
				+ "where m.employee_id = e.id and m.until is null and p.status in " + LIVE + "), 0) as total "
				+ "from employee e where e.org_id = :org and e.status <> 'EXITED'" + (departmentId == null ? "" : " and e.department_id = :dept")
				+ " order by e.display_name")
				.param("org", currentUser.orgId()).param("dept", departmentId, java.sql.Types.OTHER)
				.query((rs, i) -> {
					int total = rs.getInt(5);
					return new ProjectDtos.AllocationRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
							rs.getObject(4, UUID.class), total, total > 100 ? "OVER" : total == 100 ? "FULL" : total > 0 ? "UNDER" : "NONE");
				}).list().stream().filter(r -> wanted == null || wanted.equals(r.state())).toList();
	}
}
