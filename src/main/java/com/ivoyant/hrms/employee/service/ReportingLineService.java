package com.ivoyant.hrms.employee.service;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.ConflictException;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.common.web.PageResponse;
import com.ivoyant.hrms.common.web.UnprocessableException;
import com.ivoyant.hrms.org.OrgSettings;
import com.ivoyant.hrms.org.Positions;
import com.ivoyant.hrms.org.Groups;
import com.ivoyant.hrms.org.Relations;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reporting-lines screen: who reports to whom, the org tree, the health report, and the all-or-nothing bulk save. A bulk save is
 * judged on the lines as they will be afterwards (two people can swap managers; a loop is caught on the whole graph), and every
 * wrong row is reported at once.
 */
@Service
public class ReportingLineService {

	private static final int MAX_EXTRA_DEPARTMENTS = 3;

	/** One person as the checks need them. */
	private record E(UUID id, String code, String name, String status, UUID manager, UUID department, UUID position, String title,
			String type) {

		boolean exited() {
			return "EXITED".equals(status);
		}
	}

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final OrgSettings settings;
	private final Positions positions;
	private final EmployeeRecords records;
	private final Relations relations;
	private final Groups groups;
	private final Audit audit;

	public ReportingLineService(JdbcClient jdbc, CurrentUser currentUser, OrgSettings settings, Positions positions,
			EmployeeRecords records, Relations relations, Groups groups, Audit audit) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.settings = settings;
		this.positions = positions;
		this.records = records;
		this.relations = relations;
		this.groups = groups;
		this.audit = audit;
	}

	private Map<UUID, E> load() {
		Map<UUID, E> out = new LinkedHashMap<>();
		jdbc.sql("""
				select e.id, e.employee_code, e.display_name, e.status, e.manager_id, e.department_id, e.designation_id, t.name, et.name
				from employee e left join job_title t on t.id = e.title_id left join employment_type et on et.id = e.employment_type_id
				where e.org_id = :org order by e.display_name""").param("org", currentUser.orgId()).query((rs, i) -> {
					out.put(rs.getObject(1, UUID.class), new E(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
							rs.getObject(5, UUID.class), rs.getObject(6, UUID.class), rs.getObject(7, UUID.class), rs.getString(8), rs.getString(9)));
					return 0;
				}).list();
		return out;
	}

	private boolean mayLackManager(E e) {
		Optional<UUID> top = positions.top();
		return top.isEmpty() || e.position() != null && top.get().equals(e.position());
	}

	private Map<UUID, String> names(String sql) {
		Map<UUID, String> out = new HashMap<>();
		jdbc.sql(sql).param("org", currentUser.orgId()).query((rs, i) -> {
			out.put(rs.getObject(1, UUID.class), rs.getString(2));
			return 0;
		}).list();
		return out;
	}

	private static Map<UUID, List<E>> childrenOf(Map<UUID, E> all, Map<UUID, UUID> managerOverride) {
		Map<UUID, List<E>> out = new HashMap<>();
		for (E e : all.values()) {
			if (e.exited()) {
				continue;
			}
			UUID m = managerOverride != null && managerOverride.containsKey(e.id()) ? managerOverride.get(e.id()) : e.manager();
			if (m != null) {
				out.computeIfAbsent(m, k -> new ArrayList<>()).add(e);
			}
		}
		return out;
	}

	// ================================================================== the table

	/** Rows as the screen shows them; {@code state} is NO_MANAGER, ON_NOTICE_MANAGER or OVER_SPAN. */
	@Transactional(readOnly = true)
	public PageResponse<Map<String, Object>> rows(UUID departmentId, String q, String state, int page, int size) {
		Map<UUID, E> all = load();
		Map<UUID, List<E>> kids = childrenOf(all, null);
		Map<UUID, String> extraDepts = names("select x.employee_id, string_agg(d.name, ', ' order by d.name) from employee_extra_department x join department d on d.id = x.department_id where x.org_id = :org group by x.employee_id");
		Map<UUID, String> teams = names("select m.employee_id, string_agg(t.name, ', ' order by m.is_primary desc, t.name) from team_member m join team t on t.id = m.team_id where m.org_id = :org and m.until is null group by m.employee_id");
		Map<UUID, String> projects = names("select m.employee_id, string_agg(p.name || ' ' || m.allocation || '%', ', ' order by m.allocation desc, p.name) from project_member m join project p on p.id = m.project_id where m.org_id = :org and m.until is null group by m.employee_id");
		Map<UUID, UUID> dotted = new HashMap<>();
		jdbc.sql("select employee_id, related_id from employee_relation where org_id = :org and type = 'DOTTED' and ended_at is null and from_date <= current_date and (to_date is null or to_date >= current_date)")
				.param("org", currentUser.orgId()).query((rs, i) -> {
					dotted.put(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class));
					return 0;
				}).list();
		Map<UUID, String> departments = names("select id, name from department where org_id = :org");
		// the same sets with ids, so the Reporting lines screen can edit them
		Map<UUID, List<Map<String, Object>>> teamItems = new HashMap<>();
		jdbc.sql("select m.employee_id, t.id, t.name, m.is_primary from team_member m join team t on t.id = m.team_id where m.org_id = :org and m.until is null order by m.is_primary desc, t.name")
				.param("org", currentUser.orgId()).query((rs, i) -> teamItems.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>())
						.add(Map.of("teamId", rs.getObject(2, UUID.class), "name", rs.getString(3), "primary", rs.getBoolean(4))))
				.list();
		Map<UUID, List<Map<String, Object>>> projectItems = new HashMap<>();
		jdbc.sql("select m.employee_id, p.id, p.name, m.allocation, coalesce(m.role, '') from project_member m join project p on p.id = m.project_id where m.org_id = :org and m.until is null order by m.allocation desc, p.name")
				.param("org", currentUser.orgId()).query((rs, i) -> projectItems.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>())
						.add(Map.of("projectId", rs.getObject(2, UUID.class), "name", rs.getString(3), "allocation", rs.getInt(4), "role", rs.getString(5))))
				.list();
		Map<UUID, List<UUID>> extraIds = new HashMap<>();
		jdbc.sql("select employee_id, department_id from employee_extra_department where org_id = :org").param("org", currentUser.orgId())
				.query((rs, i) -> extraIds.computeIfAbsent(rs.getObject(1, UUID.class), k -> new ArrayList<>()).add(rs.getObject(2, UUID.class))).list();
		Map<UUID, String> positionNames = names("select id, title from designation where org_id = :org");
		String needle = q == null ? null : q.trim().toLowerCase(Locale.ROOT);
		int span = settings.maxSpan();
		List<Map<String, Object>> rows = new ArrayList<>();
		for (E e : all.values()) {
			if (e.exited() || departmentId != null && !departmentId.equals(e.department())) {
				continue;
			}
			if (needle != null && !needle.isEmpty() && !e.name().toLowerCase(Locale.ROOT).contains(needle) && !e.code().toLowerCase(Locale.ROOT).contains(needle)) {
				continue;
			}
			E manager = e.manager() == null ? null : all.get(e.manager());
			int reports = kids.getOrDefault(e.id(), List.of()).size();
			boolean noManager = e.manager() == null && !mayLackManager(e);
			boolean onNotice = manager != null && "ON_NOTICE".equals(manager.status());
			boolean over = reports > span;
			if ("NO_MANAGER".equalsIgnoreCase(state) && !noManager || "ON_NOTICE_MANAGER".equalsIgnoreCase(state) && !onNotice
					|| "OVER_SPAN".equalsIgnoreCase(state) && !over) {
				continue;
			}
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("employeeId", e.id());
			row.put("employeeCode", e.code());
			row.put("name", e.name());
			row.put("department", e.department() == null ? null : departments.get(e.department()));
			row.put("extraDepartments", extraDepts.get(e.id()));
			row.put("teams", teams.getOrDefault(e.id(), "No team"));
			row.put("projects", projects.getOrDefault(e.id(), "No projects"));
			row.put("manager", manager == null ? null : Map.of("id", manager.id(), "name", manager.name()));
			UUID d = dotted.get(e.id());
			row.put("dotted", d == null || all.get(d) == null ? null : Map.of("id", d, "name", all.get(d).name()));
			row.put("position", e.position() == null ? null : positionNames.get(e.position()));
			row.put("departmentId", e.department());
			row.put("extraDepartmentIds", extraIds.getOrDefault(e.id(), List.of()));
			row.put("teamItems", teamItems.getOrDefault(e.id(), List.of()));
			row.put("projectItems", projectItems.getOrDefault(e.id(), List.of()));
			row.put("type", e.type());
			row.put("reports", reports);
			row.put("noManager", noManager);
			row.put("managerOnNotice", onNotice);
			row.put("overSpan", over);
			row.put("crossDepartment", manager != null && manager.department() != null && e.department() != null && !manager.department().equals(e.department()));
			rows.add(row);
		}
		int pageSize = Math.min(Math.max(size, 1), 500);
		int from = Math.min(Math.max(page, 0) * pageSize, rows.size());
		return new PageResponse<>(rows.subList(from, Math.min(from + pageSize, rows.size())), Math.max(page, 0), pageSize, rows.size());
	}

	/** The chain from the top down to the employee's manager, and whether the employee is at the top. */
	@Transactional(readOnly = true)
	public Map<String, Object> chain(UUID employeeId) {
		Map<UUID, E> all = load();
		E e = all.get(employeeId);
		if (e == null) {
			throw new NotFoundException("Employee " + employeeId + " not found");
		}
		List<Map<String, Object>> chain = new ArrayList<>();
		Set<UUID> seen = new HashSet<>(Set.of(employeeId));
		UUID cursor = e.manager();
		while (cursor != null && all.containsKey(cursor) && seen.add(cursor) && chain.size() < 200) {
			E m = all.get(cursor);
			Map<String, Object> node = new LinkedHashMap<>();
			node.put("id", m.id());
			node.put("name", m.name());
			node.put("title", m.title());
			chain.add(0, node);
			cursor = m.manager();
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("chain", chain);
		out.put("isTop", e.manager() == null);
		out.put("directReports", childrenOf(all, null).getOrDefault(employeeId, List.of()).size());
		return out;
	}

	/** The organization as a tree by manager; dotted lines come back as separate edges. */
	@Transactional(readOnly = true)
	public Map<String, Object> tree(UUID rootId, Integer depth) {
		Map<UUID, E> all = load();
		Map<UUID, List<E>> kids = childrenOf(all, null);
		int max = depth == null ? 20 : Math.min(Math.max(depth, 1), 20);
		List<E> roots = rootId != null ? List.of(Optional.ofNullable(all.get(rootId)).orElseThrow(() -> new NotFoundException("Employee " + rootId + " not found")))
				: all.values().stream().filter(e -> !e.exited() && (e.manager() == null || all.get(e.manager()) == null || all.get(e.manager()).exited())).toList();
		Set<UUID> shown = new HashSet<>();
		List<Map<String, Object>> tree = roots.stream().map(r -> treeNode(r, kids, 1, max, shown)).toList();
		List<Map<String, Object>> dotted = jdbc.sql("select employee_id, related_id from employee_relation where org_id = :org and type = 'DOTTED' and ended_at is null "
				+ "and from_date <= current_date and (to_date is null or to_date >= current_date)").param("org", currentUser.orgId())
				.query((rs, i) -> Map.<String, Object>of("from", rs.getObject(1, UUID.class), "to", rs.getObject(2, UUID.class))).list().stream()
				.filter(m -> shown.contains(m.get("from")) && shown.contains(m.get("to"))).toList();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("roots", tree);
		out.put("dotted", dotted);
		return out;
	}

	private Map<String, Object> treeNode(E e, Map<UUID, List<E>> kids, int level, int max, Set<UUID> shown) {
		shown.add(e.id());
		Map<String, Object> n = new LinkedHashMap<>();
		n.put("id", e.id());
		n.put("name", e.name());
		n.put("title", e.title());
		List<E> below = kids.getOrDefault(e.id(), List.of());
		n.put("directReports", below.size());
		if (level < max) {
			n.put("children", below.stream().filter(c -> !shown.contains(c.id())).map(c -> treeNode(c, kids, level + 1, max, shown)).toList());
		}
		return n;
	}

	/** Everything on the lines that needs attention. */
	@Transactional(readOnly = true)
	public Map<String, Object> health() {
		Map<UUID, E> all = load();
		Map<UUID, List<E>> kids = childrenOf(all, null);
		int maxDepth = settings.maxReportingDepth();
		int span = settings.maxSpan();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("noManager", items(all, e -> !e.exited() && e.manager() == null && !mayLackManager(e), e -> "has no manager"));
		out.put("cycles", cycles(all));
		out.put("overDepth", items(all, e -> !e.exited() && depthOf(all, e.id()) > maxDepth, e -> depthOf(all, e.id()) + " levels deep"));
		out.put("overSpan", items(all, e -> !e.exited() && kids.getOrDefault(e.id(), List.of()).size() > span,
				e -> kids.get(e.id()).size() + " direct reports"));
		out.put("exitedManagers", items(all, e -> !e.exited() && e.manager() != null && all.get(e.manager()) != null && all.get(e.manager()).exited(),
				e -> "reports to " + all.get(e.manager()).name() + " who has left"));
		out.put("onNoticeManagers", items(all, e -> !e.exited() && e.manager() != null && all.get(e.manager()) != null
				&& "ON_NOTICE".equals(all.get(e.manager()).status()), e -> "reports to " + all.get(e.manager()).name() + " who is serving notice"));
		out.put("crossDepartment", items(all, e -> !e.exited() && e.manager() != null && all.get(e.manager()) != null && e.department() != null
				&& all.get(e.manager()).department() != null && !e.department().equals(all.get(e.manager()).department()),
				e -> "reports to " + all.get(e.manager()).name() + " in another department"));
		return out;
	}

	private static List<Map<String, Object>> items(Map<UUID, E> all, Predicate<E> keep, java.util.function.Function<E, String> detail) {
		return all.values().stream().filter(keep).map(e -> {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", e.id());
			m.put("name", e.name());
			m.put("detail", detail.apply(e));
			return m;
		}).toList();
	}

	private static List<List<String>> cycles(Map<UUID, E> all) {
		List<List<String>> out = new ArrayList<>();
		Set<UUID> reported = new HashSet<>();
		for (E start : all.values()) {
			List<UUID> chain = new ArrayList<>();
			UUID cursor = start.id();
			while (cursor != null && all.containsKey(cursor) && !chain.contains(cursor)) {
				chain.add(cursor);
				cursor = all.get(cursor).manager();
			}
			if (cursor != null && chain.contains(cursor) && !reported.contains(cursor)) {
				List<UUID> loop = chain.subList(chain.indexOf(cursor), chain.size());
				reported.addAll(loop);
				out.add(loop.stream().map(u -> all.get(u).name()).toList());
			}
		}
		return out;
	}

	/** Managers above the person plus the person: how many levels deep they are. */
	private static int depthOf(Map<UUID, E> all, UUID id) {
		int d = 1;
		Set<UUID> seen = new HashSet<>(Set.of(id));
		UUID cursor = all.get(id).manager();
		while (cursor != null && all.containsKey(cursor) && seen.add(cursor)) {
			d++;
			cursor = all.get(cursor).manager();
		}
		return d;
	}

	// ================================================================== preview and save

	/** Checks a bulk save and says what would change; nothing is written. */
	@Transactional(readOnly = true)
	public Map<String, Object> preview(Map<String, Object> body) {
		return run(body, true);
	}

	@Transactional
	public Map<String, Object> save(Map<String, Object> body) {
		return run(body, false);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> run(Map<String, Object> body, boolean preview) {
		if (!(body.get("rows") instanceof List<?> raw)) {
			throw new com.ivoyant.hrms.common.web.BadRequestException("rows is required");
		}
		List<Map<String, Object>> rows = (List<Map<String, Object>>) raw;
		LocalDate today = LocalDate.now();
		LocalDate effective = body.get("effectiveDate") == null || body.get("effectiveDate").toString().isBlank() ? today
				: LocalDate.parse(body.get("effectiveDate").toString());
		List<ErrorItem> errors = new ArrayList<>();
		if (effective.isAfter(today)) {
			errors.add(ErrorItem.cell("reportingLines", 0, "effectiveDate", "Scheduled changes are made one person at a time (PUT /employees/{id}/manager)"));
			throw new UnprocessableException(errors);
		}
		Map<UUID, E> all = load();
		Map<UUID, UUID> finalManager = new HashMap<>();
		Map<Integer, UUID> employeeOf = new HashMap<>();
		List<Map<String, Object>> diffs = new ArrayList<>();
		List<String> warnings = new ArrayList<>();
		// 1. each row on its own
		for (int i = 0; i < rows.size(); i++) {
			Map<String, Object> row = rows.get(i);
			UUID id = uuid(row.get("employeeId"));
			E e = id == null ? null : all.get(id);
			if (e == null || e.exited()) {
				errors.add(ErrorItem.cell("reportingLines", i, "employeeId", "That person doesn't exist or has left"));
				continue;
			}
			employeeOf.put(i, id);
			if (row.containsKey("managerId")) {
				UUID m = row.get("managerId") == null || row.get("managerId").toString().isBlank() ? null : uuid(row.get("managerId"));
				if (m == null && row.get("managerId") != null && !row.get("managerId").toString().isBlank()) {
					errors.add(ErrorItem.cell("reportingLines", i, "managerId", "That is not a valid id"));
					continue;
				}
				if (m == null && !mayLackManager(e)) {
					errors.add(ErrorItem.cell("reportingLines", i, "managerId", "MANAGER_REQUIRED: everyone except the top position needs a manager"));
					continue;
				}
				if (m != null && m.equals(id)) {
					errors.add(ErrorItem.cell("reportingLines", i, "managerId", "A person cannot be their own manager"));
					continue;
				}
				if (m != null && (all.get(m) == null || all.get(m).exited())) {
					errors.add(ErrorItem.cell("reportingLines", i, "managerId", "MANAGER_INACTIVE: " + (all.get(m) == null ? "that manager doesn't exist" : all.get(m).name() + " has left")));
					continue;
				}
				if (!java.util.Objects.equals(m, e.manager())) {
					finalManager.put(id, m);
					Map<String, Object> d = new LinkedHashMap<>();
					d.put("row", i);
					d.put("employeeId", id);
					d.put("name", e.name());
					d.put("from", e.manager() == null ? null : all.get(e.manager()).name());
					d.put("to", m == null ? null : all.get(m).name());
					diffs.add(d);
				}
			}
			List<?> extra = row.get("extraDepartmentIds") instanceof List<?> l ? l : null;
			if (extra != null) {
				if (extra.size() > MAX_EXTRA_DEPARTMENTS) {
					errors.add(ErrorItem.cell("reportingLines", i, "extraDepartmentIds", "TOO_MANY_DEPARTMENTS: at most " + MAX_EXTRA_DEPARTMENTS + " extra departments"));
				}
				for (Object o : extra) {
					Boolean ok = jdbc.sql("select active from department where id = :id and org_id = :org").param("id", uuid(o)).param("org", currentUser.orgId())
							.query(Boolean.class).optional().orElse(null);
					if (ok == null || !ok) {
						errors.add(ErrorItem.cell("reportingLines", i, "extraDepartmentIds", "Extra departments must be active departments"));
					}
					else if (uuid(o).equals(e.department())) {
						errors.add(ErrorItem.cell("reportingLines", i, "extraDepartmentIds", "That is already the person's own department"));
					}
				}
			}
		}
		// 2. the whole graph after the change: loops and depth
		Map<UUID, UUID> view = new HashMap<>();
		all.values().forEach(e -> view.put(e.id(), finalManager.containsKey(e.id()) ? finalManager.get(e.id()) : e.manager()));
		Map<UUID, List<E>> kids = childrenOf(all, finalManager);
		int maxDepth = settings.maxReportingDepth();
		for (Map.Entry<Integer, UUID> en : employeeOf.entrySet()) {
			UUID id = en.getValue();
			if (!finalManager.containsKey(id)) {
				continue;
			}
			if (loops(view, id)) {
				errors.add(ErrorItem.cell("reportingLines", en.getKey(), "managerId", "That would make a loop in the reporting lines"));
				continue;
			}
			int above = 0;
			Set<UUID> seen = new HashSet<>(Set.of(id));
			for (UUID c = view.get(id); c != null && all.containsKey(c) && seen.add(c); c = view.get(c)) {
				above++;
			}
			int deepest = above + 1 + height(kids, id, new HashSet<>());
			if (deepest > maxDepth) {
				errors.add(ErrorItem.cell("reportingLines", en.getKey(), "managerId", "That would make a reporting chain " + deepest + " levels long; the limit is " + maxDepth));
			}
			UUID m = view.get(id);
			if (m != null) {
				E mgr = all.get(m);
				if ("ON_NOTICE".equals(mgr.status())) {
					warnings.add(mgr.name() + " is serving notice; their reports will need a new manager");
				}
				if (kids.getOrDefault(m, List.of()).size() > settings.maxSpan()) {
					warnings.add(mgr.name() + " would have " + kids.get(m).size() + " direct reports");
				}
				E e = all.get(id);
				if (mgr.department() != null && e.department() != null && !mgr.department().equals(e.department())) {
					warnings.add(e.name() + " reports to " + mgr.name() + " in another department");
				}
			}
		}
		// 3. dotted lines against the final managers
		for (Map.Entry<Integer, UUID> en : employeeOf.entrySet()) {
			Map<String, Object> row = rows.get(en.getKey());
			if (!row.containsKey("dottedManagerId") || row.get("dottedManagerId") == null || row.get("dottedManagerId").toString().isBlank()) {
				continue;
			}
			UUID id = en.getValue();
			UUID related = uuid(row.get("dottedManagerId"));
			if (related == null || all.get(related) == null || all.get(related).exited()) {
				errors.add(ErrorItem.cell("reportingLines", en.getKey(), "dottedManagerId", "The dotted-line manager must be a current employee"));
			}
			else if (related.equals(id)) {
				errors.add(ErrorItem.cell("reportingLines", en.getKey(), "dottedManagerId", "SAME_PERSON: a person can't have a dotted line to themselves"));
			}
			else if (related.equals(view.get(id))) {
				errors.add(ErrorItem.cell("reportingLines", en.getKey(), "dottedManagerId", all.get(related).name() + " is already the direct manager"));
			}
			else if (above(view, id, related)) {
				errors.add(ErrorItem.cell("reportingLines", en.getKey(), "dottedManagerId", all.get(related).name() + " reports to " + all.get(id).name() + "; a dotted line can't point down"));
			}
		}
		if (!errors.isEmpty()) {
			throw new UnprocessableException(errors);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("valid", true);
		out.put("changes", diffs);
		out.put("warnings", warnings.stream().distinct().toList());
		if (preview) {
			out.put("saved", 0);
			return out;
		}
		// 4. write: managers first, then dotted lines, extra departments, teams and projects
		for (Map.Entry<UUID, UUID> m : finalManager.entrySet()) {
			records.setManagerUnchecked(m.getKey(), m.getValue(), "BULK", effective);
		}
		for (Map.Entry<Integer, UUID> en : employeeOf.entrySet()) {
			Map<String, Object> row = rows.get(en.getKey());
			UUID id = en.getValue();
			try {
				if (row.containsKey("dottedManagerId")) {
					applyDotted(id, row.get("dottedManagerId"), effective);
				}
				if (row.get("extraDepartmentIds") instanceof List<?> extra) {
					applyExtraDepartments(id, extra);
				}
				if (row.containsKey("teams") || row.containsKey("projects")) {
					Map<String, Object> g = new LinkedHashMap<>();
					if (row.containsKey("teams")) {
						g.put("teams", row.get("teams"));
					}
					if (row.containsKey("projects")) {
						g.put("projects", row.get("projects"));
					}
					g.put("effectiveDate", effective.toString());
					groups.replace(id, g);
				}
			}
			catch (UnprocessableException ex) {
				ex.getErrors().forEach(x -> errors.add(ErrorItem.cell("reportingLines", en.getKey(), x.fieldKey(), x.message())));
			}
			catch (ConflictException | NotFoundException ex) {
				errors.add(ErrorItem.cell("reportingLines", en.getKey(), "row", ex.getMessage()));
			}
		}
		if (!errors.isEmpty()) {
			throw new UnprocessableException(errors);
		}
		audit.publish("employee", "ReportingLines", currentUser.orgId(), "SAVE", null,
				"Reporting lines saved · " + rows.size() + (rows.size() == 1 ? " employee" : " employees"));
		out.put("saved", rows.size());
		out.put("message", "Reporting lines saved · " + rows.size() + (rows.size() == 1 ? " employee" : " employees"));
		return out;
	}

	private void applyDotted(UUID employee, Object related, LocalDate effective) {
		UUID wanted = related == null || related.toString().isBlank() ? null : uuid(related);
		UUID currentId = relations.currentDottedRelation(employee).orElse(null);
		UUID currentWith = currentId == null ? null : jdbc.sql("select related_id from employee_relation where id = :id").param("id", currentId)
				.query(UUID.class).optional().orElse(null);
		if (currentWith != null && currentWith.equals(wanted)) {
			return;
		}
		if (currentId != null) {
			relations.endRelation(currentId, "BULK");
		}
		if (wanted != null) {
			relations.addDotted(employee, wanted, effective);
		}
	}

	private void applyExtraDepartments(UUID employee, List<?> wanted) {
		Set<UUID> ids = new HashSet<>();
		wanted.forEach(o -> ids.add(uuid(o)));
		jdbc.sql("delete from employee_extra_department where employee_id = :e and department_id not in (:ids)").param("e", employee)
				.param("ids", ids.isEmpty() ? Set.of(new UUID(0, 0)) : ids).update();
		for (UUID d : ids) {
			jdbc.sql("insert into employee_extra_department (employee_id, department_id, org_id) values (:e, :d, :org) on conflict do nothing")
					.param("e", employee).param("d", d).param("org", currentUser.orgId()).update();
		}
	}

	private static UUID uuid(Object v) {
		try {
			return v == null ? null : UUID.fromString(v.toString().trim());
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	/** Does walking up from {@code id} come back to {@code id}? */
	private static boolean loops(Map<UUID, UUID> managers, UUID id) {
		Set<UUID> seen = new HashSet<>();
		for (UUID c = managers.get(id); c != null; c = managers.get(c)) {
			if (c.equals(id)) {
				return true;
			}
			if (!seen.add(c)) {
				return false;
			}
		}
		return false;
	}

	/** Is {@code above} somewhere above {@code id} in these lines? */
	private static boolean above(Map<UUID, UUID> managers, UUID above, UUID id) {
		Set<UUID> seen = new HashSet<>();
		for (UUID c = managers.get(id); c != null && seen.add(c); c = managers.get(c)) {
			if (c.equals(above)) {
				return true;
			}
		}
		return false;
	}

	private static int height(Map<UUID, List<E>> kids, UUID id, Set<UUID> seen) {
		if (!seen.add(id)) {
			return 0;
		}
		int max = 0;
		for (E c : kids.getOrDefault(id, List.of())) {
			max = Math.max(max, 1 + height(kids, c.id(), seen));
		}
		return max;
	}

	@SuppressWarnings("unused")
	private static Comparator<E> byName() {
		return Comparator.comparing(E::name);
	}
}
