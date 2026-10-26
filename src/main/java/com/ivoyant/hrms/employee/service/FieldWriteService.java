package com.ivoyant.hrms.employee.service;

import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.CodedConflictException;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.common.web.StatusException;
import com.ivoyant.hrms.config.FieldAccess;
import com.ivoyant.hrms.config.FieldConfig;
import com.ivoyant.hrms.config.FieldDefinition;
import com.ivoyant.hrms.config.WriteResult;
import com.ivoyant.hrms.employee.domain.Employee;
import com.ivoyant.hrms.employee.repo.EmployeeRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Profile edits by field: what the caller may edit is written, what needs approval becomes a request, the rest is refused with a reason. */
@Service
public class FieldWriteService {

	private static final Set<String> WORK_KEYS = Set.of("department", "designation", "title", "manager", "location", "employment_type", "status");
	private static final Set<String> SYSTEM_KEYS = Set.of("employee_code", "work_email", "joining_date", "exit_date");

	private final EmployeeRepository employees;
	private final FieldConfig fieldConfig;
	private final CurrentUser currentUser;
	private final EmployeeActions actions;
	private final JdbcClient jdbc;

	FieldWriteService(EmployeeRepository employees, FieldConfig fieldConfig, CurrentUser currentUser, EmployeeActions actions, JdbcClient jdbc) {
		this.employees = employees;
		this.fieldConfig = fieldConfig;
		this.currentUser = currentUser;
		this.actions = actions;
		this.jdbc = jdbc;
	}

	@Transactional
	public Map<String, Object> patch(UUID id, Map<String, Object> values, String ifMatch) {
		Employee e = employees.findByIdAndOrgId(id, currentUser.orgId()).filter(x -> x.getArchivedAt() == null)
				.orElseThrow(() -> new NotFoundException("Employee " + id + " not found"));
		actions.require("view_profile", id);
		if (ifMatch != null && !ifMatch.isBlank() && !ifMatch.replace("W/", "").replace("\"", "").trim().equals(String.valueOf(e.getVersion()))) {
			throw new CodedConflictException("VERSION_CONFLICT", "This profile was changed by someone else. Reload and try again.");
		}
		Map<String, FieldAccess> levels = fieldConfig.levels(EmployeeFields.AREA, id);
		Map<String, FieldDefinition> defs = fieldConfig.activeFields(EmployeeFields.AREA).stream().collect(Collectors.toMap(FieldDefinition::key, Function.identity(), (a, b) -> a));
		Map<String, Object> send = new LinkedHashMap<>();
		List<Map<String, Object>> rejected = new ArrayList<>();
		for (Map.Entry<String, Object> en : values.entrySet()) {
			String key = en.getKey();
			FieldDefinition def = defs.get(key);
			String code = null;
			if (def == null) {
				code = "UNKNOWN_FIELD";
			}
			else if (WORK_KEYS.contains(key)) {
				code = "USE_JOB_CHANGE";
			}
			else if (SYSTEM_KEYS.contains(key)) {
				code = "SYSTEM_FIELD";
			}
			else if (def.source() != null && !def.source().isBlank()) {
				code = "SOURCE_LOCKED";
			}
			else {
				FieldAccess level = levels.getOrDefault(key, FieldAccess.HIDDEN);
				if (level != FieldAccess.EDIT && level != FieldAccess.HR_APPROVES) {
					code = "FIELD_READ_ONLY";
				}
				else if (level == FieldAccess.HR_APPROVES && pendingId(id, key) != null) {
					code = "PENDING_REQUEST_EXISTS";
				}
			}
			if (code != null) {
				rejected.add(Map.of("key", key, "code", code));
			}
			else {
				send.put(key, en.getValue());
			}
		}
		List<String> applied = List.of();
		List<Map<String, Object>> requested = new ArrayList<>();
		if (!send.isEmpty()) {
			WriteResult r = fieldConfig.write(EmployeeFields.AREA, id, id, send, false);
			applied = r.updated();
			for (String key : r.pendingApproval()) {
				requested.add(Map.of("key", key, "requestId", String.valueOf(pendingId(id, key))));
			}
		}
		if (applied.isEmpty() && requested.isEmpty() && !rejected.isEmpty()) {
			String first = (String) rejected.get(0).get("code");
			HttpStatus status = switch (first) {
				case "FIELD_READ_ONLY" -> HttpStatus.FORBIDDEN;
				case "PENDING_REQUEST_EXISTS" -> HttpStatus.CONFLICT;
				default -> HttpStatus.UNPROCESSABLE_ENTITY;
			};
			throw new StatusException(status, first, message(first), Map.of("rejected", rejected));
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("applied", applied);
		out.put("requested", requested);
		out.put("rejected", rejected);
		out.put("version", employees.findById(id).map(Employee::getVersion).orElse(null));
		return out;
	}

	private UUID pendingId(UUID employeeId, String key) {
		return jdbc.sql("""
				select r.id from config_change_request r join config_field f on f.id = r.field_id
				where r.org_id = :org and r.area_key = 'employee_profile' and r.record_id = :id and r.status = 'PENDING' and f.key = :k
				order by r.requested_at desc limit 1""").param("org", currentUser.orgId()).param("id", employeeId).param("k", key).query(UUID.class).optional().orElse(null);
	}

	private static String message(String code) {
		return switch (code) {
			case "FIELD_READ_ONLY" -> "You cannot change that field";
			case "PENDING_REQUEST_EXISTS" -> "A change to that field is already waiting for approval";
			case "USE_JOB_CHANGE" -> "Department, position, title, location, manager and type change through a job change";
			case "SYSTEM_FIELD" -> "That value is set by the system";
			case "SOURCE_LOCKED" -> "That value comes from payroll and cannot be edited here";
			default -> "That field does not exist";
		};
	}
}
