package com.ivoyant.hrms.employee.service;

import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.StatusException;
import com.ivoyant.hrms.config.FieldAccess;
import com.ivoyant.hrms.config.FieldConfig;
import com.ivoyant.hrms.employee.EmployeeStatuses;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The directory: Cards / Table / Split read the same rows; each column follows what the caller may see of that field. */
@Service
public class DirectoryService {

	public record Query(String q, UUID departmentId, UUID locationId, String status, UUID managerId, boolean includeExited, String group,
			String sort, boolean desc, int page, int size, Set<String> fields) {
	}

	/** Directory column → profile field key (for the access check). */
	private static final Map<String, String> COLUMN_FIELD = Map.of("title", "title", "position", "designation", "department", "department",
			"location", "location", "manager", "manager", "doj", "joining_date", "email", "work_email", "phone", "phone", "status", "status");

	private static final String SELECT = """
			select e.id, e.employee_code, e.display_name, e.first_name, e.last_name, e.title_id, jt.name as title_name, e.designation_id,
			       d.title as position_name, e.department_id, dep.name as dept_name, e.location_id, l.name as loc_name, e.manager_id,
			       m.display_name as mgr_name, e.joining_date, e.work_email, e.phone, e.status, e.probation_end_date, e.confirmed_on, e.photo_version""";
	private static final String FROM = " " + """
			 from employee e
			 left join department dep on dep.id = e.department_id
			 left join location l on l.id = e.location_id
			 left join designation d on d.id = e.designation_id
			 left join job_title jt on jt.id = e.title_id
			 left join employee m on m.id = e.manager_id
			 where e.org_id = :org and e.archived_at is null""";

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final FieldConfig fieldConfig;
	private final EmployeeActions actions;

	DirectoryService(JdbcClient jdbc, CurrentUser currentUser, FieldConfig fieldConfig, EmployeeActions actions) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.fieldConfig = fieldConfig;
		this.actions = actions;
	}

	private record Where(String sql, Map<String, Object> params) {
	}

	private Where where(Query q, boolean withFilters) {
		StringBuilder sql = new StringBuilder();
		Map<String, Object> p = new LinkedHashMap<>();
		p.put("org", currentUser.orgId());
		if (!q.includeExited()) {
			sql.append(" and e.status <> 'EXITED'");
		}
		if (!withFilters) {
			return new Where(sql.toString(), p);
		}
		if (q.q() != null && !q.q().isBlank()) {
			sql.append(" and (lower(e.display_name) like :q or lower(e.employee_code) like :q or lower(e.work_email) like :q or lower(coalesce(jt.name, '')) like :q)");
			p.put("q", "%" + q.q().trim().toLowerCase() + "%");
		}
		if (q.departmentId() != null) {
			sql.append(" and e.department_id = :dept");
			p.put("dept", q.departmentId());
		}
		if (q.locationId() != null) {
			sql.append(" and e.location_id = :loc");
			p.put("loc", q.locationId());
		}
		if (q.managerId() != null) {
			sql.append(" and e.manager_id = :mgr");
			p.put("mgr", q.managerId());
		}
		if (q.status() != null && !q.status().isBlank()) {
			sql.append(switch (q.status().toUpperCase()) {
				case "PROBATION" -> " and e.status = 'ACTIVE' and e.probation_end_date is not null and e.confirmed_on is null";
				case "ACTIVE" -> " and e.status = 'ACTIVE' and not (e.probation_end_date is not null and e.confirmed_on is null)";
				case "NOTICE", "ON_NOTICE" -> " and e.status = 'ON_NOTICE'";
				case "EXITED" -> " and e.status = 'EXITED'";
				default -> throw new StatusException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Unknown status '" + q.status() + "'");
			});
		}
		return new Where(sql.toString(), p);
	}

	@Transactional(readOnly = true)
	public Map<String, Object> list(Query q) {
		actions.require("view_directory", null);
		Where w = where(q, true);
		long total = jdbc.sql("select count(*) " + FROM + w.sql()).params(w.params()).query(Long.class).single();
		long totalAll = jdbc.sql("select count(*) " + FROM + where(q, false).sql()).params(where(q, false).params()).query(Long.class).single();
		String order = switch (q.sort() == null ? "" : q.sort()) {
			case "id" -> " order by e.employee_code";
			case "newest" -> " order by e.joining_date desc, e.display_name";
			default -> " order by lower(e.display_name)";
		};
		if (q.desc() && !order.contains("desc")) {
			order += " desc";
		}
		int size = Math.max(1, Math.min(q.size() <= 0 ? 25 : q.size(), 200));
		int page = Math.max(0, q.page());
		Map<String, FieldAccess> levels = fieldConfig.levels(EmployeeFields.AREA, null);
		List<Map<String, Object>> items = jdbc.sql(SELECT + FROM + w.sql() + order + " limit " + size + " offset " + (long) page * size).params(w.params())
				.query((rs, i) -> row(rs, levels, q.fields())).list();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("items", items);
		out.put("page", page);
		out.put("size", size);
		out.put("total", total);
		out.put("totalAll", totalAll);
		if (q.group() != null && !q.group().isBlank()) {
			out.put("groups", groups(q, w));
		}
		return out;
	}

	/** Up to {@code max} rows of the filtered directory, shaped for the caller (export). */
	@Transactional(readOnly = true)
	public List<Map<String, Object>> rows(Query q, int max) {
		Where w = where(q, true);
		Map<String, FieldAccess> levels = fieldConfig.levels(EmployeeFields.AREA, null);
		return jdbc.sql(SELECT + FROM + w.sql() + " order by lower(e.display_name) limit " + max).params(w.params()).query((rs, i) -> row(rs, levels, q.fields())).list();
	}

	/** Which directory columns the caller can see at all. */
	public java.util.Set<String> visibleColumns() {
		Map<String, FieldAccess> levels = fieldConfig.levels(EmployeeFields.AREA, null);
		java.util.Set<String> out = new java.util.LinkedHashSet<>();
		COLUMN_FIELD.forEach((col, key) -> {
			if (levels.getOrDefault(key, FieldAccess.HIDDEN) != FieldAccess.HIDDEN) {
				out.add(col);
			}
		});
		return out;
	}

	private List<Map<String, Object>> groups(Query q, Where w) {
		String[] cols = switch (q.group()) {
			case "dept", "department" -> new String[] {"e.department_id", "dep.name"};
			case "position", "designation" -> new String[] {"e.designation_id", "d.title"};
			case "location" -> new String[] {"e.location_id", "l.name"};
			default -> throw new StatusException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Group by dept, position or location");
		};
		return jdbc.sql("select " + cols[0] + " as k, " + cols[1] + " as label, count(*) as n " + FROM + w.sql() + " group by " + cols[0] + ", " + cols[1]
				+ " order by lower(coalesce(" + cols[1] + ", 'zzz'))").params(w.params()).query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("key", rs.getObject("k", UUID.class));
					m.put("label", rs.getString("label") == null ? "Unassigned" : rs.getString("label"));
					m.put("count", rs.getLong("n"));
					return m;
				}).list();
	}

	private static boolean shown(Map<String, FieldAccess> levels, String column, Set<String> wanted) {
		if (wanted != null && !wanted.isEmpty() && !wanted.contains(column)) {
			return false;
		}
		return levels.getOrDefault(COLUMN_FIELD.get(column), FieldAccess.HIDDEN) != FieldAccess.HIDDEN;
	}

	private static boolean masked(Map<String, FieldAccess> levels, String column) {
		return levels.getOrDefault(COLUMN_FIELD.get(column), FieldAccess.HIDDEN) == FieldAccess.MASKED;
	}

	private static Object guard(Map<String, FieldAccess> levels, String column, Object v) {
		return masked(levels, column) && v != null ? Masking.mask(v) : v;
	}

	static Map<String, Object> row(ResultSet rs, Map<String, FieldAccess> levels, Set<String> wanted) throws SQLException {
		Map<String, Object> m = new LinkedHashMap<>();
		UUID id = rs.getObject("id", UUID.class);
		String first = rs.getString("first_name");
		String last = rs.getString("last_name");
		m.put("id", id);
		m.put("code", rs.getString("employee_code"));
		m.put("name", rs.getString("display_name"));
		m.put("initials", ((first == null || first.isBlank() ? "" : first.substring(0, 1)) + (last == null || last.isBlank() ? "" : last.substring(0, 1))).toUpperCase());
		m.put("photoUrl", "/api/v1/employees/" + id + "/photo?s=256&v=" + rs.getInt("photo_version"));
		if (shown(levels, "title", wanted)) {
			m.put("title", guard(levels, "title", rs.getString("title_name")));
		}
		if (shown(levels, "position", wanted) && rs.getObject("designation_id") != null) {
			m.put("position", Map.of("id", rs.getObject("designation_id", UUID.class), "name", rs.getString("position_name")));
		}
		if (shown(levels, "department", wanted) && rs.getObject("department_id") != null) {
			UUID d = rs.getObject("department_id", UUID.class);
			m.put("department", Map.of("id", d, "name", rs.getString("dept_name"), "color", Math.floorMod(d.hashCode(), 8)));
		}
		if (shown(levels, "location", wanted) && rs.getObject("location_id") != null) {
			m.put("location", Map.of("id", rs.getObject("location_id", UUID.class), "name", rs.getString("loc_name")));
		}
		if (shown(levels, "manager", wanted) && rs.getObject("manager_id") != null) {
			m.put("manager", Map.of("id", rs.getObject("manager_id", UUID.class), "name", rs.getString("mgr_name")));
		}
		if (shown(levels, "doj", wanted)) {
			m.put("doj", masked(levels, "doj") ? "•• ••• ••••" : rs.getObject("joining_date", LocalDate.class));
		}
		if (shown(levels, "email", wanted)) {
			m.put("email", guard(levels, "email", rs.getString("work_email")));
		}
		if (shown(levels, "phone", wanted)) {
			m.put("phone", guard(levels, "phone", rs.getString("phone")));
		}
		EmployeeStatuses.View st = EmployeeStatuses.of(rs.getString("status"), rs.getObject("probation_end_date", LocalDate.class), rs.getObject("confirmed_on", LocalDate.class));
		m.put("status", st.status());
		m.put("statusLabel", st.label());
		m.put("statusKind", st.kind());
		return m;
	}

	@Transactional(readOnly = true)
	public Map<String, Object> preview(UUID id) {
		actions.require("view_directory", null);
		Map<String, FieldAccess> levels = fieldConfig.levels(EmployeeFields.AREA, id);
		Map<String, Object> row = jdbc.sql(SELECT + FROM + " and e.id = :id").param("org", currentUser.orgId()).param("id", id).query((rs, i) -> row(rs, levels, null))
				.optional().orElseThrow(() -> new com.ivoyant.hrms.common.web.NotFoundException("Employee " + id + " not found"));
		List<Map<String, Object>> reports = jdbc.sql(SELECT + FROM + " and e.manager_id = :id order by lower(e.display_name)").param("org", currentUser.orgId())
				.param("id", id).query((rs, i) -> row(rs, levels, null)).list();
		Map<String, Object> out = new LinkedHashMap<>(row);
		List<Map<String, Object>> fields = new ArrayList<>();
		for (String[] f : new String[][] {{"position", "Designation"}, {"email", "Work email"}, {"phone", "Phone"}, {"location", "Location"}, {"doj", "Joined"},
				{"department", "Department"}, {"manager", "Reports to"}}) {
			if (row.containsKey(f[0])) {
				fields.add(Map.of("key", f[0], "label", f[1], "value", row.get(f[0])));
			}
		}
		out.put("fields", fields);
		out.put("reports", reports);
		return out;
	}

	@Transactional(readOnly = true)
	public Map<String, Object> filters(boolean includeExited) {
		actions.require("view_directory", null);
		Query base = new Query(null, null, null, null, null, includeExited, null, null, false, 0, 0, null);
		Where w = where(base, false);
		List<Map<String, Object>> departments = jdbc.sql("select e.department_id as k, dep.name as label, count(*) as n " + FROM + w.sql()
				+ " and e.department_id is not null group by e.department_id, dep.name order by lower(dep.name)").params(w.params())
				.query((rs, i) -> Map.<String, Object>of("id", rs.getObject("k", UUID.class), "name", rs.getString("label"), "count", rs.getLong("n"))).list();
		List<Map<String, Object>> locations = jdbc.sql("select e.location_id as k, l.name as label, count(*) as n " + FROM + w.sql()
				+ " and e.location_id is not null group by e.location_id, l.name order by lower(l.name)").params(w.params())
				.query((rs, i) -> Map.<String, Object>of("id", rs.getObject("k", UUID.class), "name", rs.getString("label"), "count", rs.getLong("n"))).list();
		Map<String, Long> statuses = new LinkedHashMap<>();
		statuses.put("ACTIVE", 0L);
		statuses.put("PROBATION", 0L);
		statuses.put("NOTICE", 0L);
		jdbc.sql("select e.status, (e.probation_end_date is not null and e.confirmed_on is null) as prob, count(*) as n " + FROM + w.sql() + " group by e.status, prob")
				.params(w.params()).query((rs, i) -> {
					EmployeeStatuses.View v = EmployeeStatuses.of(rs.getString(1), rs.getBoolean(2) ? LocalDate.now() : null, null);
					statuses.merge(v.status(), rs.getLong(3), Long::sum);
					return 0;
				}).list();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("departments", departments);
		out.put("locations", locations);
		out.put("statuses", statuses);
		return out;
	}
}
