package com.ivoyant.hrms.iam.service;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.web.ConflictException;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.iam.IamDirectory;
import com.ivoyant.hrms.iam.NavAccess;
import com.ivoyant.hrms.iam.PermLevel;
import com.ivoyant.hrms.iam.domain.Scope;
import com.ivoyant.hrms.iam.dto.RolePermissionRequest;
import com.ivoyant.hrms.iam.dto.RolePermissionResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Module permissions: per role, which menus show in the sidebar, which tabs of other people's profiles they can open,
 * and whether each Settings / Configuration page is hidden, read-only or editable. Only changes are stored; anything
 * without a row falls back to a default worked out from the role's module permissions, so nothing moves until an
 * admin changes it. Super admin and HR admin always see everything.
 */
@Service
public class NavAccessService implements NavAccess {

	/** A sidebar entry. {@code module} is the permission module it shows; null means it has none of its own. */
	public record Menu(String key, String label, String portal, String sub, String module, boolean locked) {
	}

	static final String CONFIG = "@config";
	static final String ACCESS = "@access";

	public static final List<Menu> MENUS = List.of(
			new Menu("dashboard", "Dashboard", "admin", "Home of the admin console", null, true),
			new Menu("calendar", "Calendar", "admin", "Company calendar, holidays and events", null, false),
			new Menu("inbox", "Employee requests", "admin", "Letters, tax declarations and other requests", "requests", false),
			new Menu("employees", "Employees", "admin", "People list and profiles", "employee", false),
			new Menu("org", "Org chart", "admin", "Reporting lines and departments", "org", false),
			new Menu("onboarding", "Onboarding", "admin", "New hires and their checklists", "recruiting", false),
			new Menu("assets", "Assets", "admin", "Hardware and software licences", "assets", false),
			new Menu("leave", "Leave & attendance", "admin", "Leave requests, balances and attendance", "leave", false),
			new Menu("payroll", "Payroll", "admin", "Pay runs and payslips", "payroll", false),
			new Menu("recruiting", "Recruiting", "admin", "Openings and candidates", "recruiting", false),
			new Menu("performance", "Performance", "admin", "Reviews and goals", "performance", false),
			new Menu("policies", "Policies", "admin", "Company policies", null, false),
			new Menu("documents", "Documents", "admin", "Employee documents", "employee", false),
			new Menu("reports", "Reports", "admin", "Reports and exports", null, false),
			new Menu("configuration", "Configuration", "admin", "Fields, forms, org structure and module settings", CONFIG, false),
			new Menu("settings", "Settings & roles", "admin", "Roles, assignments and access audit", ACCESS, false),
			new Menu("e-home", "Home", "ess", "Self-service home", null, true),
			new Menu("e-profile", "My profile", "ess", "Their own record", null, true),
			new Menu("e-onboarding", "Onboarding", "ess", "Their onboarding checklist", null, false),
			new Menu("e-leave", "Leave", "ess", "Apply for leave and see balances", null, false),
			new Menu("e-attendance", "Attendance", "ess", "Clock in and timesheets", null, false),
			new Menu("e-pay", "Pay", "ess", "Payslips and tax", null, false),
			new Menu("e-policies", "Policies", "ess", "Company policies", null, false),
			new Menu("e-assets", "My assets", "ess", "Equipment and software assigned to them", null, false),
			new Menu("e-requests", "Requests", "ess", "Letters and other requests", null, false),
			new Menu("e-directory", "Directory", "ess", "Find colleagues", null, false));

	/** Tabs of someone else's profile; Overview is always there. */
	public static final List<String[]> PROFILE_TABS = List.of(new String[] {"personal", "Personal"}, new String[] {"bank", "Bank & statutory"},
			new String[] {"leave", "Leave"}, new String[] {"compensation", "Compensation"}, new String[] {"documents", "Documents"},
			new String[] {"assets", "Assets"}, new String[] {"other", "Additional details"});

	private static final Set<String> ALL_ACCESS = Set.of("super_admin", "hr_admin");

	/**
	 * People menus are on by default whatever the role's module permissions: managers and department heads reach their
	 * people through reporting lines rather than a role permission, and the lists only show who they may see.
	 */
	private static final String EMPLOYEE = "employee";

	private static final Set<String> PEOPLE = Set.of("employees", "org", "documents");

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final IamDirectory iam;
	private final RoleService roles;
	private final Audit audit;
	private final com.ivoyant.hrms.common.authz.AuthzService authz;

	NavAccessService(JdbcClient jdbc, CurrentUser currentUser, IamDirectory iam, RoleService roles, Audit audit,
			com.ivoyant.hrms.common.authz.AuthzService authz) {
		this.authz = authz;
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.iam = iam;
		this.roles = roles;
		this.audit = audit;
	}

	// ------------------------------------------------------------------ editing one role

	@Transactional(readOnly = true)
	public Map<String, Object> ofRole(UUID roleId) {
		Map<String, Object> role = role(roleId);
		String key = (String) role.get("key");
		boolean all = ALL_ACCESS.contains(key);
		Map<String, String> rows = rows(roleId);
		Map<String, PermLevel> levels = levelsOf(roleId);
		boolean owner = areaOwner(roleId);
		// Self-service menus the Employee role already opens are on for everyone, whatever this role says.
		UUID empId = EMPLOYEE.equals(key) ? null
				: jdbc.sql("select id from role where org_id = :org and key = 'employee'").param("org", currentUser.orgId()).query(UUID.class).optional().orElse(null);
		Map<String, String> empRows = empId == null ? Map.of() : rows(empId);
		Map<String, PermLevel> empLevels = empId == null ? Map.of() : levelsOf(empId);
		List<Map<String, Object>> menus = new ArrayList<>();
		for (Menu m : MENUS) {
			Map<String, Object> x = new LinkedHashMap<>();
			x.put("key", m.key());
			x.put("label", m.label());
			x.put("portal", m.portal());
			x.put("sub", m.sub());
			x.put("module", m.module() == null || m.module().startsWith("@") ? null : m.module());
			x.put("locked", m.locked() || all);
			x.put("on", menuOn(m, key, rows, levels, owner));
			x.put("changed", rows.containsKey("menu|" + m.key()));
			x.put("viaEmployee", empId != null && !all && "ess".equals(m.portal()) && !m.locked() && menuOn(m, EMPLOYEE, empRows, empLevels, false));
			menus.add(x);
		}
		Map<String, Boolean> tabs = new LinkedHashMap<>();
		for (String[] t : PROFILE_TABS) {
			tabs.put(t[0], all || !"off".equals(rows.get("tab|employees:" + t[0])));
		}
		Map<String, String> pages = new LinkedHashMap<>();
		rows.forEach((k, v) -> {
			if (k.startsWith("page|") && !all) {
				pages.put(k.substring(5), v);
			}
		});
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("role", role);
		out.put("allAccess", all);
		out.put("canEdit", !all && authz.isSuperAdmin());
		out.put("menus", menus);
		out.put("profileTabs", PROFILE_TABS.stream().map(t -> Map.of("key", t[0], "label", t[1])).toList());
		out.put("tabs", tabs);
		out.put("pages", pages);
		return out;
	}

	/**
	 * Sets one entry. Switching on a menu whose module the role cannot use yet also gives the role View on that module,
	 * otherwise the menu would open onto pages it is not allowed to read.
	 */
	@Transactional
	public Map<String, Object> set(UUID roleId, String area, String key, String value, String pageLabel) {
		Map<String, Object> role = role(roleId);
		String roleKey = (String) role.get("key");
		if (ALL_ACCESS.contains(roleKey)) {
			throw new ConflictException(role.get("name") + " always sees everything");
		}
		String before;
		String label;
		switch (area == null ? "" : area) {
			case "menu" -> {
				Menu m = MENUS.stream().filter(x -> x.key().equals(key)).findFirst().orElseThrow(() -> new NotFoundException("No menu " + key));
				if (m.locked()) {
					throw new ConflictException(m.label() + " is always on");
				}
				if (!"on".equals(value) && !"off".equals(value)) {
					throw new ConflictException("A menu is on or off");
				}
				before = menuOn(m, roleKey, rows(roleId), levelsOf(roleId), areaOwner(roleId)) ? "on" : "off";
				label = (m.portal().equals("ess") ? "Self-service · " : "") + m.label();
				if ("on".equals(value) && m.module() != null && !m.module().startsWith("@") && !PEOPLE.contains(m.key())
						&& !levelsOf(roleId).getOrDefault(m.module(), PermLevel.NONE).atLeast(PermLevel.VIEW)) {
					grantView(roleId, roleKey, m.module());
				}
			}
			case "tab" -> {
				String tab = key.startsWith("employees:") ? key.substring(10) : key;
				String[] t = PROFILE_TABS.stream().filter(x -> x[0].equals(tab)).findFirst().orElseThrow(() -> new NotFoundException("No profile tab " + key));
				if (!"on".equals(value) && !"off".equals(value)) {
					throw new ConflictException("A tab is on or off");
				}
				before = "off".equals(rows(roleId).get("tab|employees:" + tab)) ? "off" : "on";
				label = "Employees · " + t[1] + " tab";
				return save(roleId, role, "tab", "employees:" + tab, value, before, label);
			}
			case "page" -> {
				if (!key.matches("^(configuration|settings)/[a-z0-9/_-]{1,70}$")) {
					throw new ConflictException("Unknown page " + key);
				}
				if (!Set.of("edit", "view", "hidden").contains(value)) {
					throw new ConflictException("A page is hidden, view or edit");
				}
				before = rows(roleId).getOrDefault("page|" + key, "edit");
				label = (key.startsWith("settings/") ? "Settings · " : "Configuration · ") + (pageLabel == null || pageLabel.isBlank() ? key.substring(key.indexOf('/') + 1) : pageLabel.strip());
			}
			default -> throw new ConflictException("Area must be menu, tab or page");
		}
		return save(roleId, role, area, key, value, before, label);
	}

	/** Several pages at once, for the "All edit / All view / All hidden" shortcuts. */
	@Transactional
	public Map<String, Object> setPages(UUID roleId, List<String> keys, String value) {
		Map<String, Object> role = role(roleId);
		if (ALL_ACCESS.contains((String) role.get("key"))) {
			throw new ConflictException(role.get("name") + " always sees everything");
		}
		if (!Set.of("edit", "view", "hidden").contains(value)) {
			throw new ConflictException("A page is hidden, view or edit");
		}
		Map<String, String> rows = rows(roleId);
		int n = 0;
		for (String k : keys) {
			if (!k.matches("^(configuration|settings)/[a-z0-9/_-]{1,70}$")) {
				continue;
			}
			if (!value.equals(rows.getOrDefault("page|" + k, "edit"))) {
				write(roleId, "page", k, value);
				n++;
			}
		}
		if (n > 0) {
			String what = keys.stream().allMatch(k -> k.startsWith("settings/")) ? "Settings pages" : "Configuration pages";
			audit.publish("iam", "RoleNavigation", roleId, "UPDATE", Map.of("role", role.get("name"), "area", "page", "label", what + " · " + n),
					Map.of("role", role.get("name"), "area", "page", "label", what + " · " + n, "value", value));
		}
		return ofRole(roleId);
	}

	private Map<String, Object> save(UUID roleId, Map<String, Object> role, String area, String key, String value, String before, String label) {
		if (!value.equals(before)) {
			write(roleId, area, key, value);
			audit.publish("iam", "RoleNavigation", roleId, "UPDATE", Map.of("role", role.get("name"), "area", area, "key", key, "label", label, "value", before),
					Map.of("role", role.get("name"), "area", area, "key", key, "label", label, "value", value));
		}
		return ofRole(roleId);
	}

	private void write(UUID roleId, String area, String key, String value) {
		jdbc.sql("""
				insert into role_nav (role_id, area, key, value, updated_by) values (:r, :a, :k, :v, :by)
				on conflict (role_id, area, key) do update set value = excluded.value, updated_at = now(), updated_by = excluded.updated_by""")
				.param("r", roleId).param("a", area).param("k", key).param("v", value)
				.param("by", currentUser.employeeId(), java.sql.Types.OTHER).update();
	}

	private void grantView(UUID roleId, String roleKey, String module) {
		List<RolePermissionRequest> keep = new ArrayList<>();
		for (RolePermissionResponse p : roles.permissionsOf(roleId)) {
			keep.add(new RolePermissionRequest(p.module(), p.action(), p.scope()));
		}
		Scope scope = "manager".equals(roleKey) ? Scope.TEAM : "employee".equals(roleKey) ? Scope.OWN : Scope.ORGANIZATION;
		keep.add(new RolePermissionRequest(module, "view", scope));
		roles.replacePermissions(roleId, keep);
	}

	// ------------------------------------------------------------------ the caller

	/** What the caller's sidebar shows: visible menu keys, profile tabs switched off, and pages not fully editable. */
	@Transactional(readOnly = true)
	public Map<String, Object> mine() {
		UUID me = currentUser.employeeId();
		Map<String, Object> out = new LinkedHashMap<>();
		List<UUID> ids = me == null ? List.of() : List.copyOf(iam.roleIdsOf(me));
		Map<UUID, String> keys = roleKeys(ids);
		if (keys.values().stream().anyMatch(ALL_ACCESS::contains)) {
			out.put("menus", MENUS.stream().map(Menu::key).toList());
			out.put("hiddenTabs", List.of());
			out.put("pages", Map.of());
			return out;
		}
		Menu conf = menu("configuration");
		Menu acc = menu("settings");
		java.util.LinkedHashSet<String> menus = new java.util.LinkedHashSet<>();
		Map<String, Integer> tabOn = new HashMap<>();
		List<Map<String, String>> pageRows = new ArrayList<>();
		List<Map<String, String>> tabRows = new ArrayList<>();
		List<boolean[]> viaMenus = new ArrayList<>();
		for (UUID id : ids) {
			Map<String, String> rows = rows(id);
			Map<String, PermLevel> levels = levelsOf(id);
			boolean owner = areaOwner(id);
			String key = keys.getOrDefault(id, "");
			for (Menu m : MENUS) {
				if (menuOn(m, key, rows, levels, owner)) {
					menus.add(m.key());
				}
			}
			if (menuOn(menu("employees"), key, rows, levels, owner)) {
				for (String[] t : PROFILE_TABS) {
					tabOn.merge(t[0], "off".equals(rows.get("tab|employees:" + t[0])) ? 0 : 1, Math::max);
				}
			}
			tabRows.add(rows);
			pageRows.add(rows);
			viaMenus.add(new boolean[] {menuOn(conf, key, rows, levels, owner), menuOn(acc, key, rows, levels, owner)});
		}
		// Owning an area personally (not through a role) opens Configuration too.
		if (!menus.contains("configuration") && me != null && jdbc.sql("""
				select count(*) from area_owner ao join app_user u on u.id = ao.user_id where u.employee_id = :e""")
				.param("e", me).query(Long.class).single() > 0) {
			menus.add("configuration");
			viaMenus.forEach(v -> v[0] = true);
		}
		// A page is as open as the most open role that reaches it through its menu; pages without a row are editable.
		Set<String> pageKeys = new java.util.TreeSet<>();
		pageRows.forEach(r -> r.keySet().stream().filter(k -> k.startsWith("page|")).forEach(k -> pageKeys.add(k.substring(5))));
		Map<String, String> pages = new LinkedHashMap<>();
		for (String p : pageKeys) {
			int best = -1;
			for (int i = 0; i < pageRows.size(); i++) {
				if (!viaMenus.get(i)[p.startsWith("configuration/") ? 0 : 1]) {
					continue;
				}
				int lv = switch (pageRows.get(i).getOrDefault("page|" + p, "edit")) {
					case "hidden" -> 0;
					case "view" -> 1;
					default -> 2;
				};
				best = Math.max(best, lv);
			}
			if (best == 0 || best == 1) {
				pages.put(p, best == 1 ? "view" : "hidden");
			}
		}
		out.put("menus", List.copyOf(menus));
		// Nobody opens the Employees menu (a colleague's profile from the directory): any role's tab switches apply.
		if (tabOn.isEmpty()) {
			for (Map<String, String> rows : tabRows) {
				for (String[] t : PROFILE_TABS) {
					tabOn.merge(t[0], "off".equals(rows.get("tab|employees:" + t[0])) ? 0 : 1, Math::max);
				}
			}
		}
		out.put("hiddenTabs", tabOn.entrySet().stream().filter(e -> e.getValue() == 0).map(Map.Entry::getKey).sorted().toList());
		out.put("pages", pages);
		return out;
	}

	@Override
	@Transactional(readOnly = true)
	@SuppressWarnings("unchecked")
	public Set<String> hiddenProfileTabs() {
		return Set.copyOf((List<String>) mine().get("hiddenTabs"));
	}

	// ------------------------------------------------------------------ helpers

	private static Menu menu(String key) {
		return MENUS.stream().filter(x -> x.key().equals(key)).findFirst().orElseThrow();
	}

	private boolean menuOn(Menu m, String roleKey, Map<String, String> rows, Map<String, PermLevel> levels, boolean owner) {
		if (m.locked() || ALL_ACCESS.contains(roleKey)) {
			return true;
		}
		String row = rows.get("menu|" + m.key());
		if (row != null) {
			return "on".equals(row);
		}
		// Everyone holds the Employee role; it opens no admin menu unless one is switched on for it.
		if (EMPLOYEE.equals(roleKey) && "admin".equals(m.portal())) {
			return false;
		}
		if (m.module() == null || PEOPLE.contains(m.key())) {
			return true;
		}
		if (CONFIG.equals(m.module())) {
			return owner;
		}
		if (ACCESS.equals(m.module())) {
			return false;
		}
		return levels.getOrDefault(m.module(), PermLevel.NONE).atLeast(PermLevel.VIEW);
	}

	private Map<String, Object> role(UUID roleId) {
		return jdbc.sql("select id, key, name from role where id = :id and org_id = :org").param("id", roleId).param("org", currentUser.orgId())
				.query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("id", rs.getObject(1, UUID.class));
					m.put("key", rs.getString(2));
					m.put("name", rs.getString(3));
					return m;
				}).optional().orElseThrow(() -> new NotFoundException("Role " + roleId + " not found"));
	}

	private Map<UUID, String> roleKeys(List<UUID> ids) {
		if (ids.isEmpty()) {
			return Map.of();
		}
		return jdbc.sql("select id, key from role where id in (:ids)").param("ids", ids)
				.query((rs, i) -> Map.entry(rs.getObject(1, UUID.class), rs.getString(2))).list().stream()
				.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}

	private Map<String, String> rows(UUID roleId) {
		Map<String, String> m = new HashMap<>();
		jdbc.sql("select area, key, value from role_nav where role_id = :r").param("r", roleId)
				.query((rs, i) -> m.put(rs.getString(1) + "|" + rs.getString(2), rs.getString(3))).list();
		return m;
	}

	private Map<String, PermLevel> levelsOf(UUID roleId) {
		Map<String, PermLevel> m = new HashMap<>();
		for (RolePermissionResponse p : roles.permissionsOf(roleId)) {
			PermLevel lv = PermLevel.ofAction(p.action());
			m.merge(p.module(), lv, (a, b) -> a.atLeast(b) ? a : b);
		}
		return m;
	}

	private boolean areaOwner(UUID roleId) {
		return jdbc.sql("select count(*) from area_owner_role where role_id = :r").param("r", roleId).query(Long.class).single() > 0;
	}
}
