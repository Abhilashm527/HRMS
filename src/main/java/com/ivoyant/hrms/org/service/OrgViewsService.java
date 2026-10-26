package com.ivoyant.hrms.org.service;

import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.security.ModuleAccess;
import com.ivoyant.hrms.common.web.NotFoundException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The By team and By project views of the org chart: everything for every card in a few queries. */
@Service
public class OrgViewsService {

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final ModuleAccess access;

	OrgViewsService(JdbcClient jdbc, CurrentUser currentUser, ModuleAccess access) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.access = access;
	}

	private record Person(UUID id, String code, String name, String initials, String title, UUID deptId, String deptName, String status) {

		Map<String, Object> card() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", id);
			m.put("code", code);
			m.put("name", name);
			m.put("initials", initials);
			m.put("title", title);
			m.put("department", deptId == null ? null : Map.of("id", deptId, "name", String.valueOf(deptName), "color", Math.floorMod(deptId.hashCode(), 8)));
			return m;
		}
	}

	private Map<UUID, Person> people() {
		Map<UUID, Person> out = new HashMap<>();
		jdbc.sql("""
				select e.id, e.employee_code, e.display_name, e.first_name, e.last_name, jt.name, e.department_id, d.name, e.status
				from employee e left join job_title jt on jt.id = e.title_id left join department d on d.id = e.department_id
				where e.org_id = :org and e.archived_at is null""").param("org", currentUser.orgId()).query((rs, i) -> {
					String f = rs.getString(4);
					String l = rs.getString(5);
					String initials = ((f == null || f.isBlank() ? "" : f.substring(0, 1)) + (l == null || l.isBlank() ? "" : l.substring(0, 1))).toUpperCase();
					UUID id = rs.getObject(1, UUID.class);
					out.put(id, new Person(id, rs.getString(2), rs.getString(3), initials, rs.getString(6), rs.getObject(7, UUID.class), rs.getString(8), rs.getString(9)));
					return 0;
				}).list();
		return out;
	}

	// ================================================================== by team

	@Transactional(readOnly = true)
	public List<Map<String, Object>> teamsView(UUID departmentId) {
		Map<UUID, Person> people = people();
		Map<UUID, List<String>> extra = new HashMap<>();
		jdbc.sql("select x.employee_id, d.name from employee_extra_department x join department d on d.id = x.department_id where x.org_id = :org order by d.name")
				.param("org", currentUser.orgId()).query((rs, i) -> extra.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>()).add(rs.getString(2))).list();
		// every team name each person is in (for the tooltip)
		Map<UUID, List<String>> teamNames = new HashMap<>();
		jdbc.sql("select m.employee_id, t.name from team_member m join team t on t.id = m.team_id where m.org_id = :org and m.until is null and t.active order by m.is_primary desc, t.name")
				.param("org", currentUser.orgId()).query((rs, i) -> teamNames.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>()).add(rs.getString(2))).list();
		Map<UUID, List<Object[]>> members = new HashMap<>();
		jdbc.sql("select team_id, employee_id, is_primary from team_member where org_id = :org and until is null").param("org", currentUser.orgId())
				.query((rs, i) -> members.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>())
						.add(new Object[] {rs.getObject(2, UUID.class), rs.getBoolean(3)})).list();
		List<Map<String, Object>> out = new ArrayList<>();
		jdbc.sql("""
				select t.id, t.name, t.department_id, d.name, t.lead_employee_id, t.ms_channel_id from team t left join department d on d.id = t.department_id
				where t.org_id = :org and t.active and (cast(:dept as uuid) is null or t.department_id = :dept) order by lower(t.name)""")
				.param("org", currentUser.orgId()).param("dept", departmentId, java.sql.Types.OTHER).query((rs, i) -> {
					UUID teamId = rs.getObject(1, UUID.class);
					UUID teamDept = rs.getObject(3, UUID.class);
					UUID leadId = rs.getObject(5, UUID.class);
					Person lead = leadId == null ? null : people.get(leadId);
					List<Map<String, Object>> list = new ArrayList<>();
					List<Object[]> rows = new ArrayList<>(members.getOrDefault(teamId, List.of()));
					rows.sort(Comparator.<Object[], Integer>comparing(r -> r[0].equals(leadId) ? 0 : (Boolean) r[1] ? 1 : 2)
							.thenComparing(r -> people.get((UUID) r[0]) == null ? "" : people.get((UUID) r[0]).name(), String.CASE_INSENSITIVE_ORDER));
					for (Object[] r : rows) {
						Person p = people.get((UUID) r[0]);
						if (p == null || "EXITED".equals(p.status())) {
							continue;
						}
						Map<String, Object> m = p.card();
						m.put("tag", (Boolean) r[1] ? "primary" : "also");
						m.put("crossDepartment", teamDept != null && p.deptId() != null && !teamDept.equals(p.deptId()));
						m.put("teams", teamNames.getOrDefault(p.id(), List.of()));
						m.put("extraDepartments", extra.getOrDefault(p.id(), List.of()));
						list.add(m);
					}
					Map<String, Object> t = new LinkedHashMap<>();
					t.put("id", teamId);
					t.put("name", rs.getString(2));
					t.put("department", teamDept == null ? null : Map.of("id", teamDept, "name", String.valueOf(rs.getString(4)), "color", Math.floorMod(teamDept.hashCode(), 8)));
					t.put("lead", lead == null ? null : Map.of("code", lead.code(), "name", lead.name()));
					t.put("channel", rs.getString(6) == null ? null : Map.of("id", rs.getString(6), "name", rs.getString(4) + " › " + rs.getString(2)));
					t.put("memberCount", list.size());
					t.put("members", list);
					out.add(t);
					return 0;
				}).list();
		return out;
	}

	// ================================================================== by project

	private static final String LIVE = "('PLANNED', 'ACTIVE', 'ON_HOLD')";

	@Transactional(readOnly = true)
	public Map<String, Object> projectsView(boolean includeCompleted) {
		Map<UUID, Person> people = people();
		UUID me = currentUser.employeeId();
		boolean hr = access.has("org", "EDIT");
		// who gives how much to which live project
		Map<UUID, List<Object[]>> perPerson = new HashMap<>();
		jdbc.sql("select m.employee_id, p.name, m.allocation from project_member m join project p on p.id = m.project_id where m.org_id = :org and m.until is null and p.status in " + LIVE
				+ " order by m.allocation desc, p.name").param("org", currentUser.orgId())
				.query((rs, i) -> perPerson.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>()).add(new Object[] {rs.getString(2), rs.getInt(3)})).list();
		Map<UUID, List<Object[]>> members = new HashMap<>();
		jdbc.sql("select project_id, employee_id, role, allocation from project_member where org_id = :org and until is null").param("org", currentUser.orgId())
				.query((rs, i) -> members.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>())
						.add(new Object[] {rs.getObject(2, UUID.class), rs.getString(3), rs.getInt(4)})).list();
		List<Map<String, Object>> projects = new ArrayList<>();
		jdbc.sql("select id, code, name, status, client, owner_employee_id from project where org_id = :org and active and status in " + (includeCompleted ? "('PLANNED', 'ACTIVE', 'ON_HOLD', 'COMPLETED')" : LIVE)
				+ " order by lower(name)").param("org", currentUser.orgId()).query((rs, i) -> {
					UUID id = rs.getObject(1, UUID.class);
					UUID owner = rs.getObject(6, UUID.class);
					String projectName = rs.getString(3);
					Person pm = owner == null ? null : people.get(owner);
					List<Object[]> rows = new ArrayList<>(members.getOrDefault(id, List.of()));
					rows.sort(Comparator.<Object[], Integer>comparing(r -> "LEAD".equals(r[1]) ? 0 : 1).thenComparing(r -> -(Integer) r[2]));
					List<Map<String, Object>> list = new ArrayList<>();
					Set<String> departments = new TreeSet<>();
					int sum = 0;
					for (Object[] r : rows) {
						Person p = people.get((UUID) r[0]);
						if (p == null || "EXITED".equals(p.status())) {
							continue;
						}
						List<Object[]> all = perPerson.getOrDefault(p.id(), List.of());
						int total = all.stream().mapToInt(a -> (Integer) a[1]).sum();
						Map<String, Object> m = p.card();
						m.put("role", r[1]);
						m.put("allocation", r[2]);
						m.put("totalAllocation", total);
						m.put("elsewhere", all.stream().filter(a -> !a[0].equals(projectName)).map(a -> Map.of("project", a[0], "allocation", a[1])).toList());
						list.add(m);
						sum += (Integer) r[2];
						if (p.deptName() != null) {
							departments.add(p.deptName());
						}
					}
					Map<String, Object> pr = new LinkedHashMap<>();
					pr.put("id", id);
					pr.put("code", rs.getString(2));
					pr.put("name", rs.getString(3));
					pr.put("status", rs.getString(4));
					pr.put("client", rs.getString(5) == null || rs.getString(5).isBlank() ? "Internal" : rs.getString(5));
					pr.put("manager", pm == null ? null : Map.of("code", pm.code(), "name", pm.name()));
					pr.put("memberCount", list.size());
					pr.put("fte", BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(100), 1, RoundingMode.HALF_UP));
					pr.put("departments", new ArrayList<>(departments));
					pr.put("canEdit", !"COMPLETED".equals(rs.getString(4)) && (hr || me != null && me.equals(owner)));
					pr.put("members", list);
					projects.add(pr);
					return 0;
				}).list();
		long multi = perPerson.entrySet().stream().filter(e -> people.get(e.getKey()) != null && !"EXITED".equals(people.get(e.getKey()).status()) && e.getValue().size() >= 2).count();
		long over = perPerson.entrySet().stream().filter(e -> people.get(e.getKey()) != null && !"EXITED".equals(people.get(e.getKey()).status())
				&& e.getValue().stream().mapToInt(a -> (Integer) a[1]).sum() > 100).count();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("stats", Map.of("projects", projects.size(), "multiProject", multi, "overAllocated", over));
		out.put("projects", projects);
		return out;
	}

	/** People who can be added to the project (current, not already on it) with what they already give to other projects. */
	@Transactional(readOnly = true)
	public List<Map<String, Object>> candidates(UUID projectId) {
		if (jdbc.sql("select count(*) from project where id = :p and org_id = :org").param("p", projectId).param("org", currentUser.orgId()).query(Long.class).single() == 0) {
			throw new NotFoundException("Project " + projectId + " not found");
		}
		return jdbc.sql("""
				select e.id, e.employee_code, e.display_name, d.name,
				       (select coalesce(sum(m.allocation), 0) from project_member m join project p on p.id = m.project_id
				        where m.employee_id = e.id and m.until is null and p.status in ('PLANNED', 'ACTIVE', 'ON_HOLD'))
				from employee e left join department d on d.id = e.department_id
				where e.org_id = :org and e.archived_at is null and e.status <> 'EXITED'
				  and not exists (select 1 from project_member x where x.project_id = :p and x.employee_id = e.id and x.until is null)
				order by lower(e.display_name)""").param("org", currentUser.orgId()).param("p", projectId).query((rs, i) -> {
					int total = rs.getInt(5);
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("id", rs.getObject(1, UUID.class));
					m.put("code", rs.getString(2));
					m.put("name", rs.getString(3));
					m.put("department", rs.getString(4));
					m.put("totalAllocation", total);
					m.put("defaultAllocation", total < 100 ? 100 - total : 20);
					return m;
				}).list();
	}
}
