package com.ivoyant.hrms.employee.service;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.ConflictException;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.UnprocessableException;
import com.ivoyant.hrms.employee.domain.Employee;
import com.ivoyant.hrms.employee.domain.EmployeeStatus;
import com.ivoyant.hrms.org.OrgSettings;
import com.ivoyant.hrms.org.ReportingLines;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Changing someone's manager with a date. Today (or a past date, for corrections) takes effect at once; a date up to 90 days ahead is
 * scheduled and a nightly job applies it. Every change is recorded in the reporting-line history.
 */
@Service
public class ManagerChangeService {

	private static final Logger log = LoggerFactory.getLogger(ManagerChangeService.class);
	private static final Set<String> REASONS = Set.of("CHANGE", "CORRECTION", "TRANSFER");
	private static final int MAX_AHEAD_DAYS = 90;

	private final EmployeeRecords records;
	private final ReportingLines lines;
	private final OrgSettings settings;
	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final Audit audit;

	public ManagerChangeService(EmployeeRecords records, ReportingLines lines, OrgSettings settings, JdbcClient jdbc,
			CurrentUser currentUser, Audit audit) {
		this.records = records;
		this.lines = lines;
		this.settings = settings;
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.audit = audit;
	}

	/** Body: {@code managerId} (null for none), {@code effectiveDate}, {@code reason} (CHANGE, CORRECTION, TRANSFER). */
	@Transactional
	public Map<String, Object> set(UUID employeeId, Map<String, Object> body) {
		Employee e = records.find(employeeId);
		UUID manager = body.get("managerId") == null || body.get("managerId").toString().isBlank() ? null : parse(body.get("managerId"));
		LocalDate today = LocalDate.now();
		LocalDate effective = body.get("effectiveDate") == null || body.get("effectiveDate").toString().isBlank() ? today
				: LocalDate.parse(body.get("effectiveDate").toString());
		String reason = body.get("reason") == null ? effective.isBefore(today) ? "CORRECTION" : "CHANGE"
				: body.get("reason").toString().trim().toUpperCase();
		if (!REASONS.contains(reason)) {
			throw invalid("reason", "INVALID", "Reason must be one of " + String.join(", ", REASONS));
		}
		if (effective.isAfter(today.plusDays(MAX_AHEAD_DAYS))) {
			throw invalid("effectiveDate", "EFFECTIVE_DATE_RANGE", "A change can be scheduled at most " + MAX_AHEAD_DAYS + " days ahead");
		}
		Map<String, Object> before = state(e);
		List<String> warnings = new ArrayList<>();
		if (effective.isAfter(today)) {
			validateAhead(e, manager);
			schedule(e, manager, effective, reason);
		}
		else {
			if (effective.isBefore(today)) {
				LocalDate since = jdbc.sql("select from_date from reporting_line_history where employee_id = :e and to_date is null")
						.param("e", employeeId).query(LocalDate.class).optional().orElse(null);
				if (since != null && effective.isBefore(since)) {
					throw invalid("effectiveDate", "EFFECTIVE_DATE_RANGE", "The current manager started on " + since + "; pick a date after that");
				}
			}
			cancelPending(employeeId);
			records.changeManager(employeeId, manager, reason, effective);
		}
		if (manager != null) {
			Employee m = records.find(manager);
			if (m.getStatus() == EmployeeStatus.ON_NOTICE) {
				warnings.add(m.getDisplayName() + " is serving notice; their reports will need a new manager");
			}
			long span = lines.reports(manager, false).size() + (effective.isAfter(today) ? 1 : 0);
			if (span > settings.maxSpan()) {
				warnings.add(m.getDisplayName() + " would have " + span + " direct reports (the limit is " + settings.maxSpan() + ")");
			}
			if (m.getDepartmentId() != null && !m.getDepartmentId().equals(e.getDepartmentId())) {
				warnings.add("The manager is in a different department");
			}
		}
		Map<String, Object> out = state(records.find(employeeId));
		out.put("scheduled", scheduled(employeeId));
		out.put("warnings", warnings);
		audit.updated("employee", "ReportingLine", employeeId, before, out);
		return out;
	}

	private Map<String, Object> state(Employee e) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("employeeId", e.getId());
		m.put("managerId", e.getManagerId());
		return m;
	}

	private Map<String, Object> scheduled(UUID employeeId) {
		return jdbc.sql("select manager_id, from_date from reporting_line_history where employee_id = :e and to_date is null and from_date > current_date")
				.param("e", employeeId).query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("managerId", rs.getObject(1, UUID.class));
					m.put("effectiveDate", rs.getObject(2, LocalDate.class));
					return m;
				}).optional().orElse(null);
	}

	/** A future change is checked now against today's lines (it is checked again the day it applies). */
	private void validateAhead(Employee e, UUID manager) {
		if (manager == null) {
			if (!records.isTopHolder(e)) {
				throw invalid("managerId", "MANAGER_REQUIRED", "Everyone except the top position needs a manager.");
			}
			return;
		}
		Employee m = records.find(manager);
		if (manager.equals(e.getId()) || lines.isAbove(e.getId(), manager)) {
			throw new ConflictException("That manager reports (directly or indirectly) to this employee");
		}
		if (m.getStatus() == EmployeeStatus.EXITED) {
			throw invalid("managerId", "MANAGER_INACTIVE", m.getDisplayName() + " has left");
		}
	}

	/** The current line stops the day before; the new one is stored from the date and applied by the nightly job. */
	private void schedule(Employee e, UUID manager, LocalDate effective, String reason) {
		cancelPending(e.getId());
		jdbc.sql("update reporting_line_history set to_date = greatest(from_date, :d - 1) where employee_id = :e and to_date is null")
				.param("d", effective).param("e", e.getId()).update();
		jdbc.sql("insert into reporting_line_history (org_id, employee_id, manager_id, from_date, reason, created_by) values (:org, :e, :m, :d, :r, :by)")
				.param("org", e.getOrgId()).param("e", e.getId()).param("m", manager, java.sql.Types.OTHER).param("d", effective)
				.param("r", reason).param("by", currentUser.employeeId(), java.sql.Types.OTHER).update();
	}

	/** Drops a scheduled change that has not applied yet, and reopens the current line. */
	private void cancelPending(UUID employeeId) {
		int removed = jdbc.sql("delete from reporting_line_history where employee_id = :e and to_date is null and from_date > current_date")
				.param("e", employeeId).update();
		if (removed > 0) {
			jdbc.sql("""
					update reporting_line_history set to_date = null where id = (
					    select id from reporting_line_history where employee_id = :e order by from_date desc limit 1)""")
					.param("e", employeeId).update();
		}
	}

	/** Every night: scheduled changes whose day has come are applied. */
	@Scheduled(cron = "0 15 0 * * *")
	public void applyDue() {
		List<Object[]> due = jdbc.sql("""
				select h.employee_id, h.manager_id, h.from_date from reporting_line_history h join employee e on e.id = h.employee_id
				where h.to_date is null and h.from_date <= current_date and h.manager_id is distinct from e.manager_id and e.status <> 'EXITED'
				""").query((rs, i) -> new Object[] {rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, LocalDate.class)}).list();
		for (Object[] d : due) {
			try {
				records.applyScheduledManager((UUID) d[0], (UUID) d[1], (LocalDate) d[2]);
			}
			catch (RuntimeException ex) {
				log.warn("Could not apply the scheduled manager change for {}: {}", d[0], ex.getMessage());
			}
		}
	}

	private static UUID parse(Object v) {
		try {
			return UUID.fromString(v.toString().trim());
		}
		catch (IllegalArgumentException ex) {
			throw invalid("managerId", "INVALID", "'" + v + "' is not a valid id");
		}
	}

	private static UnprocessableException invalid(String field, String code, String message) {
		return new UnprocessableException(List.of(ErrorItem.field(field, code, message)));
	}
}
