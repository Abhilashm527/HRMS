package com.ivoyant.hrms.iam.service;

import com.ivoyant.hrms.common.security.CurrentUser;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Access audit log: every recorded change to roles, who holds them, what they may see, relationships and org settings,
 * written as one line a person can read. Reads {@code audit_log}; nothing here writes to it.
 */
@Service
public class AccessAuditService {

	public static final List<String> KINDS = List.of("Roles", "Assignments", "Permissions", "Relationships", "Settings");

	/** Which audit rows count as access changes, and under which filter chip. */
	private static final String KIND_SQL = """
			case
			  when a.module = 'iam' and a.entity = 'Role' then 'Roles'
			  when a.module = 'iam' and a.entity in ('UserRole', 'Delegation') then 'Assignments'
			  when (a.module = 'iam' and a.entity in ('RolePermissions', 'RoleNavigation', 'AreaOwner'))
			    or (a.module = 'config' and a.entity in ('ConfigFieldAccess', 'AreaOwners')) then 'Permissions'
			  when (a.module = 'org' and a.entity = 'Relation') or (a.module = 'employee' and a.entity in ('ReportingLine', 'ReportingLines')) then 'Relationships'
			  when a.module = 'org' or (a.module = 'config' and (a.entity in ('ConfigArea', 'ConfigSection', 'ConfigField', 'EmployeeLayout') or a.entity like 'ConfigLists:%')) then 'Settings'
			end""";

	private static final int WINDOW = 3000;

	private static final Map<String, String> MODULES = Map.of("employee", "Employees", "org", "Org structure", "leave", "Leave", "attendance", "Attendance",
			"payroll", "Payroll", "recruiting", "Recruiting", "performance", "Performance", "assets", "Assets", "requests", "Requests");
	private static final Map<String, String> LEVELS = Map.of("view", "View", "edit", "Edit", "approve", "Approve", "admin", "Full");
	private static final List<String> LEVEL_ORDER = List.of("view", "edit", "approve", "admin");
	private static final Map<String, String> SCOPES = Map.of("OWN", "Own", "TEAM", "Team", "DEPARTMENT", "Department", "LOCATION", "Location", "ORGANIZATION", "Everyone");

	public record Entry(UUID id, OffsetDateTime at, String by, String kind, String act, String target, String det) {
	}

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final JsonMapper json;

	AccessAuditService(JdbcClient jdbc, CurrentUser currentUser, JsonMapper json) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.json = json;
	}

	@Transactional(readOnly = true)
	public Map<String, Object> page(String kind, String q, int page, int size) {
		List<Entry> all = entries(kind, q);
		int from = Math.min(all.size(), Math.max(0, page) * size);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("counts", counts());
		out.put("total", all.size());
		out.put("page", page);
		out.put("size", size);
		out.put("items", all.subList(from, Math.min(all.size(), from + size)));
		return out;
	}

	@Transactional(readOnly = true)
	public String csv(String kind, String q) {
		StringBuilder b = new StringBuilder("When,By,Area,Action,Target,Details\n");
		for (Entry e : entries(kind, q)) {
			b.append(cell(e.at().toString())).append(',').append(cell(e.by())).append(',').append(cell(e.kind())).append(',')
					.append(cell(e.act())).append(',').append(cell(e.target())).append(',').append(cell(e.det())).append('\n');
		}
		return b.toString();
	}

	private Map<String, Long> counts() {
		Map<String, Long> m = new LinkedHashMap<>();
		m.put("All", 0L);
		KINDS.forEach(k -> m.put(k, 0L));
		jdbc.sql("select kind, count(*) from (select " + KIND_SQL + " as kind from audit_log a where a.org_id = :org) x where kind is not null group by kind")
				.param("org", currentUser.orgId()).query((rs, i) -> m.put(rs.getString(1), rs.getLong(2))).list();
		m.put("All", KINDS.stream().mapToLong(m::get).sum());
		return m;
	}

	private List<Entry> entries(String kind, String q) {
		String k = kind == null || kind.isBlank() || "All".equalsIgnoreCase(kind) ? null : KINDS.stream().filter(x -> x.equalsIgnoreCase(kind)).findFirst().orElse("-");
		List<Map<String, Object>> rows = jdbc.sql("""
				select * from (
				  select a.id, a.at, a.module, a.entity, a.entity_id, a.action, a.before::text as b, a.after::text as af,
				         coalesce(e.display_name, case when a.actor_id is null then 'System' else 'Someone' end) as who,
				""" + KIND_SQL + """
				  as kind
				  from audit_log a left join employee e on e.id = a.actor_id
				  where a.org_id = :org) x
				where kind is not null and (cast(:k as text) is null or kind = :k)
				order by at desc""" + " limit " + WINDOW).param("org", currentUser.orgId()).param("k", k, java.sql.Types.VARCHAR)
				.query((rs, i) -> {
					Map<String, Object> m = new HashMap<>();
					m.put("id", rs.getObject("id", UUID.class));
					m.put("at", rs.getObject("at", OffsetDateTime.class));
					m.put("module", rs.getString("module"));
					m.put("entity", rs.getString("entity"));
					m.put("entityId", rs.getString("entity_id"));
					m.put("action", rs.getString("action"));
					m.put("before", rs.getString("b"));
					m.put("after", rs.getString("af"));
					m.put("who", rs.getString("who"));
					m.put("kind", rs.getString("kind"));
					return m;
				}).list();
		Names names = new Names();
		List<Entry> out = new ArrayList<>();
		String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
		for (Map<String, Object> r : rows) {
			Entry e = describe(r, names);
			if (needle.isEmpty() || (e.by() + " " + e.act() + " " + e.target() + " " + e.det()).toLowerCase(Locale.ROOT).contains(needle)) {
				out.add(e);
			}
		}
		return out;
	}

	// ------------------------------------------------------------------ one row in words

	private Entry describe(Map<String, Object> r, Names names) {
		String module = (String) r.get("module");
		String entity = (String) r.get("entity");
		String action = (String) r.get("action");
		String id = (String) r.get("entityId");
		JsonNode b = tree((String) r.get("before"));
		JsonNode a = tree((String) r.get("after"));
		JsonNode cur = a != null && !a.isNull() ? a : b;
		String act;
		String target;
		String det = "";
		String key = module + ":" + (entity.startsWith("ConfigLists:") ? "ConfigLists" : entity);
		switch (key) {
			case "iam:Role" -> {
				act = verb("Role", action);
				target = text(cur, "name");
				det = "UPDATE".equals(action) ? diff(b, a, Set.of("name", "description")) : text(cur, "description");
			}
			case "iam:RolePermissions" -> {
				act = "Permissions changed";
				target = names.role(id);
				det = permDiff(b, a);
			}
			case "iam:RoleNavigation" -> {
				String area = text(cur, "area");
				act = switch (area) {
					case "menu" -> "Menu access changed";
					case "tab" -> "Tab access changed";
					default -> "Page access changed";
				};
				target = join(text(cur, "role"), text(cur, "label"));
				det = text(b, "value").isEmpty() ? "Set to " + word(text(a, "value")) : word(text(b, "value")) + " → " + word(text(a, "value"));
			}
			case "iam:UserRole" -> {
				act = "CREATE".equals(action) ? "Role assigned" : "DELETE".equals(action) ? "Role revoked" : verb("Assignment", action);
				String emp = text(cur, "employeeId");
				target = join(emp.isEmpty() ? "" : names.employee(emp), text(cur, "roleName"));
				det = SCOPES.getOrDefault(text(cur, "scope"), text(cur, "scope"));
				if (!text(cur, "scopeRef").isEmpty()) {
					det += " · " + text(cur, "scopeRef");
				}
				det = det.isEmpty() ? "" : "Scope: " + det;
			}
			case "iam:Delegation" -> {
				act = "CREATE".equals(action) ? "Delegation added" : "DELETE".equals(action) ? "Delegation removed" : verb("Delegation", action);
				target = names.user(text(cur, "fromUserId")) + " → " + names.user(text(cur, "toUserId"));
				det = join(MODULES.getOrDefault(text(cur, "module"), text(cur, "module")), range(text(cur, "startsAt"), text(cur, "endsAt")));
			}
			case "iam:AreaOwner" -> {
				act = "CREATE".equals(action) ? "Area owner added" : "Area owner removed";
				target = text(cur, "areaKey");
				det = names.employee(text(cur, "employeeId"));
			}
			case "config:AreaOwners" -> {
				act = "Area owners changed";
				target = id;
				det = roleList(b, names) + " → " + roleList(a, names);
			}
			case "config:ConfigFieldAccess" -> {
				act = "Field security changed";
				target = "MATRIX".equals(action) ? id : names.field(id);
				det = a != null && a.isArray() ? a.size() + " change" + (a.size() == 1 ? "" : "s") : diff(b, a, null);
			}
			case "org:OrgSetting" -> {
				act = "Setting changed";
				target = cur != null && cur.isObject() && cur.properties().iterator().hasNext() ? cur.properties().iterator().next().getKey() : id;
				det = scalar(b, target) + " → " + scalar(a, target);
			}
			case "org:Relation" -> {
				act = switch (action) {
					case "CREATE" -> "Relationship added";
					case "DELETE" -> "Relationship removed";
					case "END" -> "Relationship ended";
					default -> "Relationship changed";
				};
				JsonNode n = b != null && !b.isNull() && "END".equals(action) ? b : cur;
				target = join(text(n == null ? null : n.get("employee"), "name"), text(n == null ? null : n.get("related"), "name"));
				target = target.replace(" · ", " → ");
				det = "END".equals(action) ? text(a, "reason") : join(text(cur, "typeLabel"), range(text(cur, "fromDate"), text(cur, "toDate")));
			}
			case "employee:ReportingLine" -> {
				act = "Manager changed";
				target = names.employee(id);
				det = diff(b, a, null);
			}
			case "employee:ReportingLines" -> {
				act = "Reporting lines saved";
				target = "Org chart";
				det = summary(a);
			}
			case "config:ConfigLists" -> {
				act = "Lists changed";
				target = humanize(entity.substring("ConfigLists:".length())) + " settings";
				det = summary(a);
			}
			default -> {
				act = verb(humanize(entity), action);
				target = firstOf(cur, "name", "label", "title", "displayName");
				if (target.isEmpty()) {
					target = id == null ? "" : id;
				}
				det = "UPDATE".equals(action) ? diff(b, a, null) : "";
			}
		}
		return new Entry((UUID) r.get("id"), (OffsetDateTime) r.get("at"), (String) r.get("who"), (String) r.get("kind"), act,
				target == null ? "" : target, det == null ? "" : det);
	}

	/** "Leave: None → View · Assets scope: Team → Everyone", from two permission lists. */
	private static String permDiff(JsonNode before, JsonNode after) {
		Map<String, String[]> b = highest(before);
		Map<String, String[]> a = highest(after);
		List<String> parts = new ArrayList<>();
		java.util.TreeSet<String> mods = new java.util.TreeSet<>(b.keySet());
		mods.addAll(a.keySet());
		for (String m : mods) {
			String[] x = b.getOrDefault(m, new String[] {"", ""});
			String[] y = a.getOrDefault(m, new String[] {"", ""});
			String label = MODULES.getOrDefault(m, m);
			if (!x[0].equals(y[0])) {
				parts.add(label + ": " + LEVELS.getOrDefault(x[0], "None") + " → " + LEVELS.getOrDefault(y[0], "None"));
			} else if (!x[1].equals(y[1])) {
				parts.add(label + " scope: " + SCOPES.getOrDefault(x[1], x[1]) + " → " + SCOPES.getOrDefault(y[1], y[1]));
			}
		}
		return parts.isEmpty() ? "No change" : String.join(" · ", parts);
	}

	private static Map<String, String[]> highest(JsonNode list) {
		Map<String, String[]> m = new TreeMap<>();
		if (list == null || !list.isArray()) {
			return m;
		}
		for (JsonNode p : list) {
			String mod = text(p, "module");
			String act = text(p, "action");
			String[] cur = m.get(mod);
			if (cur == null || LEVEL_ORDER.indexOf(act) > LEVEL_ORDER.indexOf(cur[0])) {
				m.put(mod, new String[] {act, text(p, "scope")});
			}
		}
		return m;
	}

	private static String roleList(JsonNode ids, Names names) {
		if (ids == null || !ids.isArray() || ids.isEmpty()) {
			return "Super admin only";
		}
		List<String> l = new ArrayList<>();
		ids.forEach(x -> l.add(names.role(x.asString())));
		return String.join(", ", l);
	}

	/** Up to three changed top-level values, "Name: A → B". */
	private static String diff(JsonNode b, JsonNode a, Set<String> only) {
		if (b == null || a == null || !b.isObject() || !a.isObject()) {
			return "";
		}
		List<String> parts = new ArrayList<>();
		Set<String> keys = new java.util.LinkedHashSet<>();
		a.properties().forEach(e -> keys.add(e.getKey()));
		b.properties().forEach(e -> keys.add(e.getKey()));
		for (String k : keys) {
			if ((only != null && !only.contains(k)) || Set.of("id", "version", "updatedAt", "createdAt", "updatedBy", "createdBy").contains(k)) {
				continue;
			}
			JsonNode x = b.get(k);
			JsonNode y = a.get(k);
			if ((x == null || x.isValueNode() || x.isNull()) && (y == null || y.isValueNode() || y.isNull()) && !String.valueOf(x).equals(String.valueOf(y))) {
				parts.add(humanize(k) + ": " + show(x) + " → " + show(y));
			}
			if (parts.size() == 3) {
				break;
			}
		}
		return String.join(" · ", parts);
	}

	private static String summary(JsonNode n) {
		if (n == null || n.isNull()) {
			return "";
		}
		if (n.isArray()) {
			return n.size() + " change" + (n.size() == 1 ? "" : "s");
		}
		if (n.isObject()) {
			List<String> parts = new ArrayList<>();
			n.properties().forEach(e -> {
				if (parts.size() < 3) {
					JsonNode v = e.getValue();
					parts.add(humanize(e.getKey()) + ": " + (v.isArray() ? String.valueOf(v.size()) : v.isValueNode() ? v.asString() : "changed"));
				}
			});
			return String.join(" · ", parts);
		}
		return n.asString();
	}

	private static String scalar(JsonNode n, String k) {
		return n == null || n.get(k) == null ? "—" : show(n.get(k));
	}

	private static String show(JsonNode n) {
		if (n == null || n.isNull()) {
			return "—";
		}
		String s = n.asString();
		if ("true".equals(s)) {
			return "On";
		}
		if ("false".equals(s)) {
			return "Off";
		}
		return s.isEmpty() ? "—" : s.length() > 60 ? s.substring(0, 57) + "…" : s;
	}

	private static String word(String v) {
		return switch (v) {
			case "on" -> "On";
			case "off" -> "Off";
			case "edit" -> "Edit";
			case "view" -> "View";
			case "hidden" -> "Hidden";
			default -> v.isEmpty() ? "—" : v;
		};
	}

	private static String verb(String what, String action) {
		return switch (action) {
			case "CREATE" -> what + " created";
			case "UPDATE" -> what + " updated";
			case "DELETE" -> what + " deleted";
			default -> what + " · " + action.toLowerCase(Locale.ROOT).replace('_', ' ');
		};
	}

	private static String humanize(String s) {
		String spaced = s.replaceAll("([a-z])([A-Z])", "$1 $2").replace('_', ' ').toLowerCase(Locale.ROOT);
		return spaced.isEmpty() ? s : Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
	}

	private static String range(String from, String to) {
		String f = from.length() >= 10 ? from.substring(0, 10) : from;
		String t = to.length() >= 10 ? to.substring(0, 10) : to;
		return f.isEmpty() ? "" : t.isEmpty() ? "from " + f : f + " to " + t;
	}

	private static String join(String a, String b) {
		return a.isEmpty() ? b : b.isEmpty() ? a : a + " · " + b;
	}

	private static String firstOf(JsonNode n, String... keys) {
		for (String k : keys) {
			String v = text(n, k);
			if (!v.isEmpty()) {
				return v;
			}
		}
		return "";
	}

	private static String text(JsonNode n, String k) {
		if (n == null || !n.isObject()) {
			return "";
		}
		JsonNode v = n.get(k);
		return v == null || v.isNull() || !v.isValueNode() ? "" : v.asString();
	}

	private JsonNode tree(String s) {
		if (s == null) {
			return null;
		}
		try {
			return json.readTree(s);
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static String cell(String s) {
		String v = s == null ? "" : s;
		// keep spreadsheet apps from running a cell as a formula
		if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0) {
			v = "'" + v;
		}
		return "\"" + v.replace("\"", "\"\"") + "\"";
	}

	/** Names looked up once per request. */
	private final class Names {
		private final Map<String, String> roles = new HashMap<>();
		private final Map<String, String> employees = new HashMap<>();
		private final Map<String, String> users = new HashMap<>();
		private final Map<String, String> fields = new HashMap<>();

		String role(String id) {
			return roles.computeIfAbsent(String.valueOf(id), k -> lookup("select name from role where id::text = :id", k, "Deleted role"));
		}

		String employee(String id) {
			return employees.computeIfAbsent(String.valueOf(id), k -> lookup("select display_name from employee where id::text = :id", k, "Someone"));
		}

		String user(String id) {
			return users.computeIfAbsent(String.valueOf(id),
					k -> lookup("select e.display_name from app_user u join employee e on e.id = u.employee_id where u.id::text = :id", k, "Someone"));
		}

		String field(String id) {
			return fields.computeIfAbsent(String.valueOf(id), k -> lookup("select label from config_field where id::text = :id", k, k));
		}

		private String lookup(String sql, String id, String fallback) {
			return jdbc.sql(sql).param("id", id).query(String.class).optional().orElse(fallback);
		}
	}
}
