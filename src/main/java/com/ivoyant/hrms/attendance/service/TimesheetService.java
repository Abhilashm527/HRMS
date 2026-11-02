package com.ivoyant.hrms.attendance.service;

import com.ivoyant.hrms.common.security.ModuleAccess;
import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.ForbiddenException;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.common.web.UnprocessableException;
import com.ivoyant.hrms.notification.Notifier;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Weekly timesheets: hours per project per day, Monday to Sunday, on the projects a person is a member of. A week is a draft until it is
 * submitted; the manager (or HR) approves it or sends it back, and a sent-back week can be edited and submitted again.
 */
@Service
public class TimesheetService {

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final ModuleAccess access;
	private final Notifier notifier;
	private final Audit audit;

	TimesheetService(JdbcClient jdbc, CurrentUser currentUser, ModuleAccess access, Notifier notifier, Audit audit) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.access = access;
		this.notifier = notifier;
		this.audit = audit;
	}

	static LocalDate monday(LocalDate d) {
		return d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
	}

	@Transactional(readOnly = true)
	public Map<String, Object> week(LocalDate any) {
		UUID me = currentUser.requireEmployeeId();
		return view(me, monday(any == null ? LocalDate.now() : any), true);
	}

	private Map<String, Object> view(UUID emp, LocalDate start, boolean own) {
		LocalDate end = start.plusDays(6);
		Map<String, Object> ts = jdbc.sql("""
				select t.id, t.status, t.submitted_at, t.decided_at, t.decision_note, x.display_name from timesheet t left join employee x on x.id = t.decided_by
				where t.employee_id = :e and t.week_start = :w""").param("e", emp).param("w", start).query((rs, i) -> {
			Map<String, Object> m = new HashMap<>();
			m.put("id", rs.getObject(1, UUID.class));
			m.put("status", rs.getString(2));
			m.put("submittedAt", rs.getObject(3, OffsetDateTime.class));
			m.put("decidedAt", rs.getObject(4, OffsetDateTime.class));
			m.put("decisionNote", rs.getString(5));
			m.put("decidedBy", rs.getString(6));
			return m;
		}).optional().orElse(null);
		// projects they belong to that week, plus any they logged hours on
		Map<UUID, String> projects = new LinkedHashMap<>();
		jdbc.sql("""
				select p.id, p.name from project_member m join project p on p.id = m.project_id
				where m.employee_id = :e and m.since <= :end and (m.until is null or m.until >= :start) order by lower(p.name)""").param("e", emp).param("start", start)
				.param("end", end).query((rs, i) -> projects.put(rs.getObject(1, UUID.class), rs.getString(2))).list();
		Map<UUID, BigDecimal[]> hours = new LinkedHashMap<>();
		if (ts != null) {
			jdbc.sql("select e.project_id, p.name, e.day, e.hours from timesheet_entry e join project p on p.id = e.project_id where e.timesheet_id = :t")
					.param("t", ts.get("id")).query((rs, i) -> {
						UUID pid = rs.getObject(1, UUID.class);
						projects.putIfAbsent(pid, rs.getString(2));
						int d = (int) (rs.getObject(3, LocalDate.class).toEpochDay() - start.toEpochDay());
						hours.computeIfAbsent(pid, k -> new BigDecimal[7])[d] = rs.getBigDecimal(4);
						return 0;
					}).list();
		}
		List<Map<String, Object>> rows = new ArrayList<>();
		for (Map.Entry<UUID, BigDecimal[]> e : hours.entrySet()) {
			rows.add(row(e.getKey(), projects.get(e.getKey()), e.getValue()));
		}
		List<Map<String, Object>> days = new ArrayList<>();
		for (int i = 0; i < 7; i++) {
			LocalDate d = start.plusDays(i);
			days.add(Map.of("date", d, "weekend", d.getDayOfWeek().getValue() >= 6));
		}
		String status = ts == null ? "DRAFT" : (String) ts.get("status");
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("weekStart", start);
		out.put("days", days);
		out.put("status", status);
		out.put("submittedAt", ts == null ? null : ts.get("submittedAt"));
		out.put("decidedAt", ts == null ? null : ts.get("decidedAt"));
		out.put("decidedBy", ts == null ? null : ts.get("decidedBy"));
		out.put("decisionNote", ts == null ? null : ts.get("decisionNote"));
		out.put("rows", rows);
		out.put("projects", projects.entrySet().stream().map(e -> Map.of("id", e.getKey(), "name", e.getValue())).toList());
		out.put("editable", own && ("DRAFT".equals(status) || "REJECTED".equals(status)) && !start.isAfter(monday(LocalDate.now())));
		return out;
	}

	private static Map<String, Object> row(UUID project, String name, BigDecimal[] h) {
		List<BigDecimal> list = new ArrayList<>();
		BigDecimal total = BigDecimal.ZERO;
		for (BigDecimal x : h) {
			list.add(x);
			if (x != null) {
				total = total.add(x);
			}
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("projectId", project);
		m.put("name", name);
		m.put("hours", list);
		m.put("total", total);
		return m;
	}

	/** Body: {@code {rows: [{projectId, hours: [7 numbers or null]}]}}. Replaces the week's hours. */
	@Transactional
	public Map<String, Object> save(LocalDate any, Map<String, Object> body) {
		UUID me = currentUser.requireEmployeeId();
		LocalDate start = monday(any);
		if (start.isAfter(monday(LocalDate.now()))) {
			throw problem("week", "FUTURE", "You can't fill in a future week yet");
		}
		Map<String, Object> cur = view(me, start, true);
		if (!Boolean.TRUE.equals(cur.get("editable"))) {
			throw new ForbiddenException("This week is " + cur.get("status").toString().toLowerCase() + " and can't be changed");
		}
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> rows = body.get("rows") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> allowed = (List<Map<String, Object>>) cur.get("projects");
		java.util.Set<UUID> ok = new java.util.HashSet<>();
		allowed.forEach(p -> ok.add((UUID) p.get("id")));
		BigDecimal[] perDay = new BigDecimal[7];
		java.util.Arrays.fill(perDay, BigDecimal.ZERO);
		List<Object[]> entries = new ArrayList<>();
		for (Map<String, Object> r : rows) {
			UUID pid = UUID.fromString(String.valueOf(r.get("projectId")));
			if (!ok.contains(pid)) {
				throw problem("rows", "PROJECT", "You can only log hours on your projects");
			}
			Object hs = r.get("hours");
			if (!(hs instanceof List<?> h) || h.size() != 7) {
				throw problem("rows", "INVALID", "Each project needs a value for every day");
			}
			for (int i = 0; i < 7; i++) {
				Object v = h.get(i);
				if (v == null || v.toString().isBlank()) {
					continue;
				}
				BigDecimal x;
				try {
					x = new BigDecimal(v.toString().trim());
				}
				catch (NumberFormatException e) {
					throw problem("rows", "INVALID", "Hours are numbers like 7.5");
				}
				if (x.signum() < 0 || x.compareTo(BigDecimal.valueOf(24)) > 0) {
					throw problem("rows", "RANGE", "Hours in a day are between 0 and 24");
				}
				if (x.signum() == 0) {
					continue;
				}
				perDay[i] = perDay[i].add(x);
				if (perDay[i].compareTo(BigDecimal.valueOf(24)) > 0) {
					throw problem("rows", "DAY_TOTAL", "A day can't have more than 24 hours");
				}
				entries.add(new Object[] {pid, start.plusDays(i), x});
			}
		}
		UUID id = sheet(me, start);
		jdbc.sql("delete from timesheet_entry where timesheet_id = :t").param("t", id).update();
		for (Object[] e : entries) {
			jdbc.sql("insert into timesheet_entry (timesheet_id, project_id, day, hours) values (:t, :p, :d, :h)").param("t", id).param("p", e[0]).param("d", e[1])
					.param("h", e[2]).update();
		}
		jdbc.sql("update timesheet set updated_at = now() where id = :t").param("t", id).update();
		return view(me, start, true);
	}

	@Transactional
	public Map<String, Object> submit(LocalDate any) {
		UUID me = currentUser.requireEmployeeId();
		LocalDate start = monday(any);
		Map<String, Object> cur = view(me, start, true);
		if (!Boolean.TRUE.equals(cur.get("editable"))) {
			throw new ForbiddenException("This week is already " + cur.get("status").toString().toLowerCase());
		}
		if (((List<?>) cur.get("rows")).isEmpty()) {
			throw problem("rows", "EMPTY", "Log some hours before submitting");
		}
		UUID approver = jdbc.sql("select manager_id from employee where id = :e").param("e", me).query(UUID.class).optional().orElse(null);
		UUID id = sheet(me, start);
		jdbc.sql("update timesheet set status = 'SUBMITTED', submitted_at = now(), approver_id = :a, decided_by = null, decided_at = null, decision_note = null where id = :t")
				.param("a", approver, java.sql.Types.OTHER).param("t", id).update();
		if (approver != null) {
			String who = jdbc.sql("select display_name from employee where id = :e").param("e", me).query(String.class).single();
			notifier.inApp(List.of(approver), "ATTENDANCE", "Timesheet to approve", who + " · week of " + start, "/leave?tab=attendance&fix=1");
		}
		audit.publish("attendance", "Timesheet", id, "SUBMIT", null, Map.of("week", start.toString()));
		return view(me, start, true);
	}

	/** Submitted weeks waiting for the caller, or every one when they can manage attendance. */
	@Transactional(readOnly = true)
	public Map<String, Object> pending() {
		UUID me = currentUser.requireEmployeeId();
		boolean hr = access.has("attendance", "EDIT") || access.has("leave", "ADMIN");
		List<Map<String, Object>> rows = jdbc.sql("""
				select t.id, t.week_start, t.submitted_at, e.id, e.display_name, d.name,
				       (select coalesce(sum(x.hours), 0) from timesheet_entry x where x.timesheet_id = t.id)
				from timesheet t join employee e on e.id = t.employee_id left join department d on d.id = e.department_id
				where t.org_id = :org and t.status = 'SUBMITTED' and (:hr or t.approver_id = :me) and t.employee_id <> :me
				order by t.submitted_at limit 200""").param("org", currentUser.orgId()).param("hr", hr).param("me", me).query((rs, i) -> {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", rs.getObject(1, UUID.class));
			m.put("weekStart", rs.getObject(2, LocalDate.class));
			m.put("submittedAt", rs.getObject(3, OffsetDateTime.class));
			m.put("employee", Map.of("id", rs.getObject(4, UUID.class), "name", rs.getString(5), "department", rs.getString(6) == null ? "" : rs.getString(6)));
			m.put("total", rs.getBigDecimal(7));
			return m;
		}).list();
		for (Map<String, Object> m : rows) {
			@SuppressWarnings("unchecked")
			Map<String, Object> e = (Map<String, Object>) m.get("employee");
			Map<String, Object> v = view((UUID) e.get("id"), (LocalDate) m.get("weekStart"), false);
			m.put("rows", v.get("rows"));
			m.put("days", v.get("days"));
		}
		return Map.of("items", rows);
	}

	@Transactional
	public Map<String, Object> decide(UUID id, boolean approve, String note) {
		UUID me = currentUser.requireEmployeeId();
		Map<String, Object> t = jdbc.sql("select employee_id, approver_id, week_start from timesheet where id = :id and org_id = :org and status = 'SUBMITTED'")
				.param("id", id).param("org", currentUser.orgId())
				.query((rs, i) -> Map.<String, Object>of("e", rs.getObject(1, UUID.class), "a", rs.getObject(2, UUID.class) == null ? new UUID(0, 0) : rs.getObject(2, UUID.class),
						"w", rs.getObject(3, LocalDate.class)))
				.optional().orElseThrow(() -> new NotFoundException("This timesheet is no longer waiting"));
		boolean hr = access.has("attendance", "EDIT") || access.has("leave", "ADMIN");
		if (me.equals(t.get("e")) || (!hr && !me.equals(t.get("a")))) {
			throw new ForbiddenException("Only their manager or HR can decide this");
		}
		if (!approve && (note == null || note.isBlank())) {
			throw problem("note", "REQUIRED", "Say what needs changing");
		}
		jdbc.sql("update timesheet set status = :s, decided_by = :by, decided_at = now(), decision_note = :n where id = :id").param("s", approve ? "APPROVED" : "REJECTED")
				.param("by", me).param("n", note == null || note.isBlank() ? null : note.trim(), java.sql.Types.VARCHAR).param("id", id).update();
		notifier.inApp(List.of((UUID) t.get("e")), "ATTENDANCE", approve ? "Timesheet approved" : "Timesheet sent back",
				"Week of " + t.get("w") + (approve ? "" : " · " + note.trim()), "/me/attendance?tab=timesheet");
		audit.publish("attendance", "Timesheet", id, approve ? "APPROVE" : "REJECT", null, Map.of("week", t.get("w").toString()));
		return Map.of("status", approve ? "APPROVED" : "REJECTED");
	}

	private UUID sheet(UUID emp, LocalDate start) {
		return jdbc.sql("""
				insert into timesheet (org_id, employee_id, week_start) values (:org, :e, :w)
				on conflict (employee_id, week_start) do update set updated_at = now() returning id""").param("org", currentUser.orgId()).param("e", emp).param("w", start)
				.query(UUID.class).single();
	}

	private static UnprocessableException problem(String field, String code, String message) {
		return new UnprocessableException(List.of(ErrorItem.field(field, code, message)));
	}
}
