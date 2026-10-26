package com.ivoyant.hrms.org.service;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.event.OrgSettingsChangedEvent;
import com.ivoyant.hrms.common.event.SalaryVisibilityChangedEvent;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.BadRequestException;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.InUseException;
import com.ivoyant.hrms.common.web.StaleException;
import com.ivoyant.hrms.common.web.UnprocessableException;
import com.ivoyant.hrms.org.EmployeeCodes;
import com.ivoyant.hrms.org.SalaryVisibility;
import com.ivoyant.hrms.org.domain.OrgSettingsRow;
import com.ivoyant.hrms.org.domain.SetupAckId;
import com.ivoyant.hrms.org.dto.SettingsMeta;
import com.ivoyant.hrms.org.repo.OrgSettingsRepository;
import com.ivoyant.hrms.org.repo.SetupAckRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The 22 organization settings: reading, validating, the "in use" guards, and the side effects. The registry below is
 * the single list of settings; the JSON keys are the settings UI's.
 */
@Service
public class OrgSettingsService {

	/** A value that is not allowed, with the error code the UI expects. */
	private static final class Bad extends RuntimeException {

		final String code;

		Bad(String code, String message) {
			super(message);
			this.code = code;
		}
	}

	private record Setting(String key, String group, String label, String kind, String hint, String preview,
			Object options, Function<OrgSettingsRow, Object> get, BiConsumer<OrgSettingsRow, Object> set,
			Function<Object, Object> parse) {
	}

	private static final Pattern TITLE_TOKEN = Pattern.compile("\\{[a-z]+\\}");
	private static final Set<String> TITLE_TOKENS = Set.of("{title}", "{position}", "{grade}");
	private static final List<Integer> DEPTHS = List.of(2, 3, 4, 5, 6, 8);
	private static final List<Integer> REPORTING = List.of(6, 8, 10, 12, 15);
	private static final List<Integer> SPANS = List.of(8, 10, 12, 15, 20, 25);
	private static final List<Integer> MENTEES = List.of(3, 5, 10, 15, 20);

	private static final List<Map<String, String>> SALARY_OPTIONS = List.of(
			opt("ADMINS_ONLY", "Admins only"), opt("HR_FINANCE", "HR & finance"), opt("MANAGERS_HR", "Managers & HR"),
			opt("EVERYONE", "Everyone"));
	private static final List<Map<String, String>> RETENTION_OPTIONS = List.of(opt("Y1", "1 year"), opt("Y3", "3 years"),
			opt("Y7", "7 years"), opt("Y10", "10 years"));
	private static final List<Map<String, String>> PASSWORD_OPTIONS = List.of(opt("STANDARD", "Standard · 8+ characters"),
			opt("STRONG", "Strong · 12+ with symbols"), opt("ENTRA", "Managed by Entra ID"));

	private static final List<Setting> SETTINGS = List.of(
			toggle("autoId", "employee", "Auto-generate employee IDs", "New hires get the next ID in the pattern.",
					OrgSettingsRow::isAutoId, OrgSettingsRow::setAutoId),
			text("idFmt", "employee", "Employee ID pattern", "Tokens: {PREFIX} {YYYY} {SEQ4}", "employee-id",
					OrgSettingsRow::getIdFormat, OrgSettingsRow::setIdFormat, OrgSettingsService::parseIdFormat),
			toggle("reqMgr", "employee", "Changes need manager approval",
					"Profile edits and transfers go to the reporting manager first.", OrgSettingsRow::isReqMgr,
					OrgSettingsRow::setReqMgr),
			toggle("multiTitle", "employee", "Allow more than one title per employee", null, OrgSettingsRow::isMultiTitle,
					OrgSettingsRow::setMultiTitle),
			toggle("posMand", "employee", "Position is mandatory", "Every employee must hold a position.",
					OrgSettingsRow::isPosMandatory, OrgSettingsRow::setPosMandatory),
			text("titleFmt", "employee", "Title display format", "Tokens: {title} {position} {grade}", "title-format",
					OrgSettingsRow::getTitleFormat, OrgSettingsRow::setTitleFormat, OrgSettingsService::parseTitleFormat),
			toggle("oobApproval", "employee", "Offers above a band need approval",
					"Off: an offer above the position's band only shows a warning.", OrgSettingsRow::isOobApproval,
					OrgSettingsRow::setOobApproval),
			toggle("nested", "departments", "Allow nested departments", "Departments can sit under a parent department.",
					OrgSettingsRow::isNestedDepts, OrgSettingsRow::setNestedDepts),
			choice("maxDepth", "departments", "Max nesting level", null, DEPTHS, OrgSettingsRow::getMaxDeptDepth,
					(r, v) -> r.setMaxDeptDepth((Integer) v), v -> oneOf(v, DEPTHS)),
			toggle("deptCode", "departments", "Department code required", null, OrgSettingsRow::isDeptCodeReq,
					OrgSettingsRow::setDeptCodeReq),
			choice("maxRep", "departments", "Max reporting depth",
					"Longest allowed chain from an employee up to the CEO.", REPORTING, OrgSettingsRow::getMaxRepDepth,
					(r, v) -> r.setMaxRepDepth((Integer) v), v -> oneOf(v, REPORTING)),
			choice("maxSpan", "departments", "Max direct reports",
					"More direct reports than this shows a warning on the manager.", SPANS, OrgSettingsRow::getMaxSpan,
					(r, v) -> r.setMaxSpan((Integer) v), v -> oneOf(v, SPANS)),
			choice("maxMentees", "departments", "Max mentees per mentor", null, MENTEES, OrgSettingsRow::getMaxMentees,
					(r, v) -> r.setMaxMentees((Integer) v), v -> oneOf(v, MENTEES)),
			toggle("customRoles", "roles", "Allow custom roles", null, OrgSettingsRow::isCustomRoles,
					OrgSettingsRow::setCustomRoles),
			toggle("inherit", "roles", "Allow role inheritance", "A role gets everything its parent role has.",
					OrgSettingsRow::isRoleInherit, OrgSettingsRow::setRoleInherit),
			toggle("sensApproval", "roles", "Sensitive roles need a second approver",
					"Assignments of roles marked sensitive wait for another admin.", OrgSettingsRow::isSensApproval,
					OrgSettingsRow::setSensApproval),
			toggle("auditPerm", "roles", "Log permission changes", "Recorded in Access audit log.",
					OrgSettingsRow::isAuditPerm, OrgSettingsRow::setAuditPerm),
			choice("salaryVis", "roles", "Salary visibility", "Presets the Salary & CTC row in Field security.",
					SALARY_OPTIONS, OrgSettingsRow::getSalaryVis, (r, v) -> r.setSalaryVis((String) v),
					v -> oneOfText(v, SALARY_OPTIONS)),
			toggle("orgChart", "reporting", "Org chart", null, OrgSettingsRow::isOrgChart, OrgSettingsRow::setOrgChart),
			toggle("analytics", "reporting", "Analytics dashboards", null, OrgSettingsRow::isAnalytics,
					OrgSettingsRow::setAnalytics),
			choice("retention", "reporting", "Data retention", "How long exited employee data and logs are kept.",
					RETENTION_OPTIONS, OrgSettingsRow::getRetention, (r, v) -> r.setRetention((String) v),
					v -> oneOfText(v, RETENTION_OPTIONS)),
			toggle("auditLog", "security", "Audit log", null, OrgSettingsRow::isAuditLog, OrgSettingsRow::setAuditLog),
			toggle("twoFA", "security", "Require 2-step sign-in", null, OrgSettingsRow::isTwoFa, OrgSettingsRow::setTwoFa),
			choice("pwPolicy", "security", "Password policy", null, PASSWORD_OPTIONS, OrgSettingsRow::getPwPolicy,
					(r, v) -> r.setPwPolicy((String) v), v -> oneOfText(v, PASSWORD_OPTIONS)),
			toggle("encrypt", "security", "Encrypt sensitive fields", "Salary, bank, PAN and Aadhaar are stored encrypted.",
					OrgSettingsRow::isEncrypt, OrgSettingsRow::setEncrypt));

	private static final Map<String, String> GROUPS = new LinkedHashMap<>();

	static {
		GROUPS.put("employee", "Employee management");
		GROUPS.put("departments", "Departments");
		GROUPS.put("roles", "Roles & permissions");
		GROUPS.put("reporting", "Reporting & analytics");
		GROUPS.put("security", "Security & compliance");
	}

	private final OrgSettingsRepository repo;
	private final SetupAckRepository acks;
	private final EmployeeCodes codes;
	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final Audit audit;
	private final ApplicationEventPublisher events;

	public OrgSettingsService(OrgSettingsRepository repo, SetupAckRepository acks, EmployeeCodes codes, JdbcClient jdbc,
			CurrentUser currentUser, Audit audit, ApplicationEventPublisher events) {
		this.repo = repo;
		this.acks = acks;
		this.codes = codes;
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.audit = audit;
		this.events = events;
	}

	// ================================================================== reading

	@Transactional(readOnly = true)
	public Map<String, Object> get() {
		return response(row(), List.of());
	}

	public SettingsMeta meta() {
		List<SettingsMeta.Group> groups = new ArrayList<>();
		GROUPS.forEach((key, label) -> groups.add(new SettingsMeta.Group(key, label, SETTINGS.stream()
				.filter(s -> s.group().equals(key))
				.map(s -> new SettingsMeta.Row(s.key(), s.label(), s.kind(), s.hint(), s.options(), s.preview())).toList())));
		return new SettingsMeta(groups);
	}

	/** "Next ID: IVY-1031" while the admin types a pattern. */
	public Map<String, String> employeeIdPreview(String pattern) {
		try {
			String next = codes.preview(pattern);
			return Map.of("example", next, "text", "Next ID: " + next);
		}
		catch (IllegalArgumentException ex) {
			throw new UnprocessableException(List.of(ErrorItem.field("idFmt", "ID_FORMAT", ex.getMessage())));
		}
	}

	public Map<String, String> titleFormatPreview(String pattern) {
		try {
			parseTitleFormat(pattern);
		}
		catch (Bad ex) {
			throw new UnprocessableException(List.of(ErrorItem.field("titleFmt", ex.code, ex.getMessage())));
		}
		String example = titleExample(pattern);
		return Map.of("example", example, "text", "Shows as: " + example);
	}

	// ================================================================== writing

	@Transactional
	public Map<String, Object> patch(Map<String, Object> patch, boolean force, String ifMatch) {
		OrgSettingsRow row = row();
		if (ifMatch != null && !ifMatch.isBlank()
				&& !ifMatch.replace("W/", "").replace("\"", "").trim().equals(String.valueOf(row.getVersion()))) {
			throw new StaleException("Someone else changed the organization settings. Reload and try again.");
		}
		Map<String, Object> before = values(row);
		List<ErrorItem> errors = new ArrayList<>();
		for (Map.Entry<String, Object> e : patch.entrySet()) {
			Setting s = SETTINGS.stream().filter(x -> x.key().equals(e.getKey())).findFirst().orElse(null);
			if (s == null) {
				if (isEcho(e.getKey())) {
					continue; // fields the UI sends back from GET
				}
				throw new BadRequestException("Unknown setting '" + e.getKey() + "'");
			}
			try {
				s.set().accept(row, s.parse().apply(e.getValue()));
			}
			catch (Bad ex) {
				errors.add(ErrorItem.field(s.key(), ex.code, ex.getMessage()));
			}
			catch (IllegalArgumentException | ClassCastException ex) {
				errors.add(ErrorItem.field(s.key(), "INVALID", "'" + s.label() + "' has an invalid value"));
			}
		}
		if (!errors.isEmpty()) {
			throw new UnprocessableException(errors);
		}
		Map<String, Object> after = values(row);
		List<String> changed = after.keySet().stream().filter(k -> !Objects.equals(before.get(k), after.get(k))).toList();
		List<String> warnings = new ArrayList<>();
		guard(row, changed, force, warnings);
		if (changed.isEmpty()) {
			return response(row, warnings);
		}
		row.setUpdatedAt(Instant.now());
		row.setUpdatedBy(currentUser.employeeId());
		repo.saveAndFlush(row);
		for (String key : changed) {
			Setting s = SETTINGS.stream().filter(x -> x.key().equals(key)).findFirst().orElseThrow();
			audit.publish("org", "OrgSetting", key, "UPDATE", Map.of(s.label(), describe(before.get(key))),
					Map.of(s.label(), describe(after.get(key))));
		}
		events.publishEvent(new OrgSettingsChangedEvent(changed));
		if (changed.contains("salaryVis")) {
			events.publishEvent(new SalaryVisibilityChangedEvent((String) after.get("salaryVis")));
		}
		return response(row, warnings);
	}

	// ---- guards: a setting cannot contradict data that already exists ----

	private void guard(OrgSettingsRow row, List<String> changed, boolean force, List<String> warnings) {
		UUID org = currentUser.orgId();
		if (changed.contains("pwPolicy") && !"ENTRA".equals(row.getPwPolicy())) {
			throw new UnprocessableException(List.of(ErrorItem.field("pwPolicy", "NOT_SUPPORTED",
					"Passwords are managed by Entra ID until local sign-in exists")));
		}
		if (changed.contains("nested") && !row.isNestedDepts()) {
			List<String> nested = names("select name from department where org_id = :org and parent_id is not null order by name", org);
			if (!nested.isEmpty()) {
				throw new InUseException("Some departments sit under a parent. Move them to the top level first.",
						Map.of("departments", nested));
			}
		}
		if ((changed.contains("maxDepth") || changed.contains("nested")) && row.isNestedDepts()) {
			int limit = row.getMaxDeptDepth();
			List<String> over = names("""
					with recursive d as (
					    select id, name, 1 as lvl from department where org_id = :org and parent_id is null
					    union all
					    select c.id, c.name, d.lvl + 1 from department c join d on c.parent_id = d.id)
					select name from d where lvl > %d order by name""".formatted(limit), org);
			if (!over.isEmpty()) {
				Long deepest = jdbc.sql("""
						with recursive d as (
						    select id, 1 as lvl from department where org_id = :org and parent_id is null
						    union all
						    select c.id, d.lvl + 1 from department c join d on c.parent_id = d.id)
						select max(lvl) from d""").param("org", org).query(Long.class).single();
				throw new InUseException("Some departments are nested deeper than " + limit + " levels.",
						Map.of("deepest", deepest, "departments", over));
			}
		}
		if (changed.contains("deptCode") && row.isDeptCodeReq()) {
			List<String> missing = names("select name from department where org_id = :org and trim(code) = '' order by name", org);
			if (!missing.isEmpty()) {
				throw new InUseException("Some departments have no code.", Map.of("departments", missing));
			}
		}
		if (changed.contains("maxRep")) {
			int limit = row.getMaxRepDepth();
			String chain = """
					with recursive c as (
					    select id, display_name, 0 as depth from employee where org_id = :org and manager_id is null and status <> 'EXITED'
					    union all
					    select e.id, e.display_name, c.depth + 1 from employee e join c on e.manager_id = c.id where e.status <> 'EXITED')
					""";
			List<String> over = names(chain + "select display_name from c where depth > " + limit + " order by display_name", org);
			if (!over.isEmpty()) {
				Long longest = jdbc.sql(chain + "select max(depth) from c").param("org", org).query(Long.class).single();
				throw new InUseException("Some reporting chains are longer than " + limit + ".",
						Map.of("longest", longest, "employees", over));
			}
		}
		if (changed.contains("maxRep")) {
			Long ladder = jdbc.sql("""
					with recursive down as (
					    select id, 0 as steps from designation where org_id = :org and reports_to_id is null and active
					    union all
					    select c.id, down.steps + 1 from designation c join down on c.reports_to_id = down.id)
					select max(steps) from down""").param("org", org).query(Long.class).optional().orElse(0L);
			if (ladder != null && ladder > row.getMaxRepDepth()) {
				throw new InUseException("The position ladder is " + ladder + " steps deep.",
						Map.of("ladderDepth", ladder));
			}
		}
		if (changed.contains("multiTitle") && !row.isMultiTitle()) {
			Long people = jdbc.sql("select count(distinct employee_id) from employee_extra_title where org_id = :org")
					.param("org", org).query(Long.class).single();
			if (people != null && people > 0) {
				throw new InUseException(people + (people == 1 ? " person has" : " people have")
						+ " more than one title. Remove the extra titles first.", Map.of("count", people));
			}
		}
		if (changed.contains("posMand") && row.isPosMandatory()) {
			long without = jdbc.sql("select count(*) from employee where org_id = :org and status <> 'EXITED' and designation_id is null")
					.param("org", org).query(Long.class).single();
			if (without > 0) {
				if (!force) {
					throw new InUseException(without + (without == 1 ? " employee has" : " employees have")
							+ " no position. Fix them first, or repeat with force=true.", Map.of("count", without));
				}
				warnings.add(without + " employee(s) still have no position");
			}
		}
	}

	private List<String> names(String sql, UUID org) {
		return jdbc.sql(sql).param("org", org).query(String.class).list();
	}

	// ================================================================== helpers

	private OrgSettingsRow row() {
		UUID org = currentUser.orgId();
		return repo.findById(org).orElseGet(() -> {
			OrgSettingsRow defaults = new OrgSettingsRow();
			defaults.setOrgId(org);
			return repo.saveAndFlush(defaults);
		});
	}

	private Map<String, Object> values(OrgSettingsRow row) {
		Map<String, Object> m = new LinkedHashMap<>();
		SETTINGS.forEach(s -> m.put(s.key(), s.get().apply(row)));
		return m;
	}

	private Map<String, Object> response(OrgSettingsRow row, List<String> warnings) {
		Map<String, Object> out = values(row);
		Map<String, Object> preview = new LinkedHashMap<>();
		try {
			preview.put("nextEmployeeId", codes.preview(row.getIdFormat()));
		}
		catch (IllegalArgumentException ex) {
			preview.put("nextEmployeeId", null);
		}
		preview.put("titleExample", titleExample(row.getTitleFormat()));
		out.put("preview", preview);
		out.put("reviewed", acks.existsById(new SetupAckId(row.getOrgId(), "org_cfg")));
		out.put("version", row.getVersion());
		if (!warnings.isEmpty()) {
			out.put("warnings", warnings);
		}
		return out;
	}

	private static boolean isEcho(String key) {
		return Set.of("preview", "reviewed", "version", "warnings").contains(key);
	}

	/** "On"/"Off" for toggles, otherwise the value (the audit trail reads "old → new"). */
	private static String describe(Object value) {
		if (value instanceof Boolean b) {
			return b ? "On" : "Off";
		}
		return String.valueOf(value);
	}

	static String titleExample(String pattern) {
		return pattern.replace("{title}", "Senior Software Engineer").replace("{position}", "Senior Associate")
				.replace("{grade}", "L4");
	}

	// ---- registry builders and parsers ----

	private static Map<String, String> opt(String value, String label) {
		return Map.of("value", value, "label", label);
	}

	private static Setting toggle(String key, String group, String label, String hint,
			Function<OrgSettingsRow, Boolean> get, BiConsumer<OrgSettingsRow, Boolean> set) {
		return new Setting(key, group, label, "toggle", hint, null, null, r -> get.apply(r),
				(r, v) -> set.accept(r, (Boolean) v), OrgSettingsService::parseBoolean);
	}

	private static Setting text(String key, String group, String label, String hint, String preview,
			Function<OrgSettingsRow, String> get, BiConsumer<OrgSettingsRow, String> set,
			Function<Object, Object> parse) {
		return new Setting(key, group, label, "text", hint, preview, null, r -> get.apply(r),
				(r, v) -> set.accept(r, (String) v), parse);
	}

	private static Setting choice(String key, String group, String label, String hint, Object options,
			Function<OrgSettingsRow, Object> get, BiConsumer<OrgSettingsRow, Object> set,
			Function<Object, Object> parse) {
		return new Setting(key, group, label, "select", hint, null, options, get, set, parse);
	}

	private static Boolean parseBoolean(Object v) {
		if (v instanceof Boolean b) {
			return b;
		}
		if (v != null && ("true".equalsIgnoreCase(v.toString()) || "false".equalsIgnoreCase(v.toString()))) {
			return Boolean.parseBoolean(v.toString());
		}
		throw new IllegalArgumentException("must be on or off");
	}

	private static Integer oneOf(Object v, List<Integer> allowed) {
		int n = v instanceof Number num ? num.intValue() : Integer.parseInt(String.valueOf(v));
		if (!allowed.contains(n)) {
			throw new Bad("INVALID", "must be one of " + allowed);
		}
		return n;
	}

	private static String oneOfText(Object v, List<Map<String, String>> options) {
		String s = v == null ? "" : v.toString().trim().toUpperCase().replace(' ', '_');
		if (options.stream().noneMatch(o -> o.get("value").equals(s))) {
			throw new Bad("INVALID", "must be one of " + options.stream().map(o -> o.get("value")).toList());
		}
		return s;
	}

	private static String parseIdFormat(Object v) {
		String pattern = v == null ? "" : v.toString().trim();
		String problem = EmployeeCodeService.validate(pattern);
		if (problem != null) {
			throw new Bad("ID_FORMAT", problem);
		}
		return pattern;
	}

	private static String parseTitleFormat(Object v) {
		String pattern = v == null ? "" : v.toString().trim();
		if (!pattern.contains("{title}")) {
			throw new Bad("TITLE_FORMAT", "The format needs {title}");
		}
		if (pattern.length() > 80) {
			throw new Bad("TITLE_FORMAT", "The format can be at most 80 characters");
		}
		Matcher m = TITLE_TOKEN.matcher(pattern);
		while (m.find()) {
			if (!TITLE_TOKENS.contains(m.group())) {
				throw new Bad("TITLE_FORMAT", "Unknown token " + m.group() + ". Use {title}, {position} and {grade}");
			}
		}
		return pattern;
	}
}
