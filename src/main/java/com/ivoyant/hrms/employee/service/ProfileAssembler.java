package com.ivoyant.hrms.employee.service;

import com.ivoyant.hrms.common.authz.AuthzService;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.security.ModuleAccess;
import com.ivoyant.hrms.common.web.ForbiddenException;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.common.web.StatusException;
import com.ivoyant.hrms.config.FieldAccess;
import com.ivoyant.hrms.config.FieldConfig;
import com.ivoyant.hrms.config.FieldDefinition;
import com.ivoyant.hrms.config.FieldType;
import com.ivoyant.hrms.config.FormField;
import com.ivoyant.hrms.config.FormSection;
import com.ivoyant.hrms.config.FormView;
import com.ivoyant.hrms.employee.EmployeeStatuses;
import com.ivoyant.hrms.employee.domain.Employee;
import com.ivoyant.hrms.employee.repo.EmployeeRepository;
import com.ivoyant.hrms.employee.view.CompensationView;
import com.ivoyant.hrms.iam.IamDirectory;
import com.ivoyant.hrms.iam.RoleRef;
import com.ivoyant.hrms.org.OrgDirectory;
import com.ivoyant.hrms.org.Positions;
import com.ivoyant.hrms.org.Titles;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One profile, many audiences: every field is shaped by what the caller may do with it (hidden fields absent, masked ones
 * masked, view / request / edit marked), the tabs follow what is left, and the header says which buttons to show.
 */
@Service
public class ProfileAssembler {

	static final String AREA = EmployeeFields.AREA;
	private static final List<String[]> TABS = List.of(new String[] {"overview", "Overview"}, new String[] {"personal", "Personal"},
			new String[] {"bank", "Bank & statutory"}, new String[] {"leave", "Leave"}, new String[] {"compensation", "Compensation"},
			new String[] {"documents", "Documents"}, new String[] {"other", "Additional details"});

	private final EmployeeRepository employees;
	private final FieldConfig fieldConfig;
	private final EmployeeRecords records;
	private final CurrentUser currentUser;
	private final EmployeeActions actions;
	private final ModuleAccess access;
	private final AuthzService authz;
	private final OrgDirectory org;
	private final Positions positions;
	private final Titles titles;
	private final IamDirectory iam;
	private final JdbcClient jdbc;
	private final CompensationView compensation;
	private final LeaveTab leaveTab;
	private final com.ivoyant.hrms.common.audit.Audit audit;

	ProfileAssembler(EmployeeRepository employees, FieldConfig fieldConfig, EmployeeRecords records, CurrentUser currentUser,
			EmployeeActions actions, ModuleAccess access, AuthzService authz, OrgDirectory org, Positions positions, Titles titles,
			IamDirectory iam, JdbcClient jdbc, CompensationView compensation, LeaveTab leaveTab, com.ivoyant.hrms.common.audit.Audit audit) {
		this.audit = audit;
		this.employees = employees;
		this.fieldConfig = fieldConfig;
		this.records = records;
		this.currentUser = currentUser;
		this.actions = actions;
		this.access = access;
		this.authz = authz;
		this.org = org;
		this.positions = positions;
		this.titles = titles;
		this.iam = iam;
		this.jdbc = jdbc;
		this.compensation = compensation;
		this.leaveTab = leaveTab;
	}

	static String tabOf(String sectionName) {
		return switch (sectionName.toLowerCase()) {
			case "basic details", "work details", "job" -> "overview";
			case "personal", "emergency contact" -> "personal";
			case "bank & legal", "bank account", "statutory ids" -> "bank";
			case "compensation" -> "compensation";
			case "documents", "previous employment" -> "documents";
			default -> "other";
		};
	}

	@Transactional
	public Map<String, Object> profile(UUID id, String onlyTab) {
		Employee e = employees.findByIdAndOrgId(id, currentUser.orgId()).filter(x -> x.getArchivedAt() == null)
				.orElseThrow(() -> new NotFoundException("Employee " + id + " not found"));
		if (!actions.can("view_profile", id)) {
			throw new StatusException(HttpStatus.FORBIDDEN, "NO_PROFILE_ACCESS", "You cannot open this profile");
		}
		UUID me = currentUser.requireEmployeeId();
		boolean self = me.equals(id);
		Map<String, FieldAccess> levels = fieldConfig.levels(AREA, id);
		Map<String, FieldDefinition> defs = fieldConfig.activeFields(AREA).stream().collect(Collectors.toMap(FieldDefinition::key, d -> d, (a, b) -> a));
		FormView form = fieldConfig.render(AREA, id, id);

		Map<String, List<Map<String, Object>>> byTab = new LinkedHashMap<>();
		List<Map<String, Object>> sections = new ArrayList<>();
		for (Map<String, Object> sec : builtIn(e, defs, levels)) {
			sections.add(sec);
			byTab.computeIfAbsent("overview", k -> new ArrayList<>()).add(sec);
		}
		for (FormSection s : form.sections()) {
			String tab = tabOf(s.name());
			List<Map<String, Object>> fields = new ArrayList<>();
			for (FormField f : s.fields()) {
				if (f.type() == FieldType.HEADING) {
					continue;
				}
				fields.add(field(f, defs.get(f.key())));
			}
			if (fields.isEmpty()) {
				continue;
			}
			Map<String, Object> sec = new LinkedHashMap<>();
			sec.put("key", s.name().toLowerCase().replaceAll("[^a-z0-9]+", "_"));
			sec.put("label", s.name());
			sec.put("tab", tab);
			sec.put("fields", fields);
			sections.add(sec);
			byTab.computeIfAbsent(tab, k -> new ArrayList<>()).add(sec);
		}

		FieldAccess ctcLevel = levels.getOrDefault("ctc", FieldAccess.HIDDEN);
		boolean seesLeave = self || access.has("leave", "VIEW") || authz.can("can_approve_leave", "employee:" + id);
		boolean seesComp = self && ctcLevel != FieldAccess.HIDDEN || ctcLevel != FieldAccess.HIDDEN || access.has("payroll", "VIEW");
		boolean seesDocs = self || access.has("employee", "VIEW");
		List<Map<String, String>> tabs = new ArrayList<>();
		for (String[] t : TABS) {
			boolean show = switch (t[0]) {
				case "overview" -> true;
				case "leave" -> seesLeave;
				case "compensation" -> seesComp || byTab.containsKey("compensation");
				case "documents" -> seesDocs;
				default -> byTab.containsKey(t[0]);
			};
			if (show) {
				tabs.add(Map.of("key", t[0], "label", t[1]));
			}
		}

		if (!self) {
			List<String> sensitive = new ArrayList<>();
			for (String k : List.of("acct", "aadhaar", "pan", "uan", "esicip")) {
				if (levels.getOrDefault(k, FieldAccess.HIDDEN).atLeast(FieldAccess.VIEW)) {
					sensitive.add(k);
				}
			}
			if (!sensitive.isEmpty() && (onlyTab == null || "bank".equals(onlyTab))) {
				audit.publish("employee", "Employee", id, "READ_SENSITIVE", null, sensitive);
			}
		}
		EmployeeStatuses.View status = EmployeeStatuses.of(e.getStatus().name(), e.getProbationEndDate(), e.getConfirmedOn());
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("id", e.getId());
		out.put("code", e.getEmployeeCode());
		out.put("name", e.getDisplayName());
		out.put("initials", initials(e));
		out.put("self", self);
		out.put("status", status.status());
		out.put("statusLabel", status.label());
		out.put("statusKind", status.kind());
		out.put("title", e.getTitleId() == null ? null : titles.names(List.of(e.getTitleId())).get(e.getTitleId()));
		out.put("titleDisplay", titles.display(e.getId()));
		out.put("department", ref(e.getDepartmentId(), org.departmentNames(ids(e.getDepartmentId()))));
		out.put("location", ref(e.getLocationId(), org.locationNames(ids(e.getLocationId()))));
		out.put("position", position(e.getDesignationId()));
		out.put("photoUrl", photoUrl(e));
		out.put("hasCustomPhoto", e.getPhotoKey() != null);
		out.put("joined", e.getJoiningDate());
		out.put("probationEnd", e.getProbationEndDate());
		out.put("manager", manager(e.getManagerId()));
		out.put("reports", employees.findByOrgIdAndManagerIdOrderByDisplayName(currentUser.orgId(), id).stream().filter(r -> r.getArchivedAt() == null)
				.map(r -> Map.<String, Object>of("id", r.getId(), "name", r.getDisplayName(), "title", String.valueOf(titles.display(r.getId())))).toList());
		out.put("tabs", onlyTab == null ? tabs : tabs.stream().filter(t -> t.get("key").equals(onlyTab)).toList());
		out.put("sections", onlyTab == null ? sections : sections.stream().filter(s -> onlyTab.equals(s.get("tab"))).toList());
		out.put("hiddenSections", hiddenSections(levels, defs));
		out.put("viewerRoles", iam.effectiveRoles(me, "employee").stream().map(RoleRef::name).sorted().toList());
		if (seesLeave && (onlyTab == null || "leave".equals(onlyTab))) {
			out.put("leave", leaveTab.forEmployee(id, java.time.Year.now().getValue(), self || access.has("leave", "EDIT")));
		}
		if ((seesComp || byTab.containsKey("compensation")) && (onlyTab == null || "compensation".equals(onlyTab))) {
			out.put("compensation", compensation(e, ctcLevel));
		}
		if (onlyTab == null) {
			Map<String, Object> panels = new LinkedHashMap<>();
			panel(panels, "pay", "Payroll details", "payroll", id);
			panel(panels, "perf", "Performance details", "performance", id);
			out.put("extraPanels", panels);
		}
		out.put("actions", actions.forProfile(id));
		out.put("version", e.getVersion());
		return out;
	}

	/**
	 * Basic and Work details are drawn by code (the generic forms skip them). Work values change through a job change,
	 * never through the field editor, so they are shown read-only whatever the field access says.
	 */
	private List<Map<String, Object>> builtIn(Employee e, Map<String, FieldDefinition> defs, Map<String, FieldAccess> levels) {
		Map<String, Object> values = records.read(e.getId());
		Map<UUID, String> departments = org.departmentNames(ids(e.getDepartmentId()));
		Map<UUID, String> locations = org.locationNames(ids(e.getLocationId()));
		Map<String, String> display = new LinkedHashMap<>();
		display.put("department", e.getDepartmentId() == null ? null : departments.get(e.getDepartmentId()));
		display.put("location", e.getLocationId() == null ? null : locations.get(e.getLocationId()));
		display.put("designation", e.getDesignationId() == null ? null : positions.names(List.of(e.getDesignationId())).get(e.getDesignationId()));
		display.put("title", e.getTitleId() == null ? null : titles.names(List.of(e.getTitleId())).get(e.getTitleId()));
		display.put("manager", e.getManagerId() == null ? null : employees.findById(e.getManagerId()).map(Employee::getDisplayName).orElse(null));
		display.put("status", EmployeeStatuses.of(e.getStatus().name(), e.getProbationEndDate(), e.getConfirmedOn()).label());
		List<Map<String, Object>> out = new ArrayList<>();
		for (String[] sec : new String[][] {{"basic", "Basic details"}, {"work", "Work details"}}) {
			List<Map<String, Object>> fields = new ArrayList<>();
			if ("work".equals(sec[0])) {
				Map<String, Object> id = new LinkedHashMap<>();
				id.put("key", "employee_code");
				id.put("label", "Employee ID");
				id.put("type", "TEXT");
				id.put("value", e.getEmployeeCode());
				id.put("display", e.getEmployeeCode());
				id.put("access", "VIEW");
				id.put("masked", false);
				id.put("required", false);
				id.put("pending", null);
				fields.add(id);
			}
			for (FieldDefinition d : defs.values().stream().filter(x -> sec[0].equals(x.sectionKey()) && x.type() != FieldType.HEADING).sorted(Comparator.comparingInt(FieldDefinition::sort)).toList()) {
				FieldAccess level = levels.getOrDefault(d.key(), FieldAccess.HIDDEN);
				if (level == FieldAccess.HIDDEN) {
					continue;
				}
				boolean masked = level == FieldAccess.MASKED;
				Object raw = values.get(d.key());
				String shown = display.containsKey(d.key()) ? display.get(d.key()) : raw == null ? null : raw.toString();
				boolean workKey = "work".equals(sec[0]) || "work_email".equals(d.key());
				Map<String, Object> f = new LinkedHashMap<>();
				f.put("key", d.key());
				f.put("label", d.label());
				f.put("type", d.type().name());
				f.put("value", masked ? Masking.mask(raw) : raw);
				f.put("display", masked ? Masking.mask(shown) : shown);
				f.put("access", workKey && level.atLeast(FieldAccess.VIEW) ? "VIEW" : level == FieldAccess.HR_APPROVES ? "REQUEST" : level.name());
				f.put("editVia", workKey ? "JOB_CHANGE" : null);
				f.put("masked", masked);
				f.put("required", d.required());
				f.put("pending", null);
				fields.add(f);
			}
			if (!fields.isEmpty()) {
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("key", sec[0]);
				m.put("label", sec[1]);
				m.put("tab", "overview");
				m.put("fields", fields);
				out.add(m);
			}
		}
		return out;
	}

	private void panel(Map<String, Object> panels, String key, String title, String area, UUID id) {
		try {
			FormView v = fieldConfig.render(area, id, id);
			if (!v.sections().isEmpty()) {
				panels.put(key, Map.of("title", title, "sections", v.sections()));
			}
		}
		catch (RuntimeException ex) {
			// no access to that area's record: the panel is simply not shown
		}
	}

	private Map<String, Object> field(FormField f, FieldDefinition def) {
		boolean masked = f.level() == FieldAccess.MASKED;
		boolean locked = def != null && def.source() != null && !def.source().isBlank();
		FieldAccess level = locked && f.level().atLeast(FieldAccess.VIEW) ? FieldAccess.VIEW : f.level();
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("key", f.key());
		m.put("label", f.label());
		m.put("type", f.type().name());
		m.put("value", masked ? f.display() : f.value());
		m.put("display", f.display());
		m.put("access", level == FieldAccess.HR_APPROVES ? "REQUEST" : level.name());
		m.put("masked", masked);
		m.put("required", f.required());
		m.put("helpText", f.helpText());
		m.put("options", f.options());
		m.put("source", locked ? def.source() : null);
		if (f.pending() != null) {
			m.put("pending", Map.of("id", f.pending().id(), "to", String.valueOf(f.pending().value()), "by", String.valueOf(f.pending().by()),
					"at", String.valueOf(f.pending().at()), "text", String.valueOf(f.pending().text()), "canApprove", f.pending().canDecide()));
		}
		else {
			m.put("pending", null);
		}
		return m;
	}

	/** Sections whose every field is hidden from this viewer (the banner "Hidden for your roles"). */
	private List<String> hiddenSections(Map<String, FieldAccess> levels, Map<String, FieldDefinition> defs) {
		Map<String, List<FieldDefinition>> bySection = defs.values().stream().filter(d -> d.type() != FieldType.HEADING)
				.collect(Collectors.groupingBy(FieldDefinition::sectionKey));
		List<String> out = new ArrayList<>();
		for (Map<String, Object> s : jdbc.sql("select key, name from config_section where org_id = :org and area_key = 'employee_profile' and active and not locked and deleted_at is null order by sort")
				.param("org", currentUser.orgId()).query((rs, i) -> Map.<String, Object>of("key", rs.getString(1), "name", rs.getString(2))).list()) {
			List<FieldDefinition> in = bySection.getOrDefault((String) s.get("key"), List.of());
			if (!in.isEmpty() && in.stream().allMatch(d -> levels.getOrDefault(d.key(), FieldAccess.HIDDEN) == FieldAccess.HIDDEN)) {
				out.add((String) s.get("name"));
			}
		}
		return out;
	}

	private Map<String, Object> compensation(Employee e, FieldAccess ctcLevel) {
		Map<String, Object> out = new LinkedHashMap<>();
		boolean hidden = ctcLevel == FieldAccess.HIDDEN;
		boolean masked = ctcLevel == FieldAccess.MASKED;
		Map<String, Object> accessMap = Map.of("ctc", ctcLevel.name(), "monthly", ctcLevel.name(), "payslip", access.has("payroll", "VIEW") ? "VIEW" : "HIDDEN");
		out.put("access", accessMap);
		if (hidden) {
			out.put("estimated", true);
			return out;
		}
		Object raw = e.getCustom().get("ctc");
		BigDecimal ctc = raw == null || raw.toString().isBlank() ? null : new BigDecimal(raw.toString());
		CompensationView.Breakdown b = compensation.forEmployee(e.getId(), ctc);
		out.put("regime", b.regime());
		out.put("ptState", b.ptState());
		out.put("estimated", b.estimated());
		out.put("masked", masked);
		out.put("ctcAnnual", masked ? "₹ •••" : b.ctcAnnual());
		out.put("earnings", masked ? List.of() : b.earnings());
		out.put("deductions", masked ? List.of() : b.deductions());
		out.put("netMonthly", masked ? "₹ •••" : b.netMonthly());
		return out;
	}

	private Map<String, Object> manager(UUID managerId) {
		if (managerId == null) {
			return null;
		}
		return employees.findById(managerId).map(m -> Map.<String, Object>of("id", m.getId(), "name", m.getDisplayName(), "title", String.valueOf(titles.display(m.getId())))).orElse(null);
	}

	private Map<String, Object> position(UUID id) {
		if (id == null) {
			return null;
		}
		return positions.get(id).map(p -> {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", p.id());
			m.put("name", p.name());
			m.put("grade", p.grade());
			return m;
		}).orElse(null);
	}

	static Map<String, Object> ref(UUID id, Map<UUID, String> names) {
		if (id == null) {
			return null;
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", id);
		m.put("name", names.get(id));
		m.put("color", Math.floorMod(id.hashCode(), 8));
		return m;
	}

	static String photoUrl(Employee e) {
		return "/api/v1/employees/" + e.getId() + "/photo?s=256&v=" + e.getPhotoVersion();
	}

	static String initials(Employee e) {
		String f = e.getFirstName() == null || e.getFirstName().isBlank() ? "" : e.getFirstName().substring(0, 1);
		String l = e.getLastName() == null || e.getLastName().isBlank() ? "" : e.getLastName().substring(0, 1);
		return (f + l).toUpperCase();
	}

	private static List<UUID> ids(UUID id) {
		return id == null ? List.of() : List.of(id);
	}
}
