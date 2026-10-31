package com.ivoyant.hrms.calendar.service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.security.ModuleAccess;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.ForbiddenException;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.common.web.UnprocessableException;

/**
 * Editing a holiday calendar a year at a time (Leave &amp; attendance → Holidays). A regional calendar shows the national holidays too; those
 * are edited on the national calendar. Closing a year makes it read-only; it can be reopened.
 */
@Service
public class HolidayAdminService {

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final ModuleAccess access;
	private final Audit audit;

	HolidayAdminService(JdbcClient jdbc, CurrentUser currentUser, ModuleAccess access, Audit audit) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.access = access;
		this.audit = audit;
	}

	private static UnprocessableException problem(String field, String code, String message) {
		return new UnprocessableException(List.of(ErrorItem.field(field, code, message)));
	}

	private boolean canEdit() {
		return access.has("calendar", "ADMIN") || access.has("leave", "ADMIN");
	}

	private void requireEdit() {
		if (!canEdit()) {
			throw new ForbiddenException("Your role can't edit holidays");
		}
	}

	private record Cal(UUID id, String name, String state) {
	}

	private Cal calendar(UUID id) {
		return jdbc.sql("select id, name, state from holiday_calendar where id = :id and org_id = :org").param("id", id).param("org", currentUser.orgId())
				.query((rs, i) -> new Cal(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3))).optional()
				.orElseThrow(() -> new NotFoundException("Holiday calendar " + id + " not found"));
	}

	private UUID national() {
		return jdbc.sql("select id from holiday_calendar where org_id = :org and state is null and active order by name limit 1").param("org", currentUser.orgId()).query(UUID.class)
				.optional().orElse(null);
	}

	private boolean locked(UUID calendarId, int year) {
		return jdbc.sql("select count(*) from holiday_year_lock where calendar_id = :c and year = :y").param("c", calendarId).param("y", year).query(Long.class).single() > 0;
	}

	private void requireOpen(UUID calendarId, int year) {
		if (locked(calendarId, year)) {
			throw problem("date", "YEAR_CLOSED", year + " is closed · reopen it to make changes");
		}
	}

	@Transactional(readOnly = true)
	public Map<String, Object> year(UUID calendarId, int year) {
		Cal c = calendar(calendarId);
		List<Map<String, Object>> types = jdbc.sql("select id, name, fg, bg, day_off from holiday_type where org_id = :org order by sort, name").param("org", currentUser.orgId())
				.query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("id", rs.getObject(1, UUID.class));
					m.put("name", rs.getString(2));
					m.put("fg", rs.getString(3));
					m.put("bg", rs.getString(4));
					m.put("dayOff", rs.getBoolean(5));
					return m;
				}).list();
		List<Map<String, Object>> rows = new ArrayList<>(holidays(calendarId, year, false));
		UUID nat = national();
		if (c.state() != null && nat != null && !nat.equals(calendarId)) {
			java.util.Set<LocalDate> own = new java.util.HashSet<>();
			rows.forEach(r -> own.add((LocalDate) r.get("date")));
			holidays(nat, year, true).stream().filter(r -> !own.contains(r.get("date"))).forEach(rows::add);
		}
		rows.sort((a, b) -> ((LocalDate) a.get("date")).compareTo((LocalDate) b.get("date")));
		Map<String, Object> lock = jdbc.sql("select closed_at, closed_by from holiday_year_lock where calendar_id = :c and year = :y").param("c", calendarId).param("y", year)
				.query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("closedAt", rs.getObject(1, OffsetDateTime.class));
					UUID by = rs.getObject(2, UUID.class);
					m.put("closedBy", by == null ? null : jdbc.sql("select display_name from employee where id = :id").param("id", by).query(String.class).optional().orElse(null));
					return m;
				}).optional().orElse(null);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("calendar", Map.of("id", c.id(), "name", c.name(), "national", c.state() == null));
		out.put("calendars", jdbc.sql("select id, name, state from holiday_calendar where org_id = :org and active order by state nulls first, name").param("org", currentUser.orgId())
				.query((rs, i) -> Map.of("id", rs.getObject(1, UUID.class), "name", rs.getString(2), "national", rs.getString(3) == null)).list());
		out.put("year", year);
		out.put("types", types);
		out.put("holidays", rows);
		out.put("closed", lock);
		out.put("canEdit", canEdit());
		return out;
	}

	private List<Map<String, Object>> holidays(UUID calendarId, int year, boolean inherited) {
		return jdbc.sql("select h.id, h.date, h.name, h.type_id from holiday h where h.calendar_id = :c and h.date between :a and :b order by h.date").param("c", calendarId)
				.param("a", LocalDate.of(year, 1, 1)).param("b", LocalDate.of(year, 12, 31)).query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("id", rs.getObject(1, UUID.class));
					m.put("date", rs.getObject(2, LocalDate.class));
					m.put("name", rs.getString(3));
					m.put("typeId", rs.getObject(4, UUID.class));
					m.put("inherited", inherited);
					return m;
				}).list();
	}

	private UUID typeId(Object o) {
		if (o == null || o.toString().isBlank()) {
			throw problem("typeId", "REQUIRED", "Choose a type");
		}
		UUID t = UUID.fromString(o.toString());
		if (jdbc.sql("select count(*) from holiday_type where id = :t and org_id = :org").param("t", t).param("org", currentUser.orgId()).query(Long.class).single() == 0) {
			throw problem("typeId", "UNKNOWN", "That holiday type doesn't exist");
		}
		return t;
	}

	private static LocalDate date(Object o) {
		if (o == null || o.toString().isBlank()) {
			throw problem("date", "REQUIRED", "Choose a date");
		}
		return LocalDate.parse(o.toString().substring(0, 10));
	}

	private static String name(Object o) {
		if (o == null || o.toString().isBlank()) {
			throw problem("name", "REQUIRED", "Give the holiday a name");
		}
		return o.toString().trim();
	}

	private void requireFree(UUID calendarId, LocalDate d, UUID except) {
		String other = jdbc.sql("select name from holiday where calendar_id = :c and date = :d and (cast(:x as uuid) is null or id <> :x)").param("c", calendarId).param("d", d)
				.param("x", except, java.sql.Types.OTHER).query(String.class).optional().orElse(null);
		if (other != null) {
			throw problem("date", "DUPLICATE", "There's already a holiday on this date: " + other);
		}
	}

	/** {@code {date, name, typeId}} */
	@Transactional
	public Map<String, Object> add(UUID calendarId, Map<String, Object> b) {
		requireEdit();
		calendar(calendarId);
		LocalDate d = date(b.get("date"));
		requireOpen(calendarId, d.getYear());
		String n = name(b.get("name"));
		UUID t = typeId(b.get("typeId"));
		requireFree(calendarId, d, null);
		UUID id = jdbc.sql("insert into holiday (calendar_id, date, name, type_id) values (:c, :d, :n, :t) returning id").param("c", calendarId).param("d", d).param("n", n)
				.param("t", t).query(UUID.class).single();
		audit.created("calendar", "Holiday", id, Map.of("date", d.toString(), "name", n));
		return year(calendarId, d.getYear());
	}

	/** Any of {@code {date, name, typeId}}. */
	@Transactional
	public Map<String, Object> update(UUID calendarId, UUID holidayId, Map<String, Object> b) {
		requireEdit();
		Map<String, Object> h = jdbc.sql("select date, name, type_id from holiday where id = :id and calendar_id = :c").param("id", holidayId).param("c", calendarId)
				.query((rs, i) -> Map.<String, Object>of("date", rs.getObject(1, LocalDate.class), "name", rs.getString(2), "typeId", rs.getObject(3, UUID.class))).optional()
				.orElseThrow(() -> new NotFoundException("Holiday " + holidayId + " not found"));
		LocalDate old = (LocalDate) h.get("date");
		requireOpen(calendarId, old.getYear());
		LocalDate d = b.containsKey("date") ? date(b.get("date")) : old;
		if (d.getYear() != old.getYear()) {
			throw problem("date", "OTHER_YEAR", "Keep the date in " + old.getYear() + " · add it to the other year instead");
		}
		String n = b.containsKey("name") ? name(b.get("name")) : (String) h.get("name");
		UUID t = b.containsKey("typeId") ? typeId(b.get("typeId")) : (UUID) h.get("typeId");
		requireFree(calendarId, d, holidayId);
		jdbc.sql("update holiday set date = :d, name = :n, type_id = :t where id = :id").param("d", d).param("n", n).param("t", t).param("id", holidayId).update();
		audit.updated("calendar", "Holiday", holidayId, h, Map.of("date", d.toString(), "name", n));
		return year(calendarId, d.getYear());
	}

	@Transactional
	public Map<String, Object> delete(UUID calendarId, UUID holidayId) {
		requireEdit();
		LocalDate d = jdbc.sql("select date from holiday where id = :id and calendar_id = :c").param("id", holidayId).param("c", calendarId).query(LocalDate.class).optional()
				.orElseThrow(() -> new NotFoundException("Holiday " + holidayId + " not found"));
		requireOpen(calendarId, d.getYear());
		jdbc.sql("delete from holiday where id = :id").param("id", holidayId).update();
		audit.deleted("calendar", "Holiday", holidayId, Map.of("date", d.toString()));
		return year(calendarId, d.getYear());
	}

	@Transactional
	public Map<String, Object> close(UUID calendarId, int year) {
		requireEdit();
		calendar(calendarId);
		jdbc.sql("insert into holiday_year_lock (calendar_id, year, closed_by) values (:c, :y, :by) on conflict do nothing").param("c", calendarId).param("y", year)
				.param("by", currentUser.requireEmployeeId()).update();
		audit.publish("calendar", "HolidayYear", calendarId, "CLOSE", null, year);
		return year(calendarId, year);
	}

	@Transactional
	public Map<String, Object> reopen(UUID calendarId, int year) {
		requireEdit();
		calendar(calendarId);
		jdbc.sql("delete from holiday_year_lock where calendar_id = :c and year = :y").param("c", calendarId).param("y", year).update();
		audit.publish("calendar", "HolidayYear", calendarId, "REOPEN", null, year);
		return year(calendarId, year);
	}
}
