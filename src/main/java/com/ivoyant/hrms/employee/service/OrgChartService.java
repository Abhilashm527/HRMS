package com.ivoyant.hrms.employee.service;

import com.ivoyant.hrms.common.event.DepartmentHeadChangedEvent;
import com.ivoyant.hrms.common.event.EmployeeCreatedEvent;
import com.ivoyant.hrms.common.event.EmployeeDepartmentChangedEvent;
import com.ivoyant.hrms.common.event.EmployeeExitedEvent;
import com.ivoyant.hrms.common.event.EmployeeJobChangedEvent;
import com.ivoyant.hrms.common.event.ManagerChangedEvent;
import com.ivoyant.hrms.common.event.ProjectMembershipChangedEvent;
import com.ivoyant.hrms.common.event.RelationCreatedEvent;
import com.ivoyant.hrms.common.event.RelationEndedEvent;
import com.ivoyant.hrms.common.event.ReportsRehomedEvent;
import com.ivoyant.hrms.common.event.TeamMembershipChangedEvent;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.common.web.StatusException;
import com.ivoyant.hrms.org.Positions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * The org chart: the reporting tree with everything a card shows (basic work fields only), and the By-department view.
 * Built from one snapshot of the organization, kept for 60 seconds and dropped when reporting lines, teams, projects or
 * departments change.
 */
@Service
public class OrgChartService {

	private static final long TTL_MS = 60_000;

	record P(UUID id, String code, String name, String initials, String status, UUID manager, UUID dept, String title, int photoVersion) {

		boolean exited() {
			return "EXITED".equals(status);
		}
	}

	record Dept(UUID id, String name, int sort, UUID head) {
	}

	private record Snapshot(long loadedAt, Map<UUID, P> people, Map<UUID, List<P>> kids, Map<UUID, Dept> depts, Map<UUID, List<String>> extraDepts,
			Map<UUID, List<Map<String, Object>>> teams, Map<UUID, List<Map<String, Object>>> projects, Map<UUID, UUID> dotted, Map<UUID, Integer> totalUnder,
			List<P> roots) {
	}

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final Positions positions;
	private final Map<UUID, Snapshot> cache = new ConcurrentHashMap<>();

	OrgChartService(JdbcClient jdbc, CurrentUser currentUser, Positions positions) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.positions = positions;
	}

	// ------------------------------------------------------------------ cache

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void changed(ManagerChangedEvent e) {
		cache.clear();
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void changed(EmployeeJobChangedEvent e) {
		cache.clear();
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void changed(EmployeeExitedEvent e) {
		cache.clear();
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void changed(EmployeeCreatedEvent e) {
		cache.clear();
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void changed(EmployeeDepartmentChangedEvent e) {
		cache.clear();
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void changed(DepartmentHeadChangedEvent e) {
		cache.clear();
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void changed(TeamMembershipChangedEvent e) {
		cache.clear();
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void changed(ProjectMembershipChangedEvent e) {
		cache.clear();
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void changed(RelationCreatedEvent e) {
		cache.clear();
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void changed(RelationEndedEvent e) {
		cache.clear();
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	void changed(ReportsRehomedEvent e) {
		cache.clear();
	}

	private Snapshot snapshot() {
		UUID org = currentUser.orgId();
		Snapshot s = cache.get(org);
		if (s != null && System.currentTimeMillis() - s.loadedAt() < TTL_MS) {
			return s;
		}
		s = build(org);
		cache.put(org, s);
		return s;
	}

	private Snapshot build(UUID org) {
		Map<UUID, Dept> depts = new HashMap<>();
		jdbc.sql("select id, name, sort, head_employee_id from department where org_id = :org").param("org", org)
				.query((rs, i) -> depts.put(rs.getObject(1, UUID.class), new Dept(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3), rs.getObject(4, UUID.class)))).list();
		Map<UUID, P> people = new LinkedHashMap<>();
		jdbc.sql("""
				select e.id, e.employee_code, e.display_name, e.first_name, e.last_name, e.status, e.manager_id, e.department_id, t.name, e.photo_version
				from employee e left join job_title t on t.id = e.title_id where e.org_id = :org and e.archived_at is null order by e.display_name""")
				.param("org", org).query((rs, i) -> {
					String f = rs.getString(4);
					String l = rs.getString(5);
					String initials = ((f == null || f.isBlank() ? "" : f.substring(0, 1)) + (l == null || l.isBlank() ? "" : l.substring(0, 1))).toUpperCase();
					UUID id = rs.getObject(1, UUID.class);
					people.put(id, new P(id, rs.getString(2), rs.getString(3), initials, rs.getString(6), rs.getObject(7, UUID.class), rs.getObject(8, UUID.class),
							rs.getString(9), rs.getInt(10)));
					return 0;
				}).list();
		Comparator<P> order = Comparator.<P, Integer>comparing(p -> p.dept() == null || !depts.containsKey(p.dept()) ? Integer.MAX_VALUE : depts.get(p.dept()).sort())
				.thenComparing(P::name, String.CASE_INSENSITIVE_ORDER);
		Map<UUID, List<P>> kids = new HashMap<>();
		for (P p : people.values()) {
			if (!p.exited() && p.manager() != null) {
				kids.computeIfAbsent(p.manager(), k -> new ArrayList<>()).add(p);
			}
		}
		kids.values().forEach(l -> l.sort(order));
		List<P> roots = people.values().stream().filter(p -> !p.exited() && (p.manager() == null || people.get(p.manager()) == null || people.get(p.manager()).exited()))
				.sorted(order).toList();
		Map<UUID, List<String>> extra = new HashMap<>();
		jdbc.sql("select x.employee_id, d.name from employee_extra_department x join department d on d.id = x.department_id where x.org_id = :org order by d.name")
				.param("org", org).query((rs, i) -> extra.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>()).add(rs.getString(2))).list();
		Map<UUID, List<Map<String, Object>>> teams = new HashMap<>();
		jdbc.sql("select m.employee_id, t.name, m.is_primary from team_member m join team t on t.id = m.team_id where m.org_id = :org and m.until is null and t.active order by m.is_primary desc, t.name")
				.param("org", org).query((rs, i) -> teams.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>())
						.add(Map.of("name", rs.getString(2), "primary", rs.getBoolean(3)))).list();
		Map<UUID, List<Map<String, Object>>> projects = new HashMap<>();
		jdbc.sql("select m.employee_id, p.name, m.allocation, m.role from project_member m join project p on p.id = m.project_id where m.org_id = :org and m.until is null and p.status in ('PLANNED', 'ACTIVE', 'ON_HOLD') order by m.allocation desc, p.name")
				.param("org", org).query((rs, i) -> projects.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>())
						.add(Map.of("name", rs.getString(2), "allocation", rs.getInt(3), "role", rs.getString(4)))).list();
		Map<UUID, UUID> dotted = new HashMap<>();
		jdbc.sql("select employee_id, related_id from employee_relation where org_id = :org and type = 'DOTTED' and ended_at is null and from_date <= current_date and (to_date is null or to_date >= current_date)")
				.param("org", org).query((rs, i) -> dotted.put(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class))).list();
		Map<UUID, Integer> totals = new HashMap<>();
		for (P p : people.values()) {
			if (!p.exited()) {
				under(p.id(), kids, totals, new HashSet<>());
			}
		}
		return new Snapshot(System.currentTimeMillis(), people, kids, depts, extra, teams, projects, dotted, totals, roots);
	}

	/** People below (recursive); a loop in bad data cannot run forever. */
	private static int under(UUID id, Map<UUID, List<P>> kids, Map<UUID, Integer> memo, Set<UUID> path) {
		Integer known = memo.get(id);
		if (known != null) {
			return known;
		}
		if (!path.add(id)) {
			return 0;
		}
		int n = 0;
		for (P c : kids.getOrDefault(id, List.of())) {
			n += 1 + under(c.id(), kids, memo, path);
		}
		path.remove(id);
		memo.put(id, n);
		return n;
	}

	// ------------------------------------------------------------------ tree

	public Map<String, Object> tree(UUID rootId, Integer depth, UUID departmentId, String q, String include) {
		Snapshot s = snapshot();
		String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
		int max = !needle.isEmpty() ? 20 : depth == null ? 20 : Math.min(Math.max(depth, 1), 20);
		Set<String> inc = include == null || include.isBlank() ? Set.of("teams", "projects", "extradepts")
				: new HashSet<>(List.of(include.toLowerCase(Locale.ROOT).split(",")));
		List<P> roots;
		if (rootId != null) {
			P r = s.people().get(rootId);
			if (r == null || r.exited()) {
				throw new NotFoundException("Employee " + rootId + " not found");
			}
			roots = List.of(r);
		}
		else if (departmentId != null) {
			roots = departmentRoots(s, departmentId);
		}
		else {
			roots = s.roots();
		}
		Set<UUID> shown = new LinkedHashSet<>();
		Map<UUID, UUID> parent = new HashMap<>();
		List<Map<String, Object>> forest = new ArrayList<>();
		for (P r : roots) {
			if (!shown.contains(r.id())) {
				forest.add(node(s, r, 1, max, shown, parent, inc));
			}
		}
		List<Map<String, Object>> dotted = new ArrayList<>();
		s.dotted().forEach((from, to) -> {
			if (shown.contains(from) && shown.contains(to)) {
				dotted.add(Map.of("from", from, "to", to));
			}
		});
		int managers = (int) shown.stream().filter(id -> !s.kids().getOrDefault(id, List.of()).isEmpty()).count();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("roots", forest);
		out.put("dotted", dotted);
		out.put("stats", Map.of("people", shown.size(), "managers", managers));
		if (!needle.isEmpty()) {
			List<UUID> hits = new ArrayList<>();
			Set<UUID> expand = new LinkedHashSet<>();
			for (UUID id : shown) {
				P p = s.people().get(id);
				String dept = p.dept() == null || s.depts().get(p.dept()) == null ? "" : s.depts().get(p.dept()).name();
				if (p.name().toLowerCase(Locale.ROOT).contains(needle) || (p.title() != null && p.title().toLowerCase(Locale.ROOT).contains(needle))
						|| dept.toLowerCase(Locale.ROOT).contains(needle)) {
					hits.add(id);
					for (UUID up = parent.get(id); up != null && expand.add(up); up = parent.get(up)) {
						// walk to the root
					}
				}
			}
			out.put("hits", hits);
			out.put("expand", expand);
		}
		return out;
	}

	/** The department's head is the root; with no head, the people whose manager sits outside the department. */
	private List<P> departmentRoots(Snapshot s, UUID departmentId) {
		Dept d = s.depts().get(departmentId);
		if (d == null) {
			throw new NotFoundException("Department " + departmentId + " not found");
		}
		P head = d.head() == null ? null : s.people().get(d.head());
		if (head != null && !head.exited()) {
			return List.of(head);
		}
		return s.people().values().stream().filter(p -> !p.exited() && departmentId.equals(p.dept())
				&& (p.manager() == null || s.people().get(p.manager()) == null || !departmentId.equals(s.people().get(p.manager()).dept()))).toList();
	}

	private Map<String, Object> node(Snapshot s, P p, int level, int max, Set<UUID> shown, Map<UUID, UUID> parent, Set<String> inc) {
		shown.add(p.id());
		List<P> below = s.kids().getOrDefault(p.id(), List.of());
		Map<String, Object> n = new LinkedHashMap<>();
		n.put("id", p.id());
		n.put("code", p.code());
		n.put("name", p.name());
		n.put("initials", p.initials());
		n.put("title", p.title());
		n.put("photoUrl", "/api/v1/employees/" + p.id() + "/photo?s=64&v=" + p.photoVersion());
		Dept d = p.dept() == null ? null : s.depts().get(p.dept());
		n.put("department", d == null ? null : Map.of("id", d.id(), "name", d.name(), "color", Math.floorMod(d.id().hashCode(), 8)));
		if (inc.contains("extradepts")) {
			n.put("extraDepartments", s.extraDepts().getOrDefault(p.id(), List.of()));
		}
		n.put("onNotice", "ON_NOTICE".equals(p.status()));
		n.put("directReports", below.size());
		n.put("totalUnder", s.totalUnder().getOrDefault(p.id(), 0));
		if (inc.contains("teams")) {
			n.put("teams", s.teams().getOrDefault(p.id(), List.of()));
		}
		if (inc.contains("projects")) {
			n.put("projects", s.projects().getOrDefault(p.id(), List.of()));
		}
		UUID dt = s.dotted().get(p.id());
		P dotted = dt == null ? null : s.people().get(dt);
		n.put("dottedTo", dotted == null ? null : Map.of("code", dotted.code(), "name", dotted.name()));
		if (level < max) {
			List<Map<String, Object>> children = new ArrayList<>();
			for (P c : below) {
				if (!shown.contains(c.id())) {
					parent.put(c.id(), p.id());
					children.add(node(s, c, level + 1, max, shown, parent, inc));
				}
			}
			n.put("children", children);
			n.put("hasMore", false);
		}
		else {
			n.put("hasMore", !below.isEmpty());
		}
		return n;
	}

	// ------------------------------------------------------------------ by department

	public Map<String, Object> departmentsView(String headRef) {
		Snapshot s = snapshot();
		if (s.roots().isEmpty()) {
			return Map.of("top", Map.of(), "heads", List.of(), "otherRoots", List.of());
		}
		Optional<UUID> topPosition = positions.top();
		List<P> byCode = s.roots().stream().sorted(Comparator.comparing(P::code)).toList();
		P top = byCode.get(0);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("top", person(top));
		out.put("otherRoots", byCode.stream().skip(1).map(this::person).toList());
		List<P> heads = s.kids().getOrDefault(top.id(), List.of());
		List<Map<String, Object>> headCards = new ArrayList<>();
		for (P h : heads) {
			Map<String, Object> m = person(h);
			Dept d = h.dept() == null ? null : s.depts().get(h.dept());
			m.put("department", d == null ? null : Map.of("id", d.id(), "name", d.name(), "color", Math.floorMod(d.id().hashCode(), 8)));
			m.put("totalUnder", s.totalUnder().getOrDefault(h.id(), 0));
			headCards.add(m);
		}
		out.put("heads", headCards);
		P selected = null;
		if (headRef != null && !headRef.isBlank()) {
			selected = heads.stream().filter(h -> h.id().toString().equals(headRef) || h.code().equalsIgnoreCase(headRef)).findFirst()
					.orElseThrow(() -> new StatusException(HttpStatus.NOT_FOUND, "NOT_A_HEAD", "That person does not report to the top of the organization"));
		}
		else if (!heads.isEmpty()) {
			selected = heads.get(0);
		}
		if (selected != null) {
			Map<String, Object> sel = person(selected);
			Dept d = selected.dept() == null ? null : s.depts().get(selected.dept());
			sel.put("department", d == null ? null : d.name());
			List<Map<String, Object>> groups = new ArrayList<>();
			for (P g : s.kids().getOrDefault(selected.id(), List.of())) {
				Map<String, Object> gm = person(g);
				Dept gd = g.dept() == null ? null : s.depts().get(g.dept());
				gm.put("department", gd == null ? null : Map.of("id", gd.id(), "name", gd.name(), "color", Math.floorMod(gd.id().hashCode(), 8)));
				gm.put("reports", s.kids().getOrDefault(g.id(), List.of()).stream().map(this::person).toList());
				groups.add(gm);
			}
			sel.put("groups", groups);
			out.put("selected", sel);
		}
		else {
			out.put("selected", null);
		}
		return out;
	}

	private Map<String, Object> person(P p) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", p.id());
		m.put("code", p.code());
		m.put("name", p.name());
		m.put("title", p.title());
		m.put("initials", p.initials());
		return m;
	}
}
