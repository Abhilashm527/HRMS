package com.ivoyant.hrms.attendance.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ivoyant.hrms.calendar.HolidayCalendars;
import com.ivoyant.hrms.calendar.HolidayView;
import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.security.ModuleAccess;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.ForbiddenException;
import com.ivoyant.hrms.common.web.UnprocessableException;
import com.ivoyant.hrms.leave.LeaveDays;
import com.ivoyant.hrms.org.Locations;
import com.ivoyant.hrms.org.WorkSchedule;

/**
 * Attendance: people check in and out (one record per person per day), and HR sees the day's board.
 *
 * A person's status for a day, in order: on approved leave; a day off (weekly off or a day-off holiday at their location); working from
 * home; present, or late when they checked in after the location's start time plus grace; otherwise absent (or "not in yet" today before the
 * grace period ends). Times are shown in the location's time zone.
 */
@Service
public class AttendanceService {

	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final ModuleAccess access;
	private final Locations locations;
	private final HolidayCalendars calendars;
	private final LeaveDays leave;
	private final Audit audit;
	private final com.ivoyant.hrms.notification.Notifier notifier;

	AttendanceService(JdbcClient jdbc, CurrentUser currentUser, ModuleAccess access, Locations locations, HolidayCalendars calendars, LeaveDays leave, Audit audit,
			com.ivoyant.hrms.notification.Notifier notifier) {
		this.notifier = notifier;
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.access = access;
		this.locations = locations;
		this.calendars = calendars;
		this.leave = leave;
		this.audit = audit;
	}

	private static UnprocessableException problem(String field, String code, String message) {
		return new UnprocessableException(List.of(ErrorItem.field(field, code, message)));
	}

	record Rec(UUID employeeId, OffsetDateTime in, OffsetDateTime out, String source, boolean wfh, String note) {
	}

	private Map<UUID, Rec> records(LocalDate day) {
		Map<UUID, Rec> m = new HashMap<>();
		jdbc.sql("select employee_id, in_at, out_at, source, wfh, note from attendance_record where org_id = :org and day = :d").param("org", currentUser.orgId()).param("d", day)
				.query((rs, i) -> m.put(rs.getObject(1, UUID.class), new Rec(rs.getObject(1, UUID.class), rs.getObject(2, OffsetDateTime.class), rs.getObject(3, OffsetDateTime.class),
						rs.getString(4), rs.getBoolean(5), rs.getString(6))))
				.list();
		return m;
	}

	/** A location's schedule and holidays for a year, worked out once per board. */
	private final class Site {
		final WorkSchedule schedule;
		final Map<LocalDate, HolidayView> holidays = new HashMap<>();

		Site(UUID locationId, UUID anyEmployee, int year) {
			schedule = locationId == null ? locations.scheduleForEmployee(anyEmployee) : locations.scheduleFor(locationId);
			UUID cal = locationId == null ? calendars.nationalOnly().orElse(null) : locations.holidayCalendarFor(locationId);
			if (cal != null) {
				calendars.holidays(cal, year).forEach(h -> holidays.put(h.date(), h));
			}
		}

		ZoneId zone() {
			return schedule.zone() == null ? IST : schedule.zone();
		}
	}

	record Status(String key, String label, String kind) {
	}

	private static Status status(LocalDate day, Site site, LeaveDays.OnLeave onLeave, Rec rec) {
		if (onLeave != null) {
			return new Status("leave", onLeave.half() ? "½ day " + onLeave.code() : "On leave · " + onLeave.code(), "paused");
		}
		HolidayView h = site.holidays.get(day);
		boolean dayOff = h != null && h.dayOff() || !site.schedule.isWorkingDay(day);
		if (dayOff) {
			// someone who checked in on a day off worked it (it may earn comp-off)
			if (rec != null && (rec.in() != null || rec.wfh())) {
				return new Status("present", "Worked · " + (h != null && h.dayOff() ? h.name() : "weekly off"), "running");
			}
			return new Status("off", h != null && h.dayOff() ? h.name() : "Weekly off", "stopped");
		}
		if (rec != null && rec.wfh()) {
			return new Status("wfh", "WFH", "running");
		}
		if (rec != null && rec.in() != null) {
			LocalTime in = rec.in().atZoneSameInstant(site.zone()).toLocalTime();
			boolean late = in.isAfter(site.schedule.start().plusMinutes(site.schedule.graceMinutes()));
			return late ? new Status("late", "Late", "pending") : new Status("present", "Present", "healthy");
		}
		ZonedDateTime now = ZonedDateTime.now(site.zone());
		if (day.isAfter(now.toLocalDate())) {
			return new Status("upcoming", "—", "stopped");
		}
		if (day.equals(now.toLocalDate()) && !now.toLocalTime().isAfter(site.schedule.start().plusMinutes(site.schedule.graceMinutes()))) {
			return new Status("absent", "Not in yet", "stopped");
		}
		return new Status("absent", day.equals(now.toLocalDate()) ? "Not in" : "Absent", "failed");
	}

	/** The day's board. {@code status}: present, late, wfh, leave, absent, off. */
	@Transactional(readOnly = true)
	public Map<String, Object> board(LocalDate day, String q, UUID dept, UUID loc, String statusFilter, int page, int size) {
		if (!access.has("leave", "VIEW") && !access.has("attendance", "VIEW")) {
			throw new ForbiddenException("Your role can't see the attendance board");
		}
		LocalDate d = day == null ? LocalDate.now(IST) : day;
		StringBuilder w = new StringBuilder(" where e.org_id = :org and e.archived_at is null and e.joining_date <= :d and (e.exit_date is null or e.exit_date >= :d)");
		Map<String, Object> p = new HashMap<>();
		p.put("org", currentUser.orgId());
		p.put("d", d);
		if (q != null && !q.isBlank()) {
			w.append(" and (lower(e.display_name) like :q or lower(e.employee_code) like :q)");
			p.put("q", "%" + q.trim().toLowerCase(Locale.ROOT) + "%");
		}
		if (dept != null) {
			w.append(" and e.department_id = :dept");
			p.put("dept", dept);
		}
		if (loc != null) {
			w.append(" and e.location_id = :loc");
			p.put("loc", loc);
		}
		record Person(UUID id, String code, String name, String first, String last, UUID deptId, String dept, UUID locId, String loc, int photo) {
		}
		List<Person> people = jdbc.sql("""
				select e.id, e.employee_code, e.display_name, e.first_name, e.last_name, e.department_id, dep.name, e.location_id, l.name, e.photo_version
				  from employee e left join department dep on dep.id = e.department_id left join location l on l.id = e.location_id""" + w + " order by lower(e.display_name)")
				.params(p).query((rs, i) -> new Person(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getObject(6, UUID.class),
						rs.getString(7), rs.getObject(8, UUID.class), rs.getString(9), rs.getInt(10)))
				.list();
		Map<UUID, Rec> recs = records(d);
		Map<UUID, LeaveDays.OnLeave> onLeave = leave.onLeave(d);
		Map<UUID, Site> sites = new HashMap<>();
		Site orgSite = null;
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (String k : List.of("present", "late", "wfh", "leave", "absent", "off")) {
			counts.put(k, 0);
		}
		List<Map<String, Object>> rows = new ArrayList<>();
		for (Person e : people) {
			Site site;
			if (e.locId() == null) {
				if (orgSite == null) {
					orgSite = new Site(null, e.id(), d.getYear());
				}
				site = orgSite;
			}
			else {
				site = sites.computeIfAbsent(e.locId(), k -> new Site(k, e.id(), d.getYear()));
			}
			Rec rec = recs.get(e.id());
			Status st = status(d, site, onLeave.get(e.id()), rec);
			counts.merge(st.key().equals("upcoming") ? "absent" : st.key(), 1, Integer::sum);
			if (statusFilter != null && !statusFilter.isBlank() && !"all".equals(statusFilter) && !statusFilter.equals(st.key())) {
				continue;
			}
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", e.id());
			m.put("code", e.code());
			m.put("name", e.name());
			m.put("initials", ((e.first() == null || e.first().isBlank() ? "" : e.first().substring(0, 1)) + (e.last() == null || e.last().isBlank() ? "" : e.last().substring(0, 1)))
					.toUpperCase(Locale.ROOT));
			m.put("photoUrl", "/api/v1/employees/" + e.id() + "/photo?s=64&v=" + e.photo());
			if (e.deptId() != null) {
				m.put("department", Map.of("id", e.deptId(), "name", e.dept(), "color", Math.floorMod(e.deptId().hashCode(), 8)));
			}
			if (e.locId() != null) {
				m.put("location", Map.of("id", e.locId(), "name", e.loc()));
			}
			m.put("status", st.key());
			m.put("label", st.label());
			m.put("kind", st.kind());
			m.put("in", rec == null || rec.in() == null ? null : rec.in().atZoneSameInstant(site.zone()).toLocalTime().withNano(0).toString().substring(0, 5));
			m.put("out", rec == null || rec.out() == null ? null : rec.out().atZoneSameInstant(site.zone()).toLocalTime().withNano(0).toString().substring(0, 5));
			if (rec != null && rec.in() != null && rec.out() != null) {
				long mins = Duration.between(rec.in(), rec.out()).toMinutes();
				m.put("hours", String.format("%d:%02d", mins / 60, mins % 60));
			}
			else {
				m.put("hours", null);
			}
			m.put("source", rec == null ? null : rec.source());
			m.put("wfh", rec != null && rec.wfh());
			m.put("note", rec == null ? null : rec.note());
			rows.add(m);
		}
		int sz = Math.max(1, Math.min(size <= 0 ? 20 : size, 200));
		int a = Math.min(rows.size(), Math.max(0, page) * sz);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("day", d);
		out.put("today", LocalDate.now(IST));
		out.put("total", rows.size());
		out.put("headcount", people.size());
		out.put("counts", counts);
		out.put("items", rows.subList(a, Math.min(rows.size(), a + sz)));
		out.put("page", page);
		out.put("size", sz);
		out.put("canEdit", access.has("attendance", "EDIT") || access.has("leave", "ADMIN"));
		return out;
	}

	/** Holidays (national calendar) for the date picker's month dots. */
	@Transactional(readOnly = true)
	public List<Map<String, Object>> holidays(int year) {
		UUID cal = calendars.nationalOnly().orElse(null);
		if (cal == null) {
			return List.of();
		}
		return calendars.holidays(cal, year).stream().map(h -> Map.<String, Object>of("date", h.date(), "name", h.name(), "dayOff", h.dayOff())).toList();
	}

	// ================================================================== self service

	@Transactional(readOnly = true)
	public Map<String, Object> me() {
		UUID me = currentUser.requireEmployeeId();
		LocalDate today = LocalDate.now(locations.zoneForEmployee(me));
		Rec r = records(today).get(me);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("day", today);
		out.put("in", r == null ? null : r.in());
		out.put("out", r == null ? null : r.out());
		out.put("wfh", r != null && r.wfh());
		out.put("source", r == null ? null : r.source());
		WorkSchedule s = locations.scheduleForEmployee(me);
		out.put("start", s.start());
		out.put("end", s.end());
		out.put("graceMinutes", s.graceMinutes());
		return out;
	}

	/** {@code {wfh?, source?}} */
	@Transactional
	public Map<String, Object> checkIn(Map<String, Object> b) {
		UUID me = currentUser.requireEmployeeId();
		LocalDate today = LocalDate.now(locations.zoneForEmployee(me));
		if (records(today).containsKey(me) && records(today).get(me).in() != null) {
			throw problem("in", "ALREADY_IN", "You've already checked in today");
		}
		boolean wfh = Boolean.parseBoolean(String.valueOf(b == null ? null : b.get("wfh")));
		String source = source(b == null ? null : b.get("source"));
		jdbc.sql("""
				insert into attendance_record (org_id, employee_id, day, in_at, source, wfh, updated_by) values (:org, :e, :d, now(), :s, :w, :e)
				on conflict (employee_id, day) do update set in_at = coalesce(attendance_record.in_at, now()), wfh = :w, source = :s, updated_at = now(), updated_by = :e""")
				.param("org", currentUser.orgId()).param("e", me).param("d", today).param("s", source).param("w", wfh).update();
		return me();
	}

	@Transactional
	public Map<String, Object> checkOut() {
		UUID me = currentUser.requireEmployeeId();
		LocalDate today = LocalDate.now(locations.zoneForEmployee(me));
		int n = jdbc.sql("update attendance_record set out_at = now(), updated_at = now(), updated_by = :e where employee_id = :e and day = :d and in_at is not null")
				.param("e", me).param("d", today).update();
		if (n == 0) {
			throw problem("out", "NOT_IN", "Check in first");
		}
		return me();
	}

	private static String source(Object o) {
		String s = o == null ? "WEB" : o.toString().toUpperCase(Locale.ROOT);
		return Set.of("WEB", "MOBILE_GPS", "BIOMETRIC", "QR").contains(s) ? s : "WEB";
	}

	/** HR corrects a day: {@code {in: "09:40", out: "18:10", wfh, note}} in the person's local time; empty in and out clears the day. */
	@Transactional
	public Map<String, Object> correct(UUID employeeId, LocalDate day, Map<String, Object> b) {
		if (!access.has("attendance", "EDIT") && !access.has("leave", "ADMIN")) {
			throw new ForbiddenException("Your role can't correct attendance");
		}
		ZoneId zone = locations.zoneForEmployee(employeeId);
		LocalTime in = time(b.get("in"), "in");
		LocalTime out = time(b.get("out"), "out");
		if (in != null && out != null && !out.isAfter(in)) {
			throw problem("out", "TIMES_ORDER", "Check-out must be after check-in");
		}
		boolean wfh = Boolean.parseBoolean(String.valueOf(b.get("wfh")));
		String note = b.get("note") == null || b.get("note").toString().isBlank() ? null : b.get("note").toString().trim();
		if (note == null) {
			throw problem("note", "REQUIRED", "Add a reason for the correction");
		}
		if (in == null && out == null && !wfh) {
			jdbc.sql("delete from attendance_record where employee_id = :e and day = :d").param("e", employeeId).param("d", day).update();
		}
		else {
			jdbc.sql("""
					insert into attendance_record (org_id, employee_id, day, in_at, out_at, source, wfh, note, updated_by) values (:org, :e, :d, :i, :o, 'MANUAL', :w, :n, :by)
					on conflict (employee_id, day) do update set in_at = :i, out_at = :o, source = 'MANUAL', wfh = :w, note = :n, updated_at = now(), updated_by = :by""")
					.param("org", currentUser.orgId()).param("e", employeeId).param("d", day)
					.param("i", in == null ? null : day.atTime(in).atZone(zone).toOffsetDateTime(), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
					.param("o", out == null ? null : day.atTime(out).atZone(zone).toOffsetDateTime(), java.sql.Types.TIMESTAMP_WITH_TIMEZONE).param("w", wfh).param("n", note)
					.param("by", currentUser.employeeId(), java.sql.Types.OTHER).update();
		}
		audit.publish("attendance", "AttendanceRecord", employeeId, "CORRECT", null, Map.of("day", day.toString(), "note", note));
		return Map.of("ok", true);
	}

	private static LocalTime time(Object o, String field) {
		if (o == null || o.toString().isBlank()) {
			return null;
		}
		try {
			return LocalTime.parse(o.toString().trim());
		}
		catch (RuntimeException e) {
			throw problem(field, "INVALID", "Use a time like 09:30");
		}
	}

	// ================================================================== my days and fixing them

	/** The caller's last {@code days} days, newest first, with each day's status and any request to fix it. */
	@Transactional(readOnly = true)
	public Map<String, Object> myLog(int days) {
		UUID me = currentUser.requireEmployeeId();
		int n = Math.max(7, Math.min(days, 62));
		UUID loc = jdbc.sql("select location_id from employee where id = :e").param("e", me).query(UUID.class).optional().orElse(null);
		ZoneId zone = locations.zoneForEmployee(me);
		LocalDate today = LocalDate.now(zone);
		LocalDate from = today.minusDays(n - 1L);
		LocalDate joined = jdbc.sql("select joining_date from employee where id = :e").param("e", me).query(LocalDate.class).optional().orElse(from);
		Map<LocalDate, Rec> recs = new HashMap<>();
		jdbc.sql("select day, in_at, out_at, source, wfh, note from attendance_record where employee_id = :e and day between :a and :b").param("e", me).param("a", from)
				.param("b", today).query((rs, i) -> recs.put(rs.getObject(1, LocalDate.class), new Rec(me, rs.getObject(2, OffsetDateTime.class),
						rs.getObject(3, OffsetDateTime.class), rs.getString(4), rs.getBoolean(5), rs.getString(6)))).list();
		Map<LocalDate, Map<String, Object>> regs = new HashMap<>();
		jdbc.sql("""
				select id, day, status, in_time, out_time, wfh, reason, decision_note from attendance_regularisation
				where employee_id = :e and day between :a and :b and status in ('PENDING', 'REJECTED') order by created_at""").param("e", me).param("a", from).param("b", today)
				.query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("id", rs.getObject(1, UUID.class));
					m.put("status", rs.getString(3));
					m.put("in", rs.getObject(4, LocalTime.class) == null ? null : rs.getObject(4, LocalTime.class).toString().substring(0, 5));
					m.put("out", rs.getObject(5, LocalTime.class) == null ? null : rs.getObject(5, LocalTime.class).toString().substring(0, 5));
					m.put("wfh", rs.getBoolean(6));
					m.put("reason", rs.getString(7));
					m.put("decisionNote", rs.getString(8));
					return regs.put(rs.getObject(2, LocalDate.class), m);
				}).list();
		Map<Integer, Site> sites = new HashMap<>();
		List<Map<String, Object>> rows = new ArrayList<>();
		for (LocalDate d = today; !d.isBefore(from); d = d.minusDays(1)) {
			if (d.isBefore(joined)) {
				break;
			}
			Site site = sites.computeIfAbsent(d.getYear(), y -> new Site(loc, me, y));
			Rec rec = recs.get(d);
			Status st = status(d, site, leave.onLeave(d).get(me), rec);
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("day", d);
			m.put("status", st.key());
			m.put("label", st.label());
			m.put("kind", st.kind());
			m.put("in", rec == null || rec.in() == null ? null : rec.in().atZoneSameInstant(site.zone()).toLocalTime().toString().substring(0, 5));
			m.put("out", rec == null || rec.out() == null ? null : rec.out().atZoneSameInstant(site.zone()).toLocalTime().toString().substring(0, 5));
			if (rec != null && rec.in() != null && rec.out() != null) {
				long mins = Duration.between(rec.in(), rec.out()).toMinutes();
				m.put("hours", String.format("%d:%02d", mins / 60, mins % 60));
			}
			else {
				m.put("hours", null);
			}
			m.put("wfh", rec != null && rec.wfh());
			m.put("source", rec == null ? null : rec.source());
			m.put("note", rec == null ? null : rec.note());
			Map<String, Object> reg = regs.get(d);
			m.put("request", reg);
			boolean missingOut = rec != null && rec.in() != null && rec.out() == null && d.isBefore(today);
			boolean fixable = !"leave".equals(st.key()) && !"off".equals(st.key()) && !"upcoming".equals(st.key())
					&& ("absent".equals(st.key()) && d.isBefore(today) || "late".equals(st.key()) || missingOut);
			m.put("missingOut", missingOut);
			m.put("canRegularise", fixable && (reg == null || !"PENDING".equals(reg.get("status"))) && !d.isBefore(today.minusDays(30)));
			rows.add(m);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("today", me());
		out.put("days", rows);
		out.put("approver", approverName(me));
		return out;
	}

	/** {@code {day, in, out, wfh, reason}}: asks the manager (or HR) to correct a past day. */
	@Transactional
	public Map<String, Object> regularise(Map<String, Object> b) {
		UUID me = currentUser.requireEmployeeId();
		ZoneId zone = locations.zoneForEmployee(me);
		LocalDate today = LocalDate.now(zone);
		LocalDate day;
		try {
			day = LocalDate.parse(String.valueOf(b.get("day")));
		}
		catch (RuntimeException e) {
			throw problem("day", "INVALID", "Pick the day to fix");
		}
		if (day.isAfter(today) || day.isBefore(today.minusDays(30))) {
			throw problem("day", "RANGE", "You can fix days from the last 30 days");
		}
		LocalTime in = time(b.get("in"), "in");
		LocalTime out = time(b.get("out"), "out");
		boolean wfh = Boolean.parseBoolean(String.valueOf(b.get("wfh")));
		if (in == null && !wfh) {
			throw problem("in", "REQUIRED", "Enter when you started");
		}
		if (in != null && out != null && !out.isAfter(in)) {
			throw problem("out", "TIMES_ORDER", "Check-out must be after check-in");
		}
		if (day.equals(today) && out != null && out.isAfter(LocalTime.now(zone))) {
			throw problem("out", "FUTURE", "Check-out can't be later than now");
		}
		String reason = b.get("reason") == null ? "" : b.get("reason").toString().trim();
		if (reason.length() < 3) {
			throw problem("reason", "REQUIRED", "Say why the day needs fixing");
		}
		if (jdbc.sql("select count(*) from attendance_regularisation where employee_id = :e and day = :d and status = 'PENDING'").param("e", me).param("d", day)
				.query(Long.class).single() > 0) {
			throw problem("day", "PENDING", "You already asked to fix this day");
		}
		UUID approver = jdbc.sql("select manager_id from employee where id = :e").param("e", me).query(UUID.class).optional().orElse(null);
		UUID id = jdbc.sql("""
				insert into attendance_regularisation (org_id, employee_id, day, in_time, out_time, wfh, reason, approver_id)
				values (:org, :e, :d, :i, :o, :w, :r, :a) returning id""").param("org", currentUser.orgId()).param("e", me).param("d", day)
				.param("i", in, java.sql.Types.TIME).param("o", out, java.sql.Types.TIME).param("w", wfh).param("r", reason)
				.param("a", approver, java.sql.Types.OTHER).query(UUID.class).single();
		String who = jdbc.sql("select display_name from employee where id = :e").param("e", me).query(String.class).single();
		if (approver != null) {
			notifier.inApp(List.of(approver), "ATTENDANCE", "Attendance fix to approve", who + " · " + day + " · " + reason, "/leave?tab=attendance&fix=1");
		}
		audit.publish("attendance", "Regularisation", id, "CREATE", null, Map.of("day", day.toString(), "reason", reason));
		return Map.of("id", id, "approver", approverName(me));
	}

	@Transactional
	public void cancelRegularisation(UUID id) {
		UUID me = currentUser.requireEmployeeId();
		if (jdbc.sql("update attendance_regularisation set status = 'CANCELLED' where id = :id and employee_id = :e and status = 'PENDING'").param("id", id).param("e", me)
				.update() == 0) {
			throw new ForbiddenException("This request can't be withdrawn");
		}
	}

	/** Requests to fix a day: those waiting for the caller, or every one when they can correct attendance. */
	@Transactional(readOnly = true)
	public Map<String, Object> regularisations(String status) {
		UUID me = currentUser.requireEmployeeId();
		boolean hr = access.has("attendance", "EDIT") || access.has("leave", "ADMIN");
		String st = status == null || status.isBlank() ? "PENDING" : status.toUpperCase(Locale.ROOT);
		List<Map<String, Object>> rows = jdbc.sql("""
				select r.id, r.day, r.in_time, r.out_time, r.wfh, r.reason, r.status, r.created_at, e.id, e.display_name, d.name, r.decision_note, x.display_name
				from attendance_regularisation r join employee e on e.id = r.employee_id left join department d on d.id = e.department_id
				left join employee x on x.id = r.decided_by
				where r.org_id = :org and r.status = :st and (:hr or r.approver_id = :me)
				order by r.created_at desc limit 200""").param("org", currentUser.orgId()).param("st", st).param("hr", hr).param("me", me).query((rs, i) -> {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", rs.getObject(1, UUID.class));
			m.put("day", rs.getObject(2, LocalDate.class));
			m.put("in", rs.getObject(3, LocalTime.class) == null ? null : rs.getObject(3, LocalTime.class).toString().substring(0, 5));
			m.put("out", rs.getObject(4, LocalTime.class) == null ? null : rs.getObject(4, LocalTime.class).toString().substring(0, 5));
			m.put("wfh", rs.getBoolean(5));
			m.put("reason", rs.getString(6));
			m.put("status", rs.getString(7));
			m.put("createdAt", rs.getObject(8, OffsetDateTime.class));
			m.put("employee", Map.of("id", rs.getObject(9, UUID.class), "name", rs.getString(10), "department", rs.getString(11) == null ? "" : rs.getString(11)));
			m.put("decisionNote", rs.getString(12));
			m.put("decidedBy", rs.getString(13));
			return m;
		}).list();
		return Map.of("items", rows);
	}

	/** Approving writes the corrected times onto the day. */
	@Transactional
	public Map<String, Object> decideRegularisation(UUID id, boolean approve, String note) {
		UUID me = currentUser.requireEmployeeId();
		Map<String, Object> r = jdbc.sql("""
				select employee_id, day, in_time, out_time, wfh, reason, approver_id from attendance_regularisation
				where id = :id and org_id = :org and status = 'PENDING'""").param("id", id).param("org", currentUser.orgId()).query((rs, i) -> {
			Map<String, Object> m = new HashMap<>();
			m.put("e", rs.getObject(1, UUID.class));
			m.put("d", rs.getObject(2, LocalDate.class));
			m.put("i", rs.getObject(3, LocalTime.class));
			m.put("o", rs.getObject(4, LocalTime.class));
			m.put("w", rs.getBoolean(5));
			m.put("r", rs.getString(6));
			m.put("a", rs.getObject(7, UUID.class));
			return m;
		}).optional().orElseThrow(() -> new com.ivoyant.hrms.common.web.NotFoundException("This request is no longer waiting"));
		boolean hr = access.has("attendance", "EDIT") || access.has("leave", "ADMIN");
		if (!hr && !me.equals(r.get("a"))) {
			throw new ForbiddenException("Only their manager or HR can decide this");
		}
		if (me.equals(r.get("e"))) {
			throw new ForbiddenException("You can't approve your own request");
		}
		if (!approve && (note == null || note.isBlank())) {
			throw problem("note", "REQUIRED", "Say why it's rejected");
		}
		UUID emp = (UUID) r.get("e");
		LocalDate day = (LocalDate) r.get("d");
		if (approve) {
			ZoneId zone = locations.zoneForEmployee(emp);
			LocalTime in = (LocalTime) r.get("i");
			LocalTime out = (LocalTime) r.get("o");
			String recNote = "Regularised: " + r.get("r");
			jdbc.sql("""
					insert into attendance_record (org_id, employee_id, day, in_at, out_at, source, wfh, note, updated_by) values (:org, :e, :d, :i, :o, 'MANUAL', :w, :n, :by)
					on conflict (employee_id, day) do update set in_at = coalesce(:i, attendance_record.in_at), out_at = coalesce(:o, attendance_record.out_at),
					  source = 'MANUAL', wfh = :w, note = :n, updated_at = now(), updated_by = :by""")
					.param("org", currentUser.orgId()).param("e", emp).param("d", day)
					.param("i", in == null ? null : day.atTime(in).atZone(zone).toOffsetDateTime(), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
					.param("o", out == null ? null : day.atTime(out).atZone(zone).toOffsetDateTime(), java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
					.param("w", r.get("w")).param("n", recNote).param("by", me).update();
		}
		jdbc.sql("update attendance_regularisation set status = :s, decided_by = :by, decided_at = now(), decision_note = :n where id = :id")
				.param("s", approve ? "APPROVED" : "REJECTED").param("by", me).param("n", note == null || note.isBlank() ? null : note.trim(), java.sql.Types.VARCHAR)
				.param("id", id).update();
		notifier.inApp(List.of(emp), "ATTENDANCE", approve ? "Attendance fixed" : "Attendance fix not approved",
				day + (approve ? " has been corrected" : " · " + note.trim()), "/me/attendance");
		audit.publish("attendance", "Regularisation", id, approve ? "APPROVE" : "REJECT", null, Map.of("day", day.toString()));
		return Map.of("status", approve ? "APPROVED" : "REJECTED");
	}

	private String approverName(UUID employee) {
		return jdbc.sql("select m.display_name from employee e join employee m on m.id = e.manager_id where e.id = :e").param("e", employee).query(String.class)
				.optional().orElse("HR");
	}
}
