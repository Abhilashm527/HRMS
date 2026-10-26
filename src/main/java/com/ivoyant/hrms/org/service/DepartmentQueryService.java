package com.ivoyant.hrms.org.service;

import com.ivoyant.hrms.common.forms.RecordCustomFields;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.security.ModuleAccess;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.common.web.PageResponse;
import com.ivoyant.hrms.org.domain.Department;
import com.ivoyant.hrms.org.dto.DepartmentBasic;
import com.ivoyant.hrms.org.dto.DepartmentEmployee;
import com.ivoyant.hrms.org.dto.DepartmentNode;
import com.ivoyant.hrms.org.dto.DepartmentOption;
import com.ivoyant.hrms.org.dto.DepartmentSummary;
import com.ivoyant.hrms.org.dto.DepartmentTree;
import com.ivoyant.hrms.org.dto.DepartmentUsage;
import com.ivoyant.hrms.org.dto.TreeHealth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Everything that reads departments: tree, list, summary, pickers, path, people, usage and tree health. */
@Service
public class DepartmentQueryService {

	private static final String SEP = " › ";

	private final DepartmentViews views;
	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final ModuleAccess access;
	private final RecordCustomFields customFields;

	public DepartmentQueryService(DepartmentViews views, JdbcClient jdbc, CurrentUser currentUser, ModuleAccess access,
			RecordCustomFields customFields) {
		this.views = views;
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.access = access;
		this.customFields = customFields;
	}

	/**
	 * Members without access to department settings get the short form. Admins get the flat list (with path and level)
	 * or, for {@code view=tree}, the nested tree.
	 */
	@Transactional(readOnly = true)
	public Object list(Boolean active, String q, UUID parentId, String view, boolean stats) {
		DepartmentViews.Snapshot s = views.snapshot(stats);
		Predicate<Department> keep = d -> (active == null || d.isActive() == active)
				&& (parentId == null || parentId.equals(d.getParentId()))
				&& matches(d, q);
		if (!access.has("org", "VIEW")) {
			return views.flatOrder(s).stream().filter(keep)
					.map(d -> new DepartmentBasic(d.getId(), d.getName(), d.getCode(), d.getParentId(), d.isActive())).toList();
		}
		if ("tree".equalsIgnoreCase(view)) {
			int deepest = s.tree().deepest();
			return new DepartmentTree(s.maxDepth(), deepest, deepest > s.maxDepth(), s.nested(), views.tree(s, keep, stats));
		}
		return views.flatOrder(s).stream().filter(keep).map(d -> views.node(s, d, stats, true)).toList();
	}

	@Transactional(readOnly = true)
	public DepartmentSummary summary() {
		DepartmentViews.Snapshot s = views.snapshot(false);
		List<Department> all = views.flatOrder(s);
		long active = all.stream().filter(Department::isActive).count();
		long without = all.stream().filter(d -> d.isActive() && d.getHeadEmployeeId() == null).count();
		int deepest = s.tree().deepest();
		String text = all.size() + (all.size() == 1 ? " department" : " departments")
				+ (without > 0 ? " · " + without + " without a head" : "");
		return new DepartmentSummary(all.size(), active, without, deepest, s.maxDepth(), text,
				deepest + (deepest == 1 ? " level" : " levels") + " · max " + s.maxDepth());
	}

	/** Active departments for pickers, in tree order, with their path. */
	@Transactional(readOnly = true)
	public List<DepartmentOption> options() {
		DepartmentViews.Snapshot s = views.snapshot(false);
		return views.flatOrder(s).stream().filter(Department::isActive)
				.map(d -> new DepartmentOption(d.getId(), d.getName(), d.getCode(), String.join(SEP, s.tree().path(d.getId()))))
				.toList();
	}

	@Transactional(readOnly = true)
	public DepartmentNode get(UUID id) {
		DepartmentViews.Snapshot s = views.snapshot(true);
		Department d = s.tree().get(id);
		if (d == null) {
			throw new NotFoundException("Department " + id + " not found");
		}
		return views.node(s, d, true, true).withExtras(customFields.read("department", id), List.of());
	}

	/** Ancestors from the root down to the department itself. */
	@Transactional(readOnly = true)
	public List<DepartmentBasic> path(UUID id) {
		DepartmentViews.Snapshot s = views.snapshot(false);
		require(s, id);
		List<DepartmentBasic> out = new ArrayList<>();
		s.tree().ancestors(id).forEach(a -> out.add(0, basic(a)));
		out.add(basic(s.tree().get(id)));
		return out;
	}

	/** The department and everything below it. */
	@Transactional(readOnly = true)
	public List<DepartmentBasic> descendants(UUID id) {
		DepartmentViews.Snapshot s = views.snapshot(false);
		require(s, id);
		return s.tree().descendants(id).stream().map(s.tree()::get).map(DepartmentQueryService::basic).toList();
	}

	@Transactional(readOnly = true)
	public PageResponse<DepartmentEmployee> employees(UUID id, boolean includeChildren, String status, int page, int size) {
		DepartmentViews.Snapshot s = views.snapshot(false);
		require(s, id);
		Set<UUID> ids = includeChildren ? s.tree().descendants(id) : Set.of(id);
		boolean all = status == null || status.isBlank();
		String where = "department_id in (:ids)" + (all ? "" : " and status = :status");
		int pageSize = Math.min(Math.max(size, 1), 100);
		long total = jdbc.sql("select count(*) from employee where " + where).param("ids", ids)
				.param("status", all ? "" : status.toUpperCase(Locale.ROOT)).query(Long.class).single();
		List<DepartmentEmployee> items = jdbc.sql("select id, employee_code, display_name, work_email, status, department_id "
				+ "from employee where " + where + " order by display_name limit :limit offset :offset")
				.param("ids", ids).param("status", all ? "" : status.toUpperCase(Locale.ROOT))
				.param("limit", pageSize).param("offset", Math.max(page, 0) * pageSize)
				.query((rs, i) -> new DepartmentEmployee(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
						rs.getString(4), rs.getString(5), rs.getObject(6, UUID.class)))
				.list();
		return new PageResponse<>(items, Math.max(page, 0), pageSize, total);
	}

	/** What blocks deleting or deactivating a department, with the message the screen shows. */
	@Transactional(readOnly = true)
	public DepartmentUsage usage(UUID id) {
		DepartmentViews.Snapshot s = views.snapshot(false);
		Department d = require(s, id);
		Set<UUID> subtree = s.tree().descendants(id);
		long employees = count("select count(*) from employee where department_id = :id and status <> 'EXITED'", id);
		long exited = count("select count(*) from employee where department_id = :id and status = 'EXITED'", id);
		long total = jdbc.sql("select count(*) from employee where department_id in (:ids) and status <> 'EXITED'")
				.param("ids", subtree).query(Long.class).single();
		long children = s.tree().childrenOf(id).size();
		long titles = count("select count(*) from job_title where department_id = :id", id);
		long teams = count("select count(*) from team where department_id = :id", id);
		long roleScopes = count("select count(*) from user_role where scope = 'DEPARTMENT' and scope_ref = :id", id);
		String idText = id.toString();
		long pendingHires = jdbc.sql("""
				select count(*) from config_field_value v join config_field f on f.id = v.field_id
				where f.area_key = 'onboarding' and f.key = 'dept' and v.value #>> '{}' = :id""")
				.param("id", idText).query(Long.class).single();
		long fieldValues = jdbc.sql("""
				select count(*) from config_field_value v join config_field f on f.id = v.field_id
				where f.type = 'DEPARTMENT' and not (f.area_key = 'onboarding' and f.key = 'dept') and v.value #>> '{}' = :id""")
				.param("id", idText).query(Long.class).single();
		// recruiting jobs, leave campaigns and positions have no tables yet, so nothing can point at the department from there
		return new DepartmentUsage(employees, total, exited, children, titles, teams, 0, pendingHires, roleScopes, 0, 0,
				fieldValues, message(d.getName(), employees, exited, children));
	}

	private static String message(String name, long employees, long exited, long children) {
		List<String> parts = new ArrayList<>();
		if (employees > 0) {
			parts.add(employees + (employees == 1 ? " person" : " people"));
		}
		if (children > 0) {
			parts.add(children + (children == 1 ? " sub-department" : " sub-departments"));
		}
		if (parts.isEmpty() && exited > 0) {
			return exited + (exited == 1 ? " former employee has" : " former employees have") + " " + name
					+ " in their history. Mark it inactive instead of deleting it.";
		}
		if (parts.isEmpty()) {
			return null;
		}
		return String.join(" and ", parts) + (employees + children == 1 ? " is" : " are") + " in " + name
				+ ". Move them first, or mark it inactive.";
	}

	/** Cycles, over-deep levels, nesting that is off but still used, departments with no head, inactive parents. */
	@Transactional(readOnly = true)
	public TreeHealth validate() {
		DepartmentViews.Snapshot s = views.snapshot(false);
		List<Department> all = views.flatOrder(s);
		List<TreeHealth.Item> over = all.stream().filter(d -> s.tree().level(d.getId()) > s.maxDepth())
				.map(d -> new TreeHealth.Item(d.getId(), d.getName(), s.tree().level(d.getId()))).toList();
		List<TreeHealth.Item> nestedButOff = s.nested() ? List.of() : all.stream().filter(d -> d.getParentId() != null)
				.map(d -> new TreeHealth.Item(d.getId(), d.getName(), null)).toList();
		List<TreeHealth.Item> noHead = all.stream().filter(d -> d.isActive() && d.getHeadEmployeeId() == null)
				.map(d -> new TreeHealth.Item(d.getId(), d.getName(), null)).toList();
		List<TreeHealth.Item> inactiveParent = all.stream().filter(d -> d.isActive() && d.getParentId() != null)
				.filter(d -> {
					Department p = s.tree().get(d.getParentId());
					return p != null && !p.isActive();
				}).map(d -> new TreeHealth.Item(d.getId(), d.getName(), null)).toList();
		return new TreeHealth(s.tree().cycles(), over, nestedButOff, noHead, inactiveParent);
	}

	// ---- helpers ----

	private static boolean matches(Department d, String q) {
		if (q == null || q.isBlank()) {
			return true;
		}
		String needle = q.trim().toLowerCase(Locale.ROOT);
		return contains(d.getName(), needle) || contains(d.getCode(), needle) || contains(d.getCostCentre(), needle);
	}

	private static boolean contains(String value, String needle) {
		return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
	}

	private Department require(DepartmentViews.Snapshot s, UUID id) {
		Department d = s.tree().get(id);
		if (d == null) {
			throw new NotFoundException("Department " + id + " not found");
		}
		return d;
	}

	private static DepartmentBasic basic(Department d) {
		return new DepartmentBasic(d.getId(), d.getName(), d.getCode(), d.getParentId(), d.isActive());
	}

	private long count(String sql, UUID id) {
		return jdbc.sql(sql).param("id", id).query(Long.class).single();
	}

	static List<String> names(List<Department> list) {
		return list.stream().map(Department::getName).collect(Collectors.toList());
	}
}
