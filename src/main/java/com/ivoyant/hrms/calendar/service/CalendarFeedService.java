package com.ivoyant.hrms.calendar.service;

import com.ivoyant.hrms.calendar.service.CalendarLayer.Edit;
import com.ivoyant.hrms.calendar.service.CalendarLayer.Perm;
import com.ivoyant.hrms.calendar.service.CalendarLayer.See;
import com.ivoyant.hrms.calendar.service.CalendarLayer.Viewer;
import com.ivoyant.hrms.common.web.BadRequestException;
import com.ivoyant.hrms.common.web.NotFoundException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Month;
import java.time.MonthDay;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the calendar: stored events (expanded for repeats and moved into the reader's time zone) plus what other modules already know
 * — holidays of the reader's office, birthdays (unless someone opted out), work anniversaries and leave. Every item says whether the
 * reader sees it in full or only as "Busy", and what they may do with it.
 */
@Service
public class CalendarFeedService {

	static final int MAX_DAYS = 100;
	private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");
	private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

	/** A stored event with what the feed needs about its type, room, audience and organiser. */
	record Ev(UUID id, CalendarLayer layer, UUID typeId, String typeKey, String typeName, String fg, String bg, String title, LocalDate date,
			LocalTime start, LocalTime end, String tz, String where, UUID roomId, String roomName, int roomCapacity, String roomLocation,
			String audience, UUID audienceRef, String audienceRefName, List<String> roles, boolean priv, boolean sensitive, String repeat,
			LocalDate until, String remind, String notes, String status, UUID organiserId, String organiserName, long version, List<UUID> people) {

		boolean repeats() {
			return !"NONE".equals(repeat);
		}
	}

	record SysType(String name, String fg, String bg, boolean active) {
	}

	private final JdbcClient jdbc;
	private final CalendarViewers viewers;

	public CalendarFeedService(JdbcClient jdbc, CalendarViewers viewers) {
		this.jdbc = jdbc;
		this.viewers = viewers;
	}

	// ---------------------------------------------------------------------------------------------------------------- reading events

	static final String EV_SQL = """
			select ev.*, t.key as type_key, t.name as type_name, t.fg, t.bg, r.name as room_name, r.capacity as room_capacity,
			       rl.name as room_location, o.display_name as organiser_name,
			       case ev.audience when 'TEAM' then (select display_name from employee where id = ev.audience_ref)
			                        when 'DEPT' then (select name from department where id = ev.audience_ref)
			                        when 'LOC' then (select name from location where id = ev.audience_ref) end as audience_ref_name,
			       array(select employee_id::text from calendar_event_person p where p.event_id = ev.id) as people
			  from calendar_event ev
			  join calendar_event_type t on t.id = ev.type_id
			  join employee o on o.id = ev.organiser_id
			  left join calendar_room r on r.id = ev.room_id
			  left join location rl on rl.id = r.location_id
			""";

	static Ev ev(ResultSet rs) throws SQLException {
		String[] ppl = (String[]) rs.getArray("people").getArray();
		String[] roles = (String[]) rs.getArray("audience_roles").getArray();
		return new Ev(rs.getObject("id", UUID.class), CalendarLayer.valueOf(rs.getString("layer")), rs.getObject("type_id", UUID.class),
				rs.getString("type_key"), rs.getString("type_name"), rs.getString("fg"), rs.getString("bg"), rs.getString("title"),
				rs.getObject("starts_on", LocalDate.class), rs.getObject("start_time", LocalTime.class), rs.getObject("end_time", LocalTime.class),
				rs.getString("time_zone"), rs.getString("where_text"), rs.getObject("room_id", UUID.class), rs.getString("room_name"),
				rs.getInt("room_capacity"), rs.getString("room_location"), rs.getString("audience"), rs.getObject("audience_ref", UUID.class),
				rs.getString("audience_ref_name"), List.of(roles), rs.getBoolean("private"), rs.getBoolean("sensitive"), rs.getString("repeat"),
				rs.getObject("repeat_until", LocalDate.class), rs.getString("remind"), rs.getString("notes"), rs.getString("status"),
				rs.getObject("organiser_id", UUID.class), rs.getString("organiser_name"), rs.getLong("version"),
				Arrays.stream(ppl).map(UUID::fromString).toList());
	}

	Ev load(UUID orgId, UUID id) {
		return jdbc.sql(EV_SQL + " where ev.id = :id and ev.org_id = :org").param("id", id).param("org", orgId).query((rs, i) -> ev(rs))
				.optional().orElseThrow(() -> new NotFoundException("Event not found"));
	}

	/** Events with an occurrence that may fall between the two days. */
	List<Ev> overlapping(UUID orgId, LocalDate from, LocalDate to) {
		return jdbc.sql(EV_SQL + """
				 where ev.org_id = :org and ev.starts_on <= :to
				   and (case when ev.repeat = 'NONE' then ev.starts_on else coalesce(ev.repeat_until, :to) end) >= :from""")
				.param("org", orgId).param("from", from).param("to", to).query((rs, i) -> ev(rs)).list();
	}

	// ---------------------------------------------------------------------------------------------------------------- who sees what

	static boolean inAudience(Ev e, Viewer v) {
		return switch (e.audience()) {
			case "ALL" -> true;
			case "TEAM" -> v.id().equals(e.audienceRef()) || (v.managerId() != null && v.managerId().equals(e.audienceRef()));
			case "DEPT" -> e.audienceRef() != null && e.audienceRef().equals(v.departmentId());
			case "LOC" -> e.audienceRef() != null && e.audienceRef().equals(v.locationId());
			case "PEOPLE" -> e.people().contains(v.id());
			case "ROLE" -> e.roles().stream().anyMatch(v::holds);
			default -> false;
		};
	}

	/** FULL, BUSY or null (not shown). */
	static See see(Ev e, Viewer v) {
		boolean mine = e.organiserId().equals(v.id());
		boolean invited = "PEOPLE".equals(e.audience()) && e.people().contains(v.id());
		if (!"LIVE".equals(e.status())) {
			return mine || v.admin() ? See.FULL : null;
		}
		if (e.layer() == CalendarLayer.MY) {
			return mine ? See.FULL : null;
		}
		if (e.sensitive()) {
			return mine || invited || v.admin() ? See.FULL : null;
		}
		if (mine || invited) {
			return See.FULL;
		}
		Perm p = v.perm(e.layer());
		if (p.see() == See.NONE || (!v.admin() && !inAudience(e, v))) {
			return null;
		}
		return e.priv() ? See.BUSY : p.see();
	}

	static boolean canEdit(Ev e, Viewer v) {
		if (see(e, v) != See.FULL) {
			return false;
		}
		Perm p = v.perm(e.layer());
		return e.organiserId().equals(v.id()) ? p.add() || p.edit() != Edit.NONE : p.edit() == Edit.ANY;
	}

	static boolean rsvpable(Ev e) {
		return e.layer() != CalendarLayer.MY && "LIVE".equals(e.status()) && !"DEADLINE".equals(e.typeKey()) && !"ME".equals(e.audience());
	}

	// ---------------------------------------------------------------------------------------------------------------- repeats & zones

	static boolean occursOn(Ev e, LocalDate d) {
		if (d.isBefore(e.date()) || (e.until() != null && d.isAfter(e.until()))) {
			return false;
		}
		return switch (e.repeat()) {
			case "WEEKLY" -> ChronoUnit.DAYS.between(e.date(), d) % 7 == 0;
			case "MONTHLY" -> d.getDayOfMonth() == e.date().getDayOfMonth();
			default -> d.equals(e.date());
		};
	}

	static List<LocalDate> occurrences(Ev e, LocalDate from, LocalDate to) {
		List<LocalDate> out = new ArrayList<>();
		if (!e.repeats()) {
			if (!e.date().isBefore(from) && !e.date().isAfter(to)) {
				out.add(e.date());
			}
			return out;
		}
		LocalDate end = e.until() != null && e.until().isBefore(to) ? e.until() : to;
		if ("WEEKLY".equals(e.repeat())) {
			LocalDate d = e.date();
			if (d.isBefore(from)) {
				d = d.plusWeeks((ChronoUnit.DAYS.between(d, from) + 6) / 7);
			}
			for (; !d.isAfter(end); d = d.plusWeeks(1)) {
				out.add(d);
			}
			return out;
		}
		for (LocalDate m = (e.date().isBefore(from) ? from : e.date()).withDayOfMonth(1); !m.isAfter(end); m = m.plusMonths(1)) {
			if (e.date().getDayOfMonth() <= m.lengthOfMonth()) {
				LocalDate d = m.withDayOfMonth(e.date().getDayOfMonth());
				if (!d.isBefore(e.date()) && !d.isBefore(from) && !d.isAfter(end)) {
					out.add(d);
				}
			}
		}
		return out;
	}

	ZoneId zoneOf(Ev e, ZoneId orgZone) {
		try {
			return e.tz() == null || e.tz().isBlank() ? orgZone : ZoneId.of(e.tz());
		}
		catch (RuntimeException ex) {
			return orgZone;
		}
	}

	static String zoneShort(ZoneId z, LocalDate d) {
		return z.getDisplayName(TextStyle.SHORT, Locale.ENGLISH).replace("GMT+05:30", "IST");
	}

	// ---------------------------------------------------------------------------------------------------------------- the feed

	@Transactional(readOnly = true)
	public Map<String, Object> feed(LocalDate from, LocalDate to) {
		return feed(viewers.current(), from, to, true);
	}

	Map<String, Object> feed(Viewer v, LocalDate from, LocalDate to, boolean withMeta) {
		if (from == null || to == null || to.isBefore(from)) {
			throw new BadRequestException("Pass from and to (yyyy-mm-dd), with to on or after from");
		}
		if (ChronoUnit.DAYS.between(from, to) > MAX_DAYS) {
			throw new BadRequestException("Ask for at most " + MAX_DAYS + " days at a time");
		}
		ZoneId orgZone = viewers.orgZone(v.orgId());
		Map<String, SysType> sys = systemTypes(v.orgId());
		Map<UUID, String> myRsvp = new HashMap<>();
		jdbc.sql("select event_id, answer from calendar_event_rsvp where employee_id = :me").param("me", v.id())
				.query((rs, i) -> myRsvp.put(rs.getObject(1, UUID.class), rs.getString(2))).list();

		List<Map<String, Object>> items = new ArrayList<>();
		for (Ev e : overlapping(v.orgId(), from.minusDays(1), to.plusDays(1))) {
			See vis = see(e, v);
			if (vis == null) {
				continue;
			}
			for (LocalDate d : occurrences(e, from.minusDays(1), to.plusDays(1))) {
				Map<String, Object> it = item(e, d, vis, v, orgZone, myRsvp.get(e.id()));
				LocalDate shown = (LocalDate) it.get("date");
				if (!shown.isBefore(from) && !shown.isAfter(to)) {
					items.add(it);
				}
			}
		}
		holidays(v, from, to, sys.get("HOLIDAY"), items);
		birthdays(v, from, to, sys.get("BIRTHDAY"), sys.get("ANNIVERSARY"), items);
		leave(v, from, to, items);
		items.sort(Comparator.<Map<String, Object>, String>comparing(m -> String.valueOf(m.get("date")))
				.thenComparing(m -> m.get("start") == null ? "" : String.valueOf(m.get("start"))).thenComparing(m -> String.valueOf(m.get("title"))));

		Map<String, Object> out = new LinkedHashMap<>();
		out.put("from", from);
		out.put("to", to);
		out.put("items", items);
		if (withMeta) {
			out.put("me", viewers.describe(v));
			out.put("pending", v.admin() ? jdbc.sql("select count(*) from calendar_event where org_id = :org and status = 'PENDING'")
					.param("org", v.orgId()).query(Long.class).single() : 0L);
			out.put("firstPending", v.admin() ? jdbc.sql("""
					select min(starts_on) from calendar_event where org_id = :org and status = 'PENDING'
					   and coalesce(repeat_until, starts_on) >= current_date""").param("org", v.orgId()).query(LocalDate.class).optional().orElse(null) : null);
		}
		return out;
	}

	Map<String, Object> item(Ev e, LocalDate d, See vis, Viewer v, ZoneId orgZone, String myRsvp) {
		boolean busy = vis == See.BUSY;
		LocalDate date = d;
		LocalTime start = e.start();
		LocalTime end = e.end();
		String tzNote = null;
		ZoneId ez = zoneOf(e, orgZone);
		if (start != null && !ez.equals(v.zone())) {
			ZonedDateTime s = ZonedDateTime.of(d, start, ez).withZoneSameInstant(v.zone());
			if (s.toLocalDate().equals(d) && s.toLocalTime().equals(start) && (end == null
					|| ZonedDateTime.of(d, end, ez).withZoneSameInstant(v.zone()).toLocalTime().equals(end))) {
				// same wall-clock time in both zones: nothing to explain
			}
			else {
				date = s.toLocalDate();
				tzNote = HM.format(start) + (end != null ? "–" + HM.format(end) : "") + " " + zoneShort(ez, d);
				start = s.toLocalTime();
				if (end != null) {
					ZonedDateTime x = ZonedDateTime.of(d, end, ez).withZoneSameInstant(v.zone());
					end = x.toLocalDate().equals(date) && x.toLocalTime().isAfter(start) ? x.toLocalTime() : LocalTime.of(23, 59);
				}
			}
		}
		Map<String, Object> it = new LinkedHashMap<>();
		it.put("key", e.id() + "@" + d);
		it.put("id", e.id());
		it.put("source", "EVENT");
		it.put("occurrence", d);
		it.put("date", date);
		it.put("start", start == null ? null : HM.format(start));
		it.put("end", end == null ? null : HM.format(end));
		it.put("layer", e.layer().name());
		it.put("vis", vis.name());
		it.put("status", e.status());
		it.put("repeat", e.repeat());
		it.put("tzNote", busy ? null : tzNote);
		if (busy) {
			it.put("title", "Busy");
			it.put("type", Map.of("key", "BUSY", "name", "Busy", "fg", "#475569", "bg", "#F1F5F9"));
			it.put("organiser", null);
			return it;
		}
		it.put("title", e.title());
		it.put("type", Map.of("key", e.typeKey(), "name", e.typeName(), "fg", e.fg(), "bg", e.bg()));
		it.put("where", e.where());
		it.put("room", e.roomId() == null ? null : room(e));
		it.put("until", e.until());
		it.put("private", e.priv());
		it.put("sensitive", e.sensitive());
		it.put("remind", e.remind());
		it.put("organiser", Map.of("id", e.organiserId(), "name", e.organiserName()));
		it.put("audienceLabel", audienceLabel(e));
		it.put("canEdit", canEdit(e, v));
		it.put("canRsvp", rsvpable(e) && !e.organiserId().equals(v.id()));
		it.put("myRsvp", myRsvp);
		return it;
	}

	static Map<String, Object> room(Ev e) {
		Map<String, Object> r = new LinkedHashMap<>();
		r.put("id", e.roomId());
		r.put("name", e.roomName());
		r.put("capacity", e.roomCapacity());
		r.put("location", e.roomLocation());
		return r;
	}

	String audienceLabel(Ev e) {
		return switch (e.audience()) {
			case "ALL" -> "Everyone";
			case "ME" -> "Only the organiser";
			case "TEAM" -> e.audienceRefName() == null ? "Team" : e.audienceRefName() + "’s team";
			case "DEPT" -> (e.audienceRefName() == null ? "A" : e.audienceRefName()) + " department";
			case "LOC" -> (e.audienceRefName() == null ? "An" : e.audienceRefName()) + " office";
			case "PEOPLE" -> e.people().size() + (e.people().size() == 1 ? " person" : " people");
			case "ROLE" -> String.join(", ", roleNames(e.roles()));
			default -> "";
		};
	}

	List<String> roleNames(Collection<String> keys) {
		if (keys.isEmpty()) {
			return List.of();
		}
		Map<String, String> names = new HashMap<>();
		jdbc.sql("select key, name from role where key in (:k)").param("k", keys).query((rs, i) -> names.put(rs.getString(1), rs.getString(2))).list();
		return keys.stream().map(k -> names.getOrDefault(k, k)).distinct().toList();
	}

	Map<String, SysType> systemTypes(UUID orgId) {
		Map<String, SysType> m = new HashMap<>();
		jdbc.sql("select key, name, fg, bg, active from calendar_event_type where org_id = :org and system").param("org", orgId)
				.query((rs, i) -> m.put(rs.getString(1), new SysType(rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5)))).list();
		return m;
	}

	private static Map<String, Object> auto(String key, String source, String layer, LocalDate date, String title, String where, String typeKey,
			String typeName, String fg, String bg) {
		Map<String, Object> it = new LinkedHashMap<>();
		it.put("key", key);
		it.put("id", null);
		it.put("source", source);
		it.put("date", date);
		it.put("start", null);
		it.put("end", null);
		it.put("layer", layer);
		it.put("vis", "FULL");
		it.put("status", "LIVE");
		it.put("title", title);
		it.put("where", where);
		it.put("type", Map.of("key", typeKey, "name", typeName, "fg", fg, "bg", bg));
		it.put("auto", true);
		it.put("canEdit", false);
		it.put("canRsvp", false);
		return it;
	}

	// ---------------------------------------------------------------------------------------------------------------- auto items

	private void holidays(Viewer v, LocalDate from, LocalDate to, SysType t, List<Map<String, Object>> items) {
		if (t == null || !t.active()) {
			return;
		}
		UUID national = jdbc.sql("select id from holiday_calendar where org_id = :org and state is null and active order by name limit 1")
				.param("org", v.orgId()).query(UUID.class).optional().orElse(null);
		UUID own = v.locationId() == null ? null : jdbc.sql("select holiday_calendar_id from location where id = :l").param("l", v.locationId())
				.query(UUID.class).optional().orElse(null);
		UUID cal = own != null ? own : national;
		if (cal == null) {
			return;
		}
		Map<LocalDate, Object[]> byDay = new TreeMap<>();
		List<UUID> cals = national != null && !national.equals(cal) ? List.of(national, cal) : List.of(cal);
		for (UUID c : cals) { // the location's own calendar wins over the national one on the same day
			jdbc.sql("""
					select h.date, h.name, ht.name, ht.day_off from holiday h join holiday_type ht on ht.id = h.type_id
					 where h.calendar_id = :c and h.date between :f and :t""").param("c", c).param("f", from).param("t", to)
					.query((rs, i) -> byDay.put(rs.getObject(1, LocalDate.class), new Object[] { rs.getString(2), rs.getString(3), rs.getBoolean(4) }))
					.list();
		}
		byDay.forEach((d, h) -> {
			Map<String, Object> it = auto("hol@" + d, "HOLIDAY", "COMPANY", d, (String) h[0], h[1] + (Boolean.TRUE.equals(h[2]) ? " · day off" : " · working day"),
					"HOLIDAY", t.name(), t.fg(), t.bg());
			items.add(it);
		});
	}

	/** Every (month, day) between the dates, mapped to the date it falls on (29 Feb shows on 28 Feb in other years). */
	private static Map<MonthDay, List<LocalDate>> monthDays(LocalDate from, LocalDate to) {
		Map<MonthDay, List<LocalDate>> m = new HashMap<>();
		for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
			m.computeIfAbsent(MonthDay.from(d), k -> new ArrayList<>()).add(d);
			if (d.getMonth() == Month.FEBRUARY && d.getDayOfMonth() == 28 && !d.isLeapYear()) {
				m.computeIfAbsent(MonthDay.of(2, 29), k -> new ArrayList<>()).add(d);
			}
		}
		return m;
	}

	private void birthdays(Viewer v, LocalDate from, LocalDate to, SysType bt, SysType at, List<Map<String, Object>> items) {
		Map<MonthDay, List<LocalDate>> days = monthDays(from, to);
		Set<UUID> optedOut = new HashSet<>(jdbc.sql("""
				select employee_id from user_preference where key = 'calendar' and coalesce((value ->> 'bdayOptOut')::boolean, false)""")
				.query(UUID.class).list());
		String scope = v.admin() ? "" : " and (e.id = :me or e.department_id = :dept)";
		if (bt != null && bt.active()) {
			jdbc.sql("""
					select e.id, e.first_name, e.display_name, d.name as dept, v.value #>> '{}' as dob
					  from employee e
					  join config_field f on f.org_id = e.org_id and f.area_key = 'employee_profile' and f.key = 'dob'
					  join config_field_value v on v.field_id = f.id and v.record_id = e.id and v.area_key = 'employee_profile'
					  left join department d on d.id = e.department_id
					 where e.org_id = :org and e.status <> 'EXITED' and e.archived_at is null""" + scope)
					.param("org", v.orgId()).param("me", v.id()).param("dept", v.departmentId(), java.sql.Types.OTHER).query((rs, i) -> {
						UUID id = rs.getObject("id", UUID.class);
						LocalDate dob = parse(rs.getString("dob"));
						if (dob == null || (optedOut.contains(id) && !id.equals(v.id()))) {
							return 0;
						}
						for (LocalDate d : days.getOrDefault(MonthDay.from(dob), List.of())) {
							boolean me = id.equals(v.id());
							items.add(auto("bday-" + id + "@" + d, "BIRTHDAY", "DEPT", d, me ? "Your birthday" : rs.getString("first_name") + "’s birthday",
									rs.getString("display_name") + (rs.getString("dept") != null ? " · " + rs.getString("dept") : ""), "BIRTHDAY", bt.name(), bt.fg(), bt.bg()));
						}
						return 0;
					}).list();
		}
		if (at != null && at.active()) {
			jdbc.sql("""
					select e.id, e.first_name, e.display_name, e.joining_date from employee e
					 where e.org_id = :org and e.status <> 'EXITED' and e.archived_at is null""" + scope)
					.param("org", v.orgId()).param("me", v.id()).param("dept", v.departmentId(), java.sql.Types.OTHER).query((rs, i) -> {
						UUID id = rs.getObject("id", UUID.class);
						LocalDate doj = rs.getObject("joining_date", LocalDate.class);
						for (LocalDate d : days.getOrDefault(MonthDay.from(doj), List.of())) {
							int y = d.getYear() - doj.getYear();
							if (y > 0) {
								items.add(auto("anniv-" + id + "@" + d, "ANNIVERSARY", "DEPT", d,
										(id.equals(v.id()) ? "You" : rs.getString("first_name")) + " · " + y + " yr" + (y > 1 ? "s" : ""),
										rs.getString("display_name") + " joined " + DMY.format(doj), "ANNIVERSARY", at.name(), at.fg(), at.bg()));
							}
						}
						return 0;
					}).list();
		}
	}

	private static LocalDate parse(String s) {
		try {
			return s == null || s.length() < 10 ? null : LocalDate.parse(s.substring(0, 10));
		}
		catch (RuntimeException e) {
			return null;
		}
	}

	private record Lv(UUID emp, String first, String name, UUID mgr, UUID dept, String code, String type, LocalDate from, LocalDate to, boolean half,
			String status) {
	}

	private void leave(Viewer v, LocalDate from, LocalDate to, List<Map<String, Object>> items) {
		List<Lv> all = jdbc.sql("""
				select r.employee_id, e.first_name, e.display_name, e.manager_id, e.department_id, t.code, t.name, r.from_date, r.to_date,
				       r.half_day, r.status
				  from leave_request r join employee e on e.id = r.employee_id join leave_type t on t.id = r.type_id
				 where r.org_id = :org and r.status in ('APPROVED', 'PENDING') and r.from_date <= :to and r.to_date >= :from""")
				.param("org", v.orgId()).param("from", from).param("to", to)
				.query((rs, i) -> new Lv(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getObject(4, UUID.class),
						rs.getObject(5, UUID.class), rs.getString(6), rs.getString(7), rs.getObject(8, LocalDate.class), rs.getObject(9, LocalDate.class),
						rs.getBoolean(10), rs.getString(11)))
				.list();
		String fg = "#15803D";
		String bg = "#DCFCE7";
		Map<LocalDate, List<Lv>> others = new TreeMap<>();
		for (Lv r : all) {
			boolean own = r.emp().equals(v.id());
			boolean pending = "PENDING".equals(r.status());
			String range = r.from().equals(r.to()) ? DMY.format(r.from()) + (r.half() ? " · half day" : "")
					: DMY.format(r.from()) + " – " + DMY.format(r.to());
			for (LocalDate d = r.from().isBefore(from) ? from : r.from(); !d.isAfter(r.to()) && !d.isAfter(to); d = d.plusDays(1)) {
				if (own) {
					items.add(auto("lv-" + r.emp() + "@" + d, "LEAVE", "MY", d, "You · " + r.code() + (pending ? " (pending)" : ""), r.type() + " · " + range,
							"LEAVE", "Leave", fg, bg));
					continue;
				}
				if (v.admin()) {
					if (!pending) {
						others.computeIfAbsent(d, k -> new ArrayList<>()).add(r);
					}
					continue;
				}
				boolean report = v.id().equals(r.mgr());
				if (report) {
					items.add(auto("lv-" + r.emp() + "@" + d, "LEAVE", "TEAM", d, r.first() + " · " + r.code() + (pending ? " (pending)" : ""),
							r.name() + " · " + range, "LEAVE", "Leave", fg, bg));
					continue;
				}
				boolean mate = (v.managerId() != null && v.managerId().equals(r.mgr())) || (v.departmentId() != null && v.departmentId().equals(r.dept()));
				if (mate && !pending) {
					items.add(auto("lv-" + r.emp() + "@" + d, "LEAVE", "TEAM", d, r.first() + " · On leave", r.name() + " · " + range, "LEAVE", "Leave", fg, bg));
				}
			}
		}
		others.forEach((d, list) -> {
			if (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
				return;
			}
			String who = String.join(", ", list.stream().limit(4).map(r -> r.first() + " (" + r.code() + ")").toList())
					+ (list.size() > 4 ? " +" + (list.size() - 4) : "");
			items.add(auto("lv@" + d, "LEAVE", "TEAM", d, list.size() + " on leave", who, "LEAVE", "Leave", fg, bg));
		});
	}

	// ---------------------------------------------------------------------------------------------------------------- one event

	@Transactional(readOnly = true)
	public Map<String, Object> detail(UUID id, LocalDate date) {
		Viewer v = viewers.current();
		Ev e = load(v.orgId(), id);
		See vis = see(e, v);
		if (vis == null) {
			throw new NotFoundException("Event not found");
		}
		LocalDate d = date != null && occursOn(e, date) ? date : e.date();
		String my = jdbc.sql("select answer from calendar_event_rsvp where event_id = :e and employee_id = :me").param("e", id).param("me", v.id())
				.query(String.class).optional().orElse(null);
		Map<String, Object> out = item(e, d, vis, v, viewers.orgZone(v.orgId()), my);
		if (vis == See.BUSY) {
			return out;
		}
		out.put("notes", e.notes());
		out.put("version", e.version());
		out.put("timeZone", zoneOf(e, viewers.orgZone(v.orgId())).getId());
		out.put("viewerZone", v.zone().getId());
		out.put("startsOn", e.date());
		out.put("startTime", e.start() == null ? null : HM.format(e.start()));
		out.put("endTime", e.end() == null ? null : HM.format(e.end()));
		out.put("typeId", e.typeId());
		Map<String, Object> aud = new LinkedHashMap<>();
		aud.put("type", e.audience());
		aud.put("ref", e.audienceRef());
		aud.put("roles", e.roles());
		aud.put("people", e.people().isEmpty() ? List.of() : jdbc.sql("select id, display_name from employee where id in (:ids) order by display_name")
				.param("ids", e.people()).query((rs, i) -> Map.of("id", rs.getObject(1, UUID.class), "name", rs.getString(2))).list());
		out.put("audience", aud);
		List<UUID> inv = invitees(v.orgId(), e);
		out.put("invitedCount", inv.size());
		boolean organiser = e.organiserId().equals(v.id());
		if (rsvpable(e) && (organiser || v.admin())) {
			Map<String, Long> c = new LinkedHashMap<>(Map.of("YES", 0L, "MAYBE", 0L, "NO", 0L));
			Set<UUID> invSet = new HashSet<>(inv);
			List<Map<String, Object>> answers = new ArrayList<>();
			jdbc.sql("select r.employee_id, r.answer, e.display_name from calendar_event_rsvp r join employee e on e.id = r.employee_id where r.event_id = :e order by e.display_name")
					.param("e", id).query((rs, i) -> {
						UUID who = rs.getObject(1, UUID.class);
						if (invSet.contains(who)) {
							c.merge(rs.getString(2), 1L, Long::sum);
							answers.add(Map.of("id", who, "name", rs.getString(3), "answer", rs.getString(2)));
						}
						return 0;
					}).list();
			long answered = c.values().stream().mapToLong(Long::longValue).sum();
			int others = inv.contains(e.organiserId()) ? inv.size() - 1 : inv.size();
			out.put("responses", Map.of("yes", c.get("YES"), "maybe", c.get("MAYBE"), "no", c.get("NO"), "none", Math.max(0, others - answered), "list", answers));
		}
		out.put("canApprove", v.admin() && "PENDING".equals(e.status()));
		out.put("canDelete", canEdit(e, v));
		out.put("log", jdbc.sql("""
				select l.at, l.action, coalesce(e.display_name, 'System') from calendar_event_log l left join employee e on e.id = l.by
				 where l.event_id = :e order by l.at desc""").param("e", id)
				.query((rs, i) -> Map.of("at", rs.getObject(1, java.time.OffsetDateTime.class), "action", rs.getString(2), "by", rs.getString(3))).list());
		return out;
	}

	/** Active employees an event is for (the organiser included). */
	List<UUID> invitees(UUID org, Ev e) {
		return invitees(org, e.audience(), e.audienceRef(), e.roles(), e.people(), e.organiserId());
	}

	List<UUID> invitees(UUID org, String audience, UUID ref, List<String> roles, List<UUID> people, UUID organiser) {
		String where = switch (audience) {
			case "ALL" -> "true";
			case "ME" -> "e.id = :organiser";
			case "TEAM" -> "(e.id = :ref or e.manager_id = :ref)";
			case "DEPT" -> "e.department_id = :ref";
			case "LOC" -> "e.location_id = :ref";
			case "PEOPLE" -> "e.id = any(cast(:people as uuid[]))";
			case "ROLE" -> """
					e.id in (select u.employee_id from app_user u join user_role ur on ur.user_id = u.id join role r on r.id = ur.role_id
					          where u.status = 'ACTIVE' and (r.key = any(cast(:roles as text[])) or (r.key = 'super_admin' and 'hr_admin' = any(cast(:roles as text[])))))""";
			default -> "false";
		};
		List<UUID> ids = new ArrayList<>(jdbc.sql("select e.id from employee e where e.status <> 'EXITED' and e.archived_at is null and e.org_id = :o and " + where)
				.param("o", org).param("organiser", organiser).param("ref", ref, java.sql.Types.OTHER)
				.param("people", arr(people)).param("roles", arr(roles)).query(UUID.class).list());
		if (!ids.contains(organiser)) {
			ids.add(organiser);
		}
		return ids;
	}

	// ---------------------------------------------------------------------------------------------------------------- iCalendar

	@Transactional(readOnly = true)
	public String ics(LocalDate from, LocalDate to) {
		return ics(feed(viewers.current(), from, to, false));
	}

	@Transactional(readOnly = true)
	public String icsOne(UUID id, LocalDate date) {
		Map<String, Object> one = detail(id, date);
		return ics(Map.of("items", List.of(one)));
	}

	/** The private subscription link: the next 6 months and the last month, for the person the token belongs to. */
	@Transactional(readOnly = true)
	public String subscription(String token) {
		Viewer v = viewers.byToken(token);
		LocalDate today = LocalDate.now(v.zone());
		List<Map<String, Object>> all = new ArrayList<>();
		for (LocalDate f = today.minusDays(30); f.isBefore(today.plusDays(183)); f = f.plusDays(MAX_DAYS + 1)) {
			LocalDate t = f.plusDays(MAX_DAYS);
			@SuppressWarnings("unchecked")
			List<Map<String, Object>> part = (List<Map<String, Object>>) feed(v, f, t, false).get("items");
			all.addAll(part);
		}
		return ics(Map.of("items", all));
	}

	@SuppressWarnings("unchecked")
	static String ics(Map<String, Object> feed) {
		DateTimeFormatter stamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
		String now = stamp.format(ZonedDateTime.now(ZoneId.of("UTC")));
		StringBuilder b = new StringBuilder("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Ivoyant//iHRMS//EN\r\nCALSCALE:GREGORIAN\r\nX-WR-CALNAME:iHRMS\r\n");
		for (Map<String, Object> it : (List<Map<String, Object>>) feed.get("items")) {
			String date = String.valueOf(it.get("date")).replace("-", "");
			String start = (String) it.get("start");
			String end = (String) it.get("end");
			b.append("BEGIN:VEVENT\r\n");
			b.append("UID:").append(String.valueOf(it.get("key")).replaceAll("[^\\w@.-]", "")).append("@ihrms.ivoyant.com\r\n");
			b.append("DTSTAMP:").append(now).append("\r\n");
			if (start == null) {
				b.append("DTSTART;VALUE=DATE:").append(date).append("\r\n");
				b.append("DTEND;VALUE=DATE:").append(LocalDate.parse(String.valueOf(it.get("date"))).plusDays(1).toString().replace("-", "")).append("\r\n");
			}
			else {
				b.append("DTSTART:").append(date).append('T').append(start.replace(":", "")).append("00\r\n");
				String e = end != null ? end : LocalTime.parse(start).plusMinutes(30).format(HM);
				b.append("DTEND:").append(date).append('T').append(e.replace(":", "")).append("00\r\n");
			}
			b.append("SUMMARY:").append(esc(String.valueOf(it.get("title")))).append("\r\n");
			Object where = it.get("room") instanceof Map<?, ?> r ? r.get("name") + (it.get("where") != null ? " · " + it.get("where") : "") : it.get("where");
			if (where != null && !"BUSY".equals(it.get("vis"))) {
				b.append("LOCATION:").append(esc(String.valueOf(where))).append("\r\n");
			}
			if (it.get("notes") != null) {
				b.append("DESCRIPTION:").append(esc(String.valueOf(it.get("notes")))).append("\r\n");
			}
			if ("BUSY".equals(it.get("vis"))) {
				b.append("CLASS:PRIVATE\r\n");
			}
			b.append("END:VEVENT\r\n");
		}
		return b.append("END:VCALENDAR\r\n").toString();
	}

	/** A Postgres array literal, bound as text and cast in SQL. */
	static String arr(Collection<?> xs) {
		return "{" + String.join(",", xs.stream().map(x -> "\"" + String.valueOf(x).replace("\\", "").replace("\"", "") + "\"").toList()) + "}";
	}

	private static String esc(String s) {
		return s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\r", "").replace("\n", "\\n");
	}
}
