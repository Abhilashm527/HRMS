package com.ivoyant.hrms.employee.service;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.authz.FgaOutbox;
import com.ivoyant.hrms.common.authz.FgaTuples;
import com.ivoyant.hrms.common.event.EmployeeArchivedEvent;
import com.ivoyant.hrms.common.event.ExitInitiatedEvent;
import com.ivoyant.hrms.common.event.ExitWithdrawnEvent;
import com.ivoyant.hrms.common.event.ProbationConfirmedEvent;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.common.web.UnprocessableException;
import com.ivoyant.hrms.employee.EmployeeStatuses;
import com.ivoyant.hrms.employee.domain.Employee;
import com.ivoyant.hrms.employee.domain.EmployeeStatus;
import com.ivoyant.hrms.employee.repo.EmployeeRepository;
import com.ivoyant.hrms.notification.Notifier;
import com.ivoyant.hrms.org.EmploymentTypes;
import com.ivoyant.hrms.org.OrgProfile;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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

/** Probation (confirm, extend), exit (start, change, withdraw, complete on the last day) and archive. */
@Service
public class LifecycleService {

	private static final Logger log = LoggerFactory.getLogger(LifecycleService.class);
	private static final Set<String> EXIT_TYPES = Set.of("RESIGNATION", "TERMINATION", "CONTRACT_END", "RETIREMENT", "ABSCONDING");

	private final EmployeeRepository employees;
	private final EmployeeRecords records;
	private final EmployeeActions actions;
	private final JobHistory history;
	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final OrgProfile orgProfile;
	private final EmploymentTypes types;
	private final Audit audit;
	private final ApplicationEventPublisher events;
	private final FgaOutbox fga;
	private final Notifier notifier;

	LifecycleService(EmployeeRepository employees, EmployeeRecords records, EmployeeActions actions, JobHistory history, JdbcClient jdbc,
			CurrentUser currentUser, OrgProfile orgProfile, EmploymentTypes types, Audit audit, ApplicationEventPublisher events, FgaOutbox fga,
			Notifier notifier) {
		this.employees = employees;
		this.records = records;
		this.actions = actions;
		this.history = history;
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.orgProfile = orgProfile;
		this.types = types;
		this.audit = audit;
		this.events = events;
		this.fga = fga;
		this.notifier = notifier;
	}

	private Employee find(UUID id) {
		return employees.findByIdAndOrgId(id, currentUser.orgId()).filter(x -> x.getArchivedAt() == null)
				.orElseThrow(() -> new NotFoundException("Employee " + id + " not found"));
	}

	private LocalDate today() {
		return LocalDate.now(orgProfile.zone());
	}

	private static UnprocessableException problem(String field, String code, String message) {
		return new UnprocessableException(List.of(ErrorItem.field(field, code, message)));
	}

	private static boolean onProbation(Employee e) {
		return e.getStatus() == EmployeeStatus.ACTIVE && e.getProbationEndDate() != null && e.getConfirmedOn() == null;
	}

	// ================================================================== probation

	@Transactional
	public Map<String, Object> confirm(UUID id, Map<String, Object> body) {
		Employee e = find(id);
		actions.require("change_status", id);
		if (!onProbation(e)) {
			throw problem(null, "NOT_ON_PROBATION", e.getDisplayName() + " is not on probation");
		}
		LocalDate on = body.get("effectiveFrom") == null ? today() : LocalDate.parse(body.get("effectiveFrom").toString());
		e.setConfirmedOn(on);
		employees.saveAndFlush(e);
		history.add(id, "STATUS", on, true, Map.of("status", "CONFIRMED"), body.get("note") == null ? "Probation confirmed" : body.get("note").toString());
		audit.publish("employee", "Employee", id, "PROBATION_CONFIRM", null, on.toString());
		events.publishEvent(new ProbationConfirmedEvent(id));
		return Map.of("status", "ACTIVE", "confirmedOn", on);
	}

	@Transactional
	public Map<String, Object> extend(UUID id, Map<String, Object> body) {
		Employee e = find(id);
		actions.require("change_status", id);
		if (!onProbation(e)) {
			throw problem(null, "NOT_ON_PROBATION", e.getDisplayName() + " is not on probation");
		}
		int months;
		try {
			months = Integer.parseInt(String.valueOf(body.get("months")));
		}
		catch (NumberFormatException ex) {
			throw problem("months", "INVALID_FORMAT", "Months is a number from 1 to 6");
		}
		if (months < 1 || months > 6) {
			throw problem("months", "INVALID", "Extend by 1 to 6 months");
		}
		LocalDate end = e.getProbationEndDate().plusMonths(months);
		if (ChronoUnit.MONTHS.between(e.getJoiningDate(), end) > 12) {
			throw problem("months", "PROBATION_MAX", "Probation cannot run past 12 months in total");
		}
		e.setProbationEndDate(end);
		employees.saveAndFlush(e);
		history.add(id, "STATUS", today(), true, Map.of("status", "PROBATION_EXTENDED"), String.valueOf(body.get("reason")));
		audit.publish("employee", "Employee", id, "PROBATION_EXTEND", null, Map.of("months", months, "end", end.toString()));
		return Map.of("probationEnd", end);
	}

	/** 09:00 daily: tells HR and the manager 15 days and 1 day before probation ends. */
	public int remindProbation() {
		LocalDate t = today();
		List<Employee> due = employees.findAll().stream().filter(e -> e.getArchivedAt() == null && onProbation(e) && e.getProbationEndDate() != null
				&& (e.getProbationEndDate().equals(t.plusDays(15)) || e.getProbationEndDate().equals(t.plusDays(1)))).toList();
		for (Employee e : due) {
			Set<UUID> to = new LinkedHashSet<>(hrAdmins());
			if (e.getManagerId() != null) {
				to.add(e.getManagerId());
			}
			notifier.inApp(to, e.getDisplayName() + "'s probation ends on " + e.getProbationEndDate(), "Confirm or extend the probation.", "/employees/" + e.getId());
		}
		return due.size();
	}

	private List<UUID> hrAdmins() {
		return jdbc.sql("select distinct u.employee_id from user_role ur join role r on r.id = ur.role_id join app_user u on u.id = ur.user_id where r.key = 'hr_admin' and r.org_id = :org")
				.param("org", currentUser.orgId()).query(UUID.class).list();
	}

	// ================================================================== exit

	@Transactional
	public Map<String, Object> startExit(UUID id, Map<String, Object> body) {
		Employee e = find(id);
		actions.require("initiate_exit", id);
		if (e.getStatus() != EmployeeStatus.ACTIVE) {
			throw problem(null, "ALREADY_EXITING", e.getDisplayName() + " is already " + (e.getStatus() == EmployeeStatus.EXITED ? "gone" : "serving notice"));
		}
		String type = String.valueOf(body.get("type")).toUpperCase();
		if (!EXIT_TYPES.contains(type)) {
			throw problem("type", "INVALID", "Type is one of " + String.join(", ", EXIT_TYPES));
		}
		LocalDate notice = body.get("noticeDate") == null ? today() : LocalDate.parse(body.get("noticeDate").toString());
		int noticeDays = e.getEmploymentTypeId() == null ? 0 : types.noticeDays(e.getEmploymentTypeId());
		LocalDate lwd = body.get("lastWorkingDay") == null ? notice.plusDays(noticeDays) : LocalDate.parse(body.get("lastWorkingDay").toString());
		if (lwd.isBefore(today())) {
			throw problem("lastWorkingDay", "LWD_IN_PAST", "The last working day cannot be in the past");
		}
		if (lwd.isBefore(notice)) {
			throw problem("lastWorkingDay", "INVALID", "The last working day is before the notice date");
		}
		int shortfall = (int) Math.max(0, noticeDays - ChronoUnit.DAYS.between(notice, lwd));
		String previous = EmployeeStatuses.of(e.getStatus().name(), e.getProbationEndDate(), e.getConfirmedOn()).status();
		jdbc.sql("""
				insert into employee_exit (employee_id, type, notice_date, last_working_day, notice_shortfall_days, reason, eligible_for_rehire, previous_status, created_by)
				values (:e, :t, :n, :l, :s, :r, :rehire, :p, :by)
				on conflict (employee_id) do update set type = :t, notice_date = :n, last_working_day = :l, notice_shortfall_days = :s, reason = :r,
				  eligible_for_rehire = :rehire, previous_status = :p, withdrawn_at = null, completed_at = null""")
				.param("e", id).param("t", type).param("n", notice).param("l", lwd).param("s", shortfall).param("r", body.get("reason") == null ? null : body.get("reason").toString())
				.param("rehire", body.get("eligibleForRehire") instanceof Boolean b ? b : null, java.sql.Types.BOOLEAN).param("p", previous)
				.param("by", currentUser.employeeId(), java.sql.Types.OTHER).update();
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("status", EmployeeStatus.ON_NOTICE.name());
		values.put("exit_date", lwd.toString());
		records.apply(id, values);
		audit.publish("employee", "Employee", id, "EXIT_START", null, Map.of("type", type, "lastWorkingDay", lwd.toString()));
		events.publishEvent(new ExitInitiatedEvent(id, lwd));
		return exit(id);
	}

	@Transactional
	public Map<String, Object> changeLastDay(UUID id, Map<String, Object> body) {
		find(id);
		actions.require("initiate_exit", id);
		Map<String, Object> exit = activeExit(id);
		LocalDate lwd = LocalDate.parse(String.valueOf(body.get("lastWorkingDay")));
		if (lwd.isBefore(today())) {
			throw problem("lastWorkingDay", "LWD_IN_PAST", "The last working day cannot be in the past");
		}
		LocalDate notice = (LocalDate) exit.get("noticeDate");
		Employee e = find(id);
		int noticeDays = e.getEmploymentTypeId() == null ? 0 : types.noticeDays(e.getEmploymentTypeId());
		int shortfall = (int) Math.max(0, noticeDays - ChronoUnit.DAYS.between(notice, lwd));
		jdbc.sql("update employee_exit set last_working_day = :l, notice_shortfall_days = :s where employee_id = :e").param("l", lwd).param("s", shortfall).param("e", id).update();
		records.apply(id, Map.of("exit_date", lwd.toString()));
		audit.publish("employee", "Employee", id, "EXIT_CHANGE", null, lwd.toString());
		return exit(id);
	}

	@Transactional
	public Map<String, Object> withdraw(UUID id) {
		find(id);
		actions.require("initiate_exit", id);
		Map<String, Object> exit = activeExit(id);
		if (((LocalDate) exit.get("lastWorkingDay")).isBefore(today())) {
			throw problem(null, "LWD_IN_PAST", "The last working day has passed");
		}
		jdbc.sql("update employee_exit set withdrawn_at = now() where employee_id = :e").param("e", id).update();
		Employee e = find(id);
		e.setStatus(EmployeeStatus.ACTIVE);
		e.setExitDate(null);
		employees.saveAndFlush(e);
		audit.publish("employee", "Employee", id, "EXIT_WITHDRAW", null, null);
		events.publishEvent(new ExitWithdrawnEvent(id));
		return Map.of("status", EmployeeStatuses.of("ACTIVE", e.getProbationEndDate(), e.getConfirmedOn()).status());
	}

	@Transactional(readOnly = true)
	public Map<String, Object> exit(UUID id) {
		find(id);
		return jdbc.sql("select type, notice_date, last_working_day, notice_shortfall_days, reason, eligible_for_rehire, previous_status, withdrawn_at, completed_at from employee_exit where employee_id = :e")
				.param("e", id).query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("type", rs.getString(1));
					m.put("noticeDate", rs.getObject(2, LocalDate.class));
					m.put("lastWorkingDay", rs.getObject(3, LocalDate.class));
					m.put("noticeShortfallDays", rs.getInt(4));
					m.put("reason", rs.getString(5));
					m.put("eligibleForRehire", rs.getObject(6));
					m.put("previousStatus", rs.getString(7));
					m.put("state", rs.getObject(9) != null ? "COMPLETED" : rs.getObject(8) != null ? "WITHDRAWN" : "ACTIVE");
					return m;
				}).optional().orElseThrow(() -> new NotFoundException("No exit for this employee"));
	}

	private Map<String, Object> activeExit(UUID id) {
		Map<String, Object> exit = jdbc.sql("select notice_date, last_working_day from employee_exit where employee_id = :e and withdrawn_at is null and completed_at is null")
				.param("e", id).query((rs, i) -> Map.<String, Object>of("noticeDate", rs.getObject(1, LocalDate.class), "lastWorkingDay", rs.getObject(2, LocalDate.class)))
				.optional().orElse(null);
		if (exit == null) {
			throw problem(null, "NOT_EXITING", "This person is not serving notice");
		}
		return exit;
	}

	/** 23:30 daily: finishes exits whose last day has come. */
	public int completeDue() {
		LocalDate t = today();
		List<UUID> due = jdbc.sql("select e.employee_id from employee_exit e join employee p on p.id = e.employee_id where e.withdrawn_at is null and e.completed_at is null and e.last_working_day <= :d and p.org_id = :org")
				.param("d", t).param("org", currentUser.orgId()).query(UUID.class).list();
		int n = 0;
		for (UUID id : due) {
			try {
				complete(id);
				n++;
			}
			catch (RuntimeException ex) {
				log.warn("exit of {} could not be completed: {}", id, ex.getMessage());
			}
		}
		return n;
	}

	@Transactional
	public void complete(UUID id) {
		Employee e = employees.findById(id).orElseThrow();
		UUID manager = e.getManagerId();
		UUID dept = e.getDepartmentId();
		UUID loc = e.getLocationId();
		records.apply(id, Map.of("status", EmployeeStatus.EXITED.name()));
		jdbc.sql("update employee_exit set completed_at = now() where employee_id = :e").param("e", id).update();
		jdbc.sql("update app_user set status = 'DISABLED' where employee_id = :e").param("e", id).update();
		FgaTuples.employeeAll(currentUser.orgId(), id, manager, dept, loc).forEach(fga::delete);
		history.add(id, "EXIT", e.getExitDate() == null ? today() : e.getExitDate(), true, Map.of("status", "EXITED"), "Exit completed");
		audit.publish("employee", "Employee", id, "EXIT_COMPLETE", null, null);
	}

	// ================================================================== archive

	@Transactional
	public void archive(UUID id) {
		Employee e = find(id);
		actions.require("delete_employee", id);
		if (e.getStatus() != EmployeeStatus.EXITED) {
			throw problem(null, "NOT_EXITED", "Only someone who has left can be archived");
		}
		e.setArchivedAt(java.time.Instant.now());
		employees.saveAndFlush(e);
		audit.publish("employee", "Employee", id, "ARCHIVE", null, null);
		events.publishEvent(new EmployeeArchivedEvent(id));
	}

	@Transactional
	public void unarchive(UUID id) {
		Employee e = employees.findByIdAndOrgId(id, currentUser.orgId()).orElseThrow(() -> new NotFoundException("Employee " + id + " not found"));
		actions.require("delete_employee", id);
		e.setArchivedAt(null);
		employees.saveAndFlush(e);
		audit.publish("employee", "Employee", id, "UNARCHIVE", null, null);
	}
}
