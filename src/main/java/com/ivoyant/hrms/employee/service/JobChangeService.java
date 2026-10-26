package com.ivoyant.hrms.employee.service;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.event.EmployeeJobChangedEvent;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.CodedConflictException;
import com.ivoyant.hrms.common.web.ConflictException;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.common.web.UnprocessableException;
import com.ivoyant.hrms.employee.domain.Employee;
import com.ivoyant.hrms.employee.repo.EmployeeRepository;
import com.ivoyant.hrms.org.OrgProfile;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Department, position, title, location, manager and employment type change here, effective from a date: today's changes
 * apply at once through the same rules as any profile edit, future ones are stored and applied by {@link #applyDue}.
 */
@Service
public class JobChangeService {

	private static final Logger log = LoggerFactory.getLogger(JobChangeService.class);
	private static final Set<String> KINDS = Set.of("PROMOTION", "TRANSFER", "REDESIGNATION", "MANAGER_CHANGE", "TYPE_CHANGE", "CORRECTION");

	private final EmployeeRepository employees;
	private final EmployeeRecords records;
	private final EmployeeActions actions;
	private final JobHistory history;
	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final OrgProfile orgProfile;
	private final Audit audit;
	private final ApplicationEventPublisher events;

	JobChangeService(EmployeeRepository employees, EmployeeRecords records, EmployeeActions actions, JobHistory history, JdbcClient jdbc,
			CurrentUser currentUser, OrgProfile orgProfile, Audit audit, ApplicationEventPublisher events) {
		this.employees = employees;
		this.records = records;
		this.actions = actions;
		this.history = history;
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.orgProfile = orgProfile;
		this.audit = audit;
		this.events = events;
	}

	private Employee find(UUID id) {
		return employees.findByIdAndOrgId(id, currentUser.orgId()).filter(x -> x.getArchivedAt() == null)
				.orElseThrow(() -> new NotFoundException("Employee " + id + " not found"));
	}

	private LocalDate today() {
		return LocalDate.now(orgProfile.zone());
	}

	@Transactional
	public Map<String, Object> create(UUID id, Map<String, Object> body) {
		Employee e = find(id);
		actions.require("change_job", id);
		Map<String, Object> changes = new LinkedHashMap<>();
		put(changes, "department", uuid(body, "departmentId"));
		put(changes, "position", uuid(body, "positionId"));
		put(changes, "titleId", uuid(body, "titleId"));
		put(changes, "location", uuid(body, "locationId"));
		put(changes, "manager", uuid(body, "managerId"));
		put(changes, "type", uuid(body, "employmentTypeId"));
		String titleText = body.get("title") == null || body.get("title").toString().isBlank() ? null : body.get("title").toString().trim();
		if (titleText != null) {
			changes.put("titleText", titleText);
		}
		// drop what is already the case
		drop(changes, "department", e.getDepartmentId());
		drop(changes, "position", e.getDesignationId());
		drop(changes, "titleId", e.getTitleId());
		drop(changes, "location", e.getLocationId());
		drop(changes, "manager", e.getManagerId());
		drop(changes, "type", e.getEmploymentTypeId());
		if (changes.isEmpty()) {
			throw new UnprocessableException(List.of(ErrorItem.field(null, "NO_CHANGE", "Nothing would change")));
		}
		if (changes.containsKey("manager")) {
			actions.require("change_manager", id);
			if (id.equals(changes.get("manager"))) {
				throw new UnprocessableException(List.of(ErrorItem.field("managerId", "SELF_MANAGER", "A person cannot report to themselves")));
			}
		}
		if (changes.containsKey("type")) {
			actions.require("change_status", id);
		}
		String kind = kind(body.get("kind"), changes);
		if ("CORRECTION".equals(kind)) {
			actions.require("delete_employee", id);
		}
		LocalDate effective = body.get("effectiveFrom") == null ? today() : LocalDate.parse(body.get("effectiveFrom").toString());
		if (effective.isBefore(today().minusDays(90))) {
			throw new UnprocessableException(List.of(ErrorItem.field("effectiveFrom", "INVALID", "A change cannot start more than 90 days ago")));
		}
		String reason = body.get("reason") == null ? null : body.get("reason").toString().trim();
		Map<String, Object> out = new LinkedHashMap<>();
		if (effective.isAfter(today())) {
			UUID hid = history.add(id, kind, effective, false, changes, reason);
			audit.publish("employee", "Employee", id, "JOB_CHANGE_SCHEDULED", null, changes);
			out.put("id", hid);
			out.put("state", "SCHEDULED");
			out.put("effectiveFrom", effective);
			return out;
		}
		apply(e, changes, kind, effective, reason);
		if (body.get("reassignReportsTo") != null) {
			UUID to = UUID.fromString(body.get("reassignReportsTo").toString());
			for (Employee r : employees.findByManagerId(id)) {
				if (r.getStatus() != com.ivoyant.hrms.employee.domain.EmployeeStatus.EXITED) {
					records.setManagerUnchecked(r.getId(), to, "JOB_CHANGE_REHOME", null);
				}
			}
		}
		UUID hid = history.add(id, kind, effective, true, changes, reason);
		audit.publish("employee", "Employee", id, "JOB_CHANGE", null, changes);
		events.publishEvent(new EmployeeJobChangedEvent(id, kind, effective));
		out.put("id", hid);
		out.put("state", "APPLIED");
		out.put("effectiveFrom", effective);
		return out;
	}

	/** Applies the changes through the profile rules (title decides position, manager checks, history of manager and type). */
	private void apply(Employee e, Map<String, Object> changes, String kind, LocalDate effective, String reason) {
		Map<String, Object> values = new LinkedHashMap<>();
		if (changes.containsKey("department")) {
			values.put("department", changes.get("department").toString());
		}
		if (changes.containsKey("position")) {
			values.put("designation", changes.get("position").toString());
		}
		if (changes.containsKey("titleId")) {
			values.put("title", changes.get("titleId").toString());
		}
		if (changes.containsKey("location")) {
			values.put("location", changes.get("location").toString());
		}
		if (changes.containsKey("manager")) {
			values.put("manager", changes.get("manager").toString());
		}
		if (changes.containsKey("type")) {
			values.put("employment_type", changes.get("type").toString());
		}
		try {
			records.apply(e.getId(), values, reason, effective);
		}
		catch (ConflictException ex) {
			if (ex instanceof CodedConflictException) {
				throw ex;
			}
			String m = ex.getMessage() == null ? "" : ex.getMessage();
			if (changes.containsKey("manager") && (m.contains("manager") || m.contains("reports"))) {
				throw new CodedConflictException("MANAGER_CYCLE", m);
			}
			throw ex;
		}
	}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> list(UUID id) {
		find(id);
		actions.require("view_history", id);
		return jdbc.sql("""
				select h.id, h.kind, h.effective_from, h.applied_at, h.cancelled_at, h.reason, h.title, dep.name, d.title, jt.name, l.name, m.display_name, et.name, h.status
				from employee_job_history h
				left join department dep on dep.id = h.department_id left join designation d on d.id = h.position_id left join job_title jt on jt.id = h.title_id
				left join location l on l.id = h.location_id left join employee m on m.id = h.manager_id left join employment_type et on et.id = h.employment_type_id
				where h.employee_id = :e order by h.effective_from desc, h.created_at desc""").param("e", id).query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("id", rs.getObject(1, UUID.class));
					m.put("kind", rs.getString(2));
					m.put("effectiveFrom", rs.getObject(3, LocalDate.class));
					m.put("state", rs.getObject(5) != null ? "CANCELLED" : rs.getObject(4) == null ? "SCHEDULED" : "APPLIED");
					m.put("reason", rs.getString(6));
					Map<String, Object> to = new LinkedHashMap<>();
					put(to, "department", rs.getString(8));
					put(to, "position", rs.getString(9));
					put(to, "title", rs.getString(10) != null ? rs.getString(10) : rs.getString(7));
					put(to, "location", rs.getString(11));
					put(to, "manager", rs.getString(12));
					put(to, "employmentType", rs.getString(13));
					put(to, "status", rs.getString(14));
					m.put("changes", to);
					return m;
				}).list();
	}

	@Transactional
	public void cancel(UUID id, UUID changeId) {
		find(id);
		actions.require("change_job", id);
		int n = jdbc.sql("update employee_job_history set cancelled_at = now() where id = :c and employee_id = :e and applied_at is null and cancelled_at is null")
				.param("c", changeId).param("e", id).update();
		if (n == 0) {
			throw new NotFoundException("No scheduled change " + changeId + " to cancel");
		}
		audit.publish("employee", "Employee", id, "JOB_CHANGE_CANCELLED", null, changeId.toString());
	}

	/** The 00:30 job: applies every scheduled change whose day has come. */
	public int applyDue() {
		LocalDate today = today();
		List<Map<String, Object>> due = jdbc.sql("""
				select id, employee_id, kind, effective_from, department_id, position_id, title_id, location_id, manager_id, employment_type_id, reason
				from employee_job_history where applied_at is null and cancelled_at is null and effective_from <= :d order by effective_from, created_at""")
				.param("d", today).query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("id", rs.getObject(1, UUID.class));
					m.put("employee", rs.getObject(2, UUID.class));
					m.put("kind", rs.getString(3));
					m.put("effective", rs.getObject(4, LocalDate.class));
					Map<String, Object> c = new LinkedHashMap<>();
					put(c, "department", rs.getObject(5, UUID.class));
					put(c, "position", rs.getObject(6, UUID.class));
					put(c, "titleId", rs.getObject(7, UUID.class));
					put(c, "location", rs.getObject(8, UUID.class));
					put(c, "manager", rs.getObject(9, UUID.class));
					put(c, "type", rs.getObject(10, UUID.class));
					m.put("changes", c);
					m.put("reason", rs.getString(11));
					return m;
				}).list();
		int done = 0;
		for (Map<String, Object> d : due) {
			try {
				applyOne(d);
				done++;
			}
			catch (RuntimeException ex) {
				log.warn("scheduled job change {} for {} could not be applied: {}", d.get("id"), d.get("employee"), ex.getMessage());
			}
		}
		return done;
	}

	@SuppressWarnings("unchecked")
	@Transactional
	void applyOne(Map<String, Object> d) {
		Employee e = employees.findById((UUID) d.get("employee")).orElseThrow();
		apply(e, (Map<String, Object>) d.get("changes"), (String) d.get("kind"), (LocalDate) d.get("effective"), (String) d.get("reason"));
		jdbc.sql("update employee_job_history set applied_at = now() where id = :id").param("id", d.get("id")).update();
		events.publishEvent(new EmployeeJobChangedEvent(e.getId(), (String) d.get("kind"), (LocalDate) d.get("effective")));
	}

	private static String kind(Object requested, Map<String, Object> changes) {
		if (requested != null && !requested.toString().isBlank()) {
			String k = requested.toString().toUpperCase();
			if (!KINDS.contains(k)) {
				throw new UnprocessableException(List.of(ErrorItem.field("kind", "INVALID", "Kind is one of " + String.join(", ", new ArrayList<>(KINDS)))));
			}
			return k;
		}
		if (changes.size() == 1 && changes.containsKey("manager")) {
			return "MANAGER_CHANGE";
		}
		if (changes.size() == 1 && changes.containsKey("type")) {
			return "TYPE_CHANGE";
		}
		if (changes.containsKey("department") || changes.containsKey("location")) {
			return "TRANSFER";
		}
		return "REDESIGNATION";
	}

	private static UUID uuid(Map<String, Object> body, String key) {
		Object o = body.get(key);
		if (o == null || o.toString().isBlank()) {
			return null;
		}
		try {
			return UUID.fromString(o.toString());
		}
		catch (IllegalArgumentException ex) {
			throw new UnprocessableException(List.of(ErrorItem.field(key, "INVALID_FORMAT", "Choose a value from the list")));
		}
	}

	private static void put(Map<String, Object> m, String k, Object v) {
		if (v != null) {
			m.put(k, v);
		}
	}

	private static void drop(Map<String, Object> m, String k, UUID current) {
		if (m.containsKey(k) && m.get(k).equals(current)) {
			m.remove(k);
		}
	}
}
