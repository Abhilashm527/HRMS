package com.ivoyant.hrms.employee.service;

import com.ivoyant.hrms.common.util.Csv;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.UnprocessableException;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.config.FieldConfig;
import com.ivoyant.hrms.config.FieldDefinition;
import com.ivoyant.hrms.employee.dto.CreateEmployeeRequest;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Bulk creation from a CSV (data migration): a dry run lists every problem, the real run creates everyone or no one. */
@Service
public class ImportService {

	static final int MAX_ROWS = 5000;
	private static final Pattern REF = Pattern.compile("^ref:row-(\\d+)$", Pattern.CASE_INSENSITIVE);
	private static final Set<String> KNOWN = Set.of("first_name", "last_name", "personal_email", "work_email", "phone", "department_code", "position_code", "title",
			"location_code", "manager_employee_id", "employment_type_code", "doj", "status", "probation_months", "contract_end");

	private final JdbcClient jdbc;
	private final EmployeeService employees;
	private final EmployeeActions actions;
	private final CurrentUser currentUser;
	private final FieldConfig fieldConfig;
	private final EmployeeRecords records;
	private final com.ivoyant.hrms.org.OrgSettings settings;

	ImportService(JdbcClient jdbc, EmployeeService employees, EmployeeActions actions, CurrentUser currentUser, FieldConfig fieldConfig, EmployeeRecords records,
			com.ivoyant.hrms.org.OrgSettings settings) {
		this.settings = settings;
		this.jdbc = jdbc;
		this.employees = employees;
		this.actions = actions;
		this.currentUser = currentUser;
		this.fieldConfig = fieldConfig;
		this.records = records;
	}

	private record Row(int number, Map<String, String> v) {
	}

	@Transactional
	public Map<String, Object> run(String csv, boolean dryRun) {
		actions.require("import_employees", null);
		List<List<String>> table = Csv.parse(csv);
		if (table.size() < 2) {
			throw new UnprocessableException(List.of(ErrorItem.field(null, "EMPTY", "The file has no rows")));
		}
		List<String> header = table.get(0).stream().map(h -> h.trim().toLowerCase()).toList();
		if (table.size() - 1 > MAX_ROWS) {
			throw new UnprocessableException(List.of(ErrorItem.field(null, "TOO_MANY_ROWS", "At most " + MAX_ROWS + " rows per file")));
		}
		Map<String, FieldDefinition> custom = fieldConfig.activeFields(EmployeeFields.AREA).stream().filter(d -> !EmployeeFields.isColumn(d.key()) && d.type().hasValue())
				.collect(Collectors.toMap(FieldDefinition::key, d -> d, (a, b) -> a));
		List<Map<String, Object>> errors = new ArrayList<>();
		for (String h : header) {
			if (!KNOWN.contains(h) && !custom.containsKey(h)) {
				errors.add(err(1, h, "UNKNOWN_COLUMN", "'" + h + "' is not a column"));
			}
		}
		List<Row> rows = new ArrayList<>();
		for (int i = 1; i < table.size(); i++) {
			Map<String, String> v = new HashMap<>();
			for (int c = 0; c < header.size() && c < table.get(i).size(); c++) {
				String cell = table.get(i).get(c).trim();
				if (!cell.isEmpty()) {
					v.put(header.get(c), cell);
				}
			}
			rows.add(new Row(i, v));
		}
		Lookups lk = new Lookups();
		Map<Integer, CreateEmployeeRequest> requests = new LinkedHashMap<>();
		Map<Integer, Integer> managerRef = new HashMap<>();
		Set<String> emails = new HashSet<>();
		for (Row r : rows) {
			CreateEmployeeRequest req = build(r, lk, custom, errors, emails, managerRef);
			if (req != null) {
				requests.put(r.number(), req);
			}
		}
		detectCycles(managerRef, errors);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("rows", rows.size());
		out.put("errors", errors);
		out.put("valid", errors.isEmpty() ? rows.size() : rows.size() - errors.stream().map(e -> e.get("row")).distinct().count());
		out.put("dryRun", dryRun);
		if (dryRun || !errors.isEmpty()) {
			if (!dryRun) {
				throw new UnprocessableException(errors.stream().map(e -> ErrorItem.field((String) e.get("column"), (String) e.get("code"), "Row " + e.get("row") + ": " + e.get("message"))).toList());
			}
			return out;
		}
		// create everyone a manager points to before the person (rows referring to rows in this file)
		Map<Integer, UUID> created = new HashMap<>();
		List<Integer> order = new ArrayList<>();
		Set<Integer> placed = new HashSet<>();
		while (order.size() < rows.size()) {
			boolean progress = false;
			for (Row r : rows) {
				Integer ref = managerRef.get(r.number());
				if (!placed.contains(r.number()) && (ref == null || placed.contains(ref))) {
					order.add(r.number());
					placed.add(r.number());
					progress = true;
				}
			}
			if (!progress) {
				break;
			}
		}
		List<UUID> ids = new ArrayList<>();
		for (int number : order) {
			CreateEmployeeRequest req = requests.get(number);
			Integer ref = managerRef.get(number);
			if (ref != null) {
				req = new CreateEmployeeRequest(req.employeeCode(), req.firstName(), req.lastName(), req.workEmail(), req.personalEmail(), req.phone(), req.departmentId(),
						req.designationId(), req.titleId(), req.locationId(), created.get(ref), req.employmentType(), req.joiningDate(), req.contractEndDate(), req.custom());
			}
			try {
				UUID id = employees.create(req).id();
				created.put(number, id);
				ids.add(id);
				String probation = rows.get(number - 1).v().get("probation_months");
				if (probation != null) {
					jdbc.sql("update employee set probation_end_date = :d where id = :id").param("d", req.joiningDate().plusMonths(Integer.parseInt(probation))).param("id", id).update();
				}
			}
			catch (RuntimeException ex) {
				throw new UnprocessableException(List.of(ErrorItem.field(null, "ROW_FAILED", "Row " + number + ": " + ex.getMessage())));
			}
		}
		out.put("created", ids.size());
		out.put("ids", ids);
		return out;
	}

	private CreateEmployeeRequest build(Row r, Lookups lk, Map<String, FieldDefinition> custom, List<Map<String, Object>> errors, Set<String> emails, Map<Integer, Integer> managerRef) {
		int before = errors.size();
		Map<String, String> v = r.v();
		for (String req : List.of("first_name", "last_name", "work_email", "employment_type_code", "doj")) {
			if (!v.containsKey(req)) {
				errors.add(err(r.number(), req, "REQUIRED", req + " is required"));
			}
		}
		String email = v.get("work_email");
		if (email != null) {
			if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
				errors.add(err(r.number(), "work_email", "INVALID_FORMAT", "Not a valid email"));
			}
			else if (!emails.add(email.toLowerCase())) {
				errors.add(err(r.number(), "work_email", "DUPLICATE", "The same work email appears twice in the file"));
			}
			else if (jdbc.sql("select count(*) from employee where lower(work_email) = lower(:e)").param("e", email).query(Long.class).single() > 0) {
				errors.add(err(r.number(), "work_email", "DUPLICATE", "That work email is already in use"));
			}
		}
		LocalDate doj = date(v.get("doj"), r.number(), "doj", errors);
		LocalDate cend = date(v.get("contract_end"), r.number(), "contract_end", errors);
		UUID dept = lookup(lk.departments(), v.get("department_code"), r.number(), "department_code", errors);
		UUID loc = lookup(lk.locations(), v.get("location_code"), r.number(), "location_code", errors);
		UUID position = lookup(lk.positions(), v.get("position_code"), r.number(), "position_code", errors);
		UUID type = lookup(lk.types(), v.get("employment_type_code"), r.number(), "employment_type_code", errors);
		UUID title = null;
		if (v.get("title") != null) {
			title = lk.titles().get(v.get("title").toLowerCase());
			if (title == null) {
				errors.add(err(r.number(), "title", "NOT_FOUND", "'" + v.get("title") + "' is not a job title"));
			}
		}
		if (position == null && title == null && v.get("position_code") == null && v.get("title") == null && settings.positionMandatory()) {
			errors.add(err(r.number(), "position_code", "REQUIRED", "A position or a job title is required"));
		}
		UUID manager = null;
		String m = v.get("manager_employee_id");
		if (m != null) {
			Matcher ref = REF.matcher(m);
			if (ref.matches()) {
				int target = Integer.parseInt(ref.group(1));
				if (target == r.number() || target < 1) {
					errors.add(err(r.number(), "manager_employee_id", "MANAGER_CYCLE", "A row cannot refer to itself or to a row that does not exist"));
				}
				else {
					managerRef.put(r.number(), target);
				}
			}
			else {
				manager = lk.employees().get(m.toLowerCase());
				if (manager == null) {
					errors.add(err(r.number(), "manager_employee_id", "NOT_FOUND", "No employee with ID '" + m + "'"));
				}
			}
		}
		String probation = v.get("probation_months");
		if (probation != null && !probation.matches("\\d{1,2}")) {
			errors.add(err(r.number(), "probation_months", "INVALID_FORMAT", "Probation is a number of months"));
		}
		Map<String, Object> extra = new LinkedHashMap<>();
		for (Map.Entry<String, String> e : v.entrySet()) {
			if (custom.containsKey(e.getKey())) {
				extra.put(e.getKey(), e.getValue());
			}
		}
		if (errors.size() > before || doj == null || type == null) {
			return null;
		}
		return new CreateEmployeeRequest(null, v.get("first_name"), v.get("last_name"), v.get("work_email"), v.get("personal_email"), v.get("phone"), dept, position, title, loc,
				manager, type.toString(), doj, cend, extra);
	}

	private static void detectCycles(Map<Integer, Integer> ref, List<Map<String, Object>> errors) {
		for (Integer start : ref.keySet()) {
			Set<Integer> seen = new HashSet<>();
			Integer cur = start;
			while (cur != null && seen.add(cur)) {
				cur = ref.get(cur);
			}
			if (cur != null && cur.equals(start)) {
				errors.add(err(start, "manager_employee_id", "MANAGER_CYCLE", "Managers in the file form a loop"));
			}
		}
	}

	private static UUID lookup(Map<String, UUID> map, String code, int row, String column, List<Map<String, Object>> errors) {
		if (code == null) {
			return null;
		}
		UUID id = map.get(code.toLowerCase());
		if (id == null) {
			errors.add(err(row, column, "NOT_FOUND", "'" + code + "' was not found"));
		}
		return id;
	}

	private static LocalDate date(String s, int row, String column, List<Map<String, Object>> errors) {
		if (s == null) {
			return null;
		}
		try {
			return LocalDate.parse(s);
		}
		catch (DateTimeParseException ex) {
			errors.add(err(row, column, "INVALID_FORMAT", "Use the date format yyyy-mm-dd"));
			return null;
		}
	}

	private static Map<String, Object> err(int row, String column, String code, String message) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("row", row);
		m.put("column", column);
		m.put("code", code);
		m.put("message", message);
		return m;
	}

	/** Code/name → id for the things a row can refer to (loaded once per import). */
	private final class Lookups {

		private Map<String, UUID> departments;
		private Map<String, UUID> locations;
		private Map<String, UUID> positions;
		private Map<String, UUID> types;
		private Map<String, UUID> titles;
		private Map<String, UUID> employees;

		private Map<String, UUID> load(String sql) {
			Map<String, UUID> m = new HashMap<>();
			jdbc.sql(sql).param("org", currentUser.orgId()).query((rs, i) -> {
				UUID id = rs.getObject(1, UUID.class);
				for (int c = 2; c <= rs.getMetaData().getColumnCount(); c++) {
					if (rs.getString(c) != null) {
						m.putIfAbsent(rs.getString(c).toLowerCase(), id);
					}
				}
				return 0;
			}).list();
			return m;
		}

		Map<String, UUID> departments() {
			return departments != null ? departments : (departments = load("select id, code, name from department where org_id = :org and active"));
		}

		Map<String, UUID> locations() {
			return locations != null ? locations : (locations = load("select id, code, name from location where org_id = :org and active"));
		}

		Map<String, UUID> positions() {
			return positions != null ? positions : (positions = load("select id, code, title from designation where org_id = :org and active"));
		}

		Map<String, UUID> types() {
			return types != null ? types : (types = load("select id, code, name from employment_type where org_id = :org and active"));
		}

		Map<String, UUID> titles() {
			return titles != null ? titles : (titles = load("select id, name from job_title where org_id = :org and active"));
		}

		Map<String, UUID> employees() {
			return employees != null ? employees : (employees = load("select id, employee_code from employee where org_id = :org"));
		}
	}
}
