package com.ivoyant.hrms.calendar.service;

import com.ivoyant.hrms.calendar.CalendarTypeUsage;
import com.ivoyant.hrms.calendar.service.CalendarFeedService.Ev;
import com.ivoyant.hrms.calendar.service.CalendarLayer.Perm;
import com.ivoyant.hrms.calendar.service.CalendarLayer.See;
import com.ivoyant.hrms.calendar.service.CalendarLayer.Viewer;
import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.ForbiddenException;
import com.ivoyant.hrms.common.web.InUseException;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.common.web.StaleException;
import com.ivoyant.hrms.common.web.UnprocessableException;
import com.ivoyant.hrms.notification.Notifier;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Adds, edits and removes calendar events; HR approval of company and office events added by managers; RSVPs; the warnings a form
 * shows before saving (holiday, weekend, invitees on leave, overlaps) and the one error it can't save past (a room already booked);
 * meeting rooms. Also counts the events of each type for Configuration → Calendar event types.
 */
@Service
public class CalendarEventService implements CalendarTypeUsage {

	private static final String ENTITY = "CalendarEvent";
	static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH);
	private static final DateTimeFormatter DAY = DAY_FMT;
	private static final Set<String> REPEATS = Set.of("NONE", "WEEKLY", "MONTHLY");

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final CalendarViewers viewers;
	private final CalendarFeedService feed;
	private final Notifier notifier;
	private final Audit audit;

	public CalendarEventService(JdbcClient jdbc, CurrentUser currentUser, CalendarViewers viewers, CalendarFeedService feed, Notifier notifier,
			Audit audit) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.viewers = viewers;
		this.feed = feed;
		this.notifier = notifier;
		this.audit = audit;
	}

	@Override
	public long eventsOfType(UUID typeId) {
		return jdbc.sql("select count(*) from calendar_event where type_id = :t").param("t", typeId).query(Long.class).single();
	}

	// ---------------------------------------------------------------------------------------------------------------- form options

	/** What the event form offers the caller: layers they may add to, who each layer can be for, event types and rooms. */
	@Transactional(readOnly = true)
	public Map<String, Object> options() {
		Viewer v = viewers.current();
		Map<String, Object> out = new LinkedHashMap<>();
		List<Map<String, Object>> layers = new ArrayList<>();
		for (CalendarLayer l : CalendarLayer.values()) {
			Perm p = v.perm(l);
			if (!p.add()) {
				continue;
			}
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("key", l.name());
			m.put("name", l.label);
			m.put("color", l.color);
			m.put("needsApproval", p.needsApproval());
			m.put("audiences", audiences(v, l).entrySet().stream().map(e -> Map.of("value", e.getKey(), "label", e.getValue())).toList());
			layers.add(m);
		}
		out.put("layers", layers);
		out.put("types", jdbc.sql("""
				select id, key, name, fg, bg, who from calendar_event_type where org_id = :org and active and not system
				   and (who = 'ALL' or :admin) order by sort, name""").param("org", v.orgId()).param("admin", v.admin())
				.query((rs, i) -> Map.of("id", rs.getObject(1, UUID.class), "key", rs.getString(2), "name", rs.getString(3), "fg", rs.getString(4),
						"bg", rs.getString(5), "who", rs.getString(6)))
				.list());
		out.put("rooms", rooms(v.orgId(), true));
		out.put("remind", viewers.settings(v.id()).get("remind"));
		out.put("timeZone", v.zone().getId());
		return out;
	}

	/** Audience value ("ALL", "ME", "PEOPLE", "TEAM:id", "DEPT:id", "LOC:id", "ROLE:a,b") → label, for one layer. */
	Map<String, String> audiences(Viewer v, CalendarLayer l) {
		Map<String, String> m = new LinkedHashMap<>();
		switch (l) {
			case MY -> m.put("ME", "Only me");
			case TEAM -> {
				UUID team = v.manager() ? v.id() : v.managerId();
				if (team != null) {
					String n = jdbc.sql("select first_name from employee where id = :id").param("id", team).query(String.class).single();
					m.put("TEAM:" + team, team.equals(v.id()) ? "My team" : n + "’s team");
				}
			}
			case DEPT -> jdbc.sql("select id, name from department where org_id = :org and active" + (v.admin() ? "" : " and id = :d") + " order by name")
					.param("org", v.orgId()).param("d", v.departmentId(), java.sql.Types.OTHER)
					.query((rs, i) -> m.put("DEPT:" + rs.getObject(1, UUID.class), rs.getString(2) + " department")).list();
			case LOC -> jdbc.sql("select id, name from location where org_id = :org and active" + (v.admin() ? "" : " and id = :l") + " order by name")
					.param("org", v.orgId()).param("l", v.locationId(), java.sql.Types.OTHER)
					.query((rs, i) -> m.put("LOC:" + rs.getObject(1, UUID.class), rs.getString(2) + " office")).list();
			case COMPANY -> {
				m.put("ALL", "Everyone");
				jdbc.sql("select key, name from role where org_id = :org and key not in ('employee', 'super_admin') order by name").param("org", v.orgId())
						.query((rs, i) -> m.put("ROLE:" + rs.getString(1), rs.getString(2) + " only")).list();
			}
			case HR -> m.put("ROLE:hr_admin", "HR admins");
			case REC -> m.put("ROLE:hr_admin,hr_ops", "HR admins and HR operations");
			case FIN -> m.put("ROLE:finance,hr_admin", "Finance and HR");
		}
		if (l != CalendarLayer.MY) {
			m.put("PEOPLE", "Specific people");
		}
		return m;
	}

	// ---------------------------------------------------------------------------------------------------------------- reading a form

	record Input(CalendarLayer layer, UUID typeId, String title, LocalDate date, LocalTime start, LocalTime end, String tz, String where, UUID roomId,
			String audience, UUID audienceRef, List<String> roles, List<UUID> people, boolean priv, boolean sensitive, String repeat, LocalDate until,
			String remind, String notes) {
	}

	private Input read(Map<String, Object> b, Viewer v, List<ErrorItem> errors) {
		CalendarLayer layer = CalendarLayer.of(b.get("layer"));
		if (layer == null || !v.perm(layer).add()) {
			errors.add(ErrorItem.field("layer", "LAYER", "You can’t add events to that calendar"));
			layer = CalendarLayer.MY;
		}
		String title = str(b.get("title"));
		if (title == null) {
			errors.add(ErrorItem.field("title", "REQUIRED", "Add a title"));
		}
		else if (title.length() > 200) {
			errors.add(ErrorItem.field("title", "TOO_LONG", "Keep the title under 200 characters"));
		}
		LocalDate date = date(b.get("date"));
		if (date == null) {
			errors.add(ErrorItem.field("date", "REQUIRED", "Pick a date"));
		}
		boolean allDay = Boolean.parseBoolean(String.valueOf(b.get("allDay"))) || str(b.get("start")) == null;
		LocalTime start = allDay ? null : time(b.get("start"));
		LocalTime end = allDay ? null : time(b.get("end"));
		if (!allDay && start == null) {
			errors.add(ErrorItem.field("start", "INVALID", "Pick a start time"));
		}
		if (start != null && end != null && !end.isAfter(start)) {
			errors.add(ErrorItem.field("end", "END_BEFORE_START", "End time must be after start"));
		}
		String tz = str(b.get("timeZone"));
		if (tz != null) {
			try {
				tz = ZoneId.of(tz).getId();
				if (tz.equals(v.zone().getId())) {
					tz = null;
				}
			}
			catch (RuntimeException e) {
				errors.add(ErrorItem.field("timeZone", "INVALID", "Unknown time zone"));
				tz = null;
			}
		}
		UUID typeId = uuid(b.get("typeId"));
		if (typeId == null || jdbc.sql("select count(*) from calendar_event_type where id = :t and org_id = :org and active and not system and (who = 'ALL' or :admin)")
				.param("t", typeId).param("org", v.orgId()).param("admin", v.admin()).query(Long.class).single() == 0) {
			errors.add(ErrorItem.field("typeId", "TYPE", "Pick an event type"));
		}
		UUID roomId = allDay ? null : uuid(b.get("roomId"));
		if (roomId != null && jdbc.sql("select count(*) from calendar_room where id = :r and org_id = :org and active").param("r", roomId).param("org", v.orgId())
				.query(Long.class).single() == 0) {
			errors.add(ErrorItem.field("roomId", "ROOM", "That room isn’t available"));
		}
		String aud = str(b.get("audience"));
		Map<String, String> allowed = audiences(v, layer);
		if (aud == null || !allowed.containsKey(aud)) {
			errors.add(ErrorItem.field("audience", "AUDIENCE", "Pick who the event is for"));
			aud = allowed.keySet().iterator().next();
		}
		String kind = aud.contains(":") ? aud.substring(0, aud.indexOf(':')) : aud;
		String val = aud.contains(":") ? aud.substring(aud.indexOf(':') + 1) : null;
		List<UUID> people = new ArrayList<>();
		if ("PEOPLE".equals(kind)) {
			Set<UUID> ids = new LinkedHashSet<>();
			if (b.get("people") instanceof Collection<?> c) {
				c.forEach(x -> {
					UUID id = uuid(x);
					if (id != null) {
						ids.add(id);
					}
				});
			}
			ids.remove(v.id());
			if (!ids.isEmpty()) {
				people.addAll(jdbc.sql("select id from employee where org_id = :org and status <> 'EXITED' and id = any(cast(:ids as uuid[]))").param("org", v.orgId())
						.param("ids", CalendarFeedService.arr(ids)).query(UUID.class).list());
			}
			if (people.isEmpty()) {
				errors.add(ErrorItem.field("people", "REQUIRED", "Add at least one person"));
			}
			people.add(0, v.id());
		}
		String repeat = String.valueOf(b.getOrDefault("repeat", "NONE")).toUpperCase();
		if (!REPEATS.contains(repeat)) {
			errors.add(ErrorItem.field("repeat", "INVALID", "Pick how it repeats"));
			repeat = "NONE";
		}
		LocalDate until = "NONE".equals(repeat) ? null : date(b.get("until"));
		if (until != null && date != null && until.isBefore(date)) {
			errors.add(ErrorItem.field("until", "UNTIL_BEFORE_START", "Repeat end must be after the start date"));
		}
		String remind = String.valueOf(b.getOrDefault("remind", "NONE")).toUpperCase();
		if (!CalendarViewers.REMINDERS.contains(remind)) {
			remind = "NONE";
		}
		String notes = str(b.get("notes"));
		if (notes != null && notes.length() > 4000) {
			errors.add(ErrorItem.field("notes", "TOO_LONG", "Keep notes under 4,000 characters"));
		}
		boolean priv = layer == CalendarLayer.MY || Boolean.parseBoolean(String.valueOf(b.get("private")));
		boolean sensitive = layer == CalendarLayer.HR && Boolean.parseBoolean(String.valueOf(b.get("sensitive")));
		return new Input(layer, typeId, title, date, start, end, tz, str(b.get("where")), roomId, kind,
				"TEAM".equals(kind) || "DEPT".equals(kind) || "LOC".equals(kind) ? uuid(val) : null,
				"ROLE".equals(kind) && val != null ? List.of(val.split(",")) : List.of(), people, priv, sensitive, repeat, until, remind, notes);
	}

	// ---------------------------------------------------------------------------------------------------------------- warnings

	/** Errors (can't save) and warnings (can save) for a form, before saving. {@code editId} is the event being edited. */
	@Transactional(readOnly = true)
	public Map<String, Object> check(Map<String, Object> body) {
		Viewer v = viewers.current();
		List<ErrorItem> errors = new ArrayList<>();
		Input in = read(body, v, errors);
		UUID editId = uuid(body.get("editId"));
		List<String> warnings = new ArrayList<>();
		String roomError = in.date() == null ? null : roomClash(v, in, editId);
		if (in.date() != null) {
			@SuppressWarnings("unchecked")
			List<Map<String, Object>> day = (List<Map<String, Object>>) feed.feed(v, in.date(), in.date(), false).get("items");
			day.stream().filter(it -> "HOLIDAY".equals(it.get("source"))).findFirst().ifPresent(h -> warnings.add("Company holiday · " + h.get("title")));
			if (in.date().getDayOfWeek() == DayOfWeek.SATURDAY || in.date().getDayOfWeek() == DayOfWeek.SUNDAY) {
				warnings.add("Falls on a weekend");
			}
			if (!"ME".equals(in.audience())) {
				List<UUID> inv = feed.invitees(v.orgId(), in.audience(), in.audienceRef(), in.roles(), in.people(), v.id());
				List<String> away = jdbc.sql("""
						select distinct e.first_name from leave_request r join employee e on e.id = r.employee_id
						 where r.status in ('APPROVED', 'PENDING') and r.from_date <= :d and r.to_date >= :d and r.employee_id = any(cast(:ids as uuid[]))
						 order by 1""").param("d", in.date()).param("ids", CalendarFeedService.arr(inv)).query(String.class).list();
				if (!away.isEmpty()) {
					warnings.add(away.size() + (away.size() == 1 ? " invitee is" : " invitees are") + " on leave"
							+ (away.size() <= 3 ? " · " + String.join(", ", away) : ""));
				}
			}
			if (in.start() != null && in.end() != null) {
				day.stream().filter(it -> it.get("id") != null && !String.valueOf(it.get("id")).equals(String.valueOf(editId)) && it.get("start") != null
						&& it.get("end") != null && !"REJECTED".equals(it.get("status")) && mineOrInvited(v, (UUID) it.get("id"))
						&& LocalTime.parse((String) it.get("start")).isBefore(in.end()) && in.start().isBefore(LocalTime.parse((String) it.get("end"))))
						.findFirst().ifPresent(it -> warnings.add("Overlaps with " + ("BUSY".equals(it.get("vis")) ? "another event" : "“" + it.get("title") + "”")
								+ " " + it.get("start") + "–" + it.get("end")));
			}
		}
		List<Map<String, String>> errs = new ArrayList<>(errors.stream().map(e -> Map.of("field", String.valueOf(e.fieldKey()), "message", e.message())).toList());
		if (roomError != null) {
			errs.add(Map.of("field", "roomId", "message", roomError));
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("errors", errs);
		out.put("warnings", warnings);
		out.put("needsApproval", v.perm(in.layer()).needsApproval());
		return out;
	}

	private boolean mineOrInvited(Viewer v, UUID eventId) {
		return jdbc.sql("""
				select count(*) from calendar_event ev where ev.id = :id
				   and (ev.organiser_id = :me or exists (select 1 from calendar_event_person p where p.event_id = ev.id and p.employee_id = :me))""")
				.param("id", eventId).param("me", v.id()).query(Long.class).single() > 0;
	}

	/** "Board room is booked 10:00–11:00 · Title" when another event holds the room at that time on that day. */
	private String roomClash(Viewer v, Input in, UUID editId) {
		if (in.roomId() == null || in.start() == null) {
			return null;
		}
		LocalTime end = in.end() != null ? in.end() : in.start().plusMinutes(30);
		for (Ev e : feed.overlapping(v.orgId(), in.date(), in.date())) {
			if (!in.roomId().equals(e.roomId()) || e.id().equals(editId) || "REJECTED".equals(e.status()) || e.start() == null
					|| !CalendarFeedService.occursOn(e, in.date())) {
				continue;
			}
			LocalTime eEnd = e.end() != null ? e.end() : e.start().plusMinutes(30);
			if (e.start().isBefore(end) && in.start().isBefore(eEnd)) {
				return e.roomName() + " is booked " + e.start() + "–" + eEnd + (CalendarFeedService.see(e, v) == See.FULL ? " · " + e.title() : "");
			}
		}
		return null;
	}

	// ---------------------------------------------------------------------------------------------------------------- save

	@Transactional
	public Map<String, Object> create(Map<String, Object> body) {
		Viewer v = viewers.current();
		List<ErrorItem> errors = new ArrayList<>();
		Input in = read(body, v, errors);
		if (errors.isEmpty()) {
			String clash = roomClash(v, in, null);
			if (clash != null) {
				errors.add(ErrorItem.field("roomId", "ROOM_BOOKED", clash));
			}
		}
		if (!errors.isEmpty()) {
			throw new UnprocessableException(errors);
		}
		boolean pending = v.perm(in.layer()).needsApproval();
		UUID id = jdbc.sql("""
				insert into calendar_event (org_id, layer, type_id, title, starts_on, start_time, end_time, time_zone, where_text, room_id, audience,
				                            audience_ref, audience_roles, private, sensitive, repeat, repeat_until, remind, notes, status, organiser_id, updated_by)
				values (:org, :layer, :type, :title, :date, :start, :end, :tz, :where, :room, :aud, :ref, cast(:roles as text[]), :priv, :sens, :repeat, :until, :remind,
				        :notes, :status, :me, :me) returning id""")
				.param("org", v.orgId()).param("status", pending ? "PENDING" : "LIVE").param("me", v.id()).params(params(in)).query(UUID.class).single();
		people(id, in.people());
		log(id, v.id(), pending ? "Submitted for HR approval" : "Created");
		audit.publish("calendar", ENTITY, id, "ADDED", null, Map.of("detail", in.layer().label + " · " + in.title()));
		Ev e = feed.load(v.orgId(), id);
		if (pending) {
			notifier.inApp(admins(v.orgId()), "CALENDAR", "Event waiting for approval", v.name() + " added “" + in.title() + "” on " + DAY.format(in.date()),
					link(e));
		}
		else {
			tell(v, e, "New event: " + in.title(), when(e) + (e.where() != null ? " · " + e.where() : ""));
		}
		return feed.detail(id, in.date());
	}

	private static Map<String, Object> params(Input in) {
		Map<String, Object> p = new LinkedHashMap<>();
		p.put("layer", in.layer().name());
		p.put("type", in.typeId());
		p.put("title", in.title());
		p.put("date", in.date());
		p.put("start", in.start());
		p.put("end", in.end());
		p.put("tz", in.tz());
		p.put("where", in.where());
		p.put("room", in.roomId());
		p.put("aud", in.audience());
		p.put("ref", in.audienceRef());
		p.put("roles", CalendarFeedService.arr(in.roles()));
		p.put("priv", in.priv());
		p.put("sens", in.sensitive());
		p.put("repeat", in.repeat());
		p.put("until", in.until());
		p.put("remind", in.remind());
		p.put("notes", in.notes());
		return p;
	}

	private void people(UUID id, List<UUID> people) {
		jdbc.sql("delete from calendar_event_person where event_id = :id").param("id", id).update();
		for (UUID p : people) {
			jdbc.sql("insert into calendar_event_person (event_id, employee_id) values (:id, :p) on conflict do nothing").param("id", id).param("p", p).update();
		}
	}

	private void log(UUID id, UUID by, String action) {
		jdbc.sql("insert into calendar_event_log (event_id, by, action) values (:id, :by, :a)").param("id", id).param("by", by, java.sql.Types.OTHER)
				.param("a", action).update();
	}

	private Ev editable(Viewer v, UUID id, String ifMatch) {
		Ev e = feed.load(v.orgId(), id);
		if (CalendarFeedService.see(e, v) == null) {
			throw new NotFoundException("Event not found");
		}
		if (!CalendarFeedService.canEdit(e, v)) {
			throw new ForbiddenException("You can’t change this event");
		}
		if (ifMatch != null && !ifMatch.isBlank() && !ifMatch.replace("W/", "").replace("\"", "").trim().equals(String.valueOf(e.version()))) {
			throw new StaleException("Someone else changed this event. Reload and try again.");
		}
		return e;
	}

	@Transactional
	public Map<String, Object> update(UUID id, Map<String, Object> body, String ifMatch) {
		Viewer v = viewers.current();
		Ev cur = editable(v, id, ifMatch);
		List<ErrorItem> errors = new ArrayList<>();
		if (!body.containsKey("layer")) {
			body.put("layer", cur.layer().name());
		}
		Input in = read(body, v, errors);
		if (errors.isEmpty()) {
			String clash = roomClash(v, in, id);
			if (clash != null) {
				errors.add(ErrorItem.field("roomId", "ROOM_BOOKED", clash));
			}
		}
		if (!errors.isEmpty()) {
			throw new UnprocessableException(errors);
		}
		boolean approval = v.perm(in.layer()).needsApproval();
		String status = switch (cur.status()) {
			case "REJECTED" -> approval ? "PENDING" : "LIVE";
			case "PENDING" -> approval || v.admin() ? "PENDING" : "LIVE";
			default -> approval && cur.layer() != in.layer() ? "PENDING" : "LIVE";
		};
		jdbc.sql("""
				update calendar_event set layer = :layer, type_id = :type, title = :title, starts_on = :date, start_time = :start, end_time = :end,
				       time_zone = :tz, where_text = :where, room_id = :room, audience = :aud, audience_ref = :ref, audience_roles = cast(:roles as text[]),
				       private = :priv, sensitive = :sens, repeat = :repeat, repeat_until = :until, remind = :remind, notes = :notes, status = :status,
				       updated_at = now(), updated_by = :me, version = version + 1
				 where id = :id""").params(params(in)).param("status", status).param("me", v.id()).param("id", id).update();
		people(id, in.people());
		jdbc.sql("delete from calendar_reminder_sent where event_id = :id and occurs_on >= current_date").param("id", id).update();
		log(id, v.id(), "REJECTED".equals(cur.status()) && "PENDING".equals(status) ? "Edited and sent for approval again" : "Edited");
		audit.publish("calendar", ENTITY, id, "UPDATED", null, Map.of("detail", in.title()));
		Ev e = feed.load(v.orgId(), id);
		if ("LIVE".equals(status)) {
			tell(v, e, "Event updated: " + in.title(), when(e) + (e.where() != null ? " · " + e.where() : ""));
		}
		return feed.detail(id, in.date());
	}

	@Transactional
	public void delete(UUID id) {
		Viewer v = viewers.current();
		Ev e = editable(v, id, null);
		if ("LIVE".equals(e.status())) {
			tell(v, e, "Event cancelled: " + e.title(), (e.repeats() ? "Every occurrence · " : "") + when(e));
		}
		jdbc.sql("delete from calendar_event where id = :id").param("id", id).update();
		audit.publish("calendar", ENTITY, id, "DELETED", null, Map.of("detail", e.title()));
	}

	// ---------------------------------------------------------------------------------------------------------------- approval & RSVP

	@Transactional
	public Map<String, Object> decide(UUID id, boolean approve, String note) {
		Viewer v = viewers.current();
		if (!v.admin()) {
			throw new ForbiddenException("Only HR can approve calendar events");
		}
		Ev e = feed.load(v.orgId(), id);
		if (!"PENDING".equals(e.status())) {
			throw new UnprocessableException(List.of(ErrorItem.field(null, "NOT_PENDING", "This event isn’t waiting for approval")));
		}
		jdbc.sql("update calendar_event set status = :s, updated_at = now(), updated_by = :me, version = version + 1 where id = :id")
				.param("s", approve ? "LIVE" : "REJECTED").param("me", v.id()).param("id", id).update();
		String n = note == null || note.isBlank() ? "" : " · " + note.trim();
		log(id, v.id(), (approve ? "Approved by HR" : "Rejected by HR") + n);
		audit.publish("calendar", ENTITY, id, approve ? "APPROVED" : "REJECTED", null, Map.of("detail", e.title()));
		notifier.inApp(List.of(e.organiserId()), "CALENDAR", approve ? "Event approved" : "Event not approved",
				"“" + e.title() + "” " + (approve ? "is now on the calendar" : "was rejected by HR" + n), link(e));
		if (approve) {
			tell(v, feed.load(v.orgId(), id), "New event: " + e.title(), when(e) + (e.where() != null ? " · " + e.where() : ""));
		}
		return feed.detail(id, null);
	}

	@Transactional
	public Map<String, Object> rsvp(UUID id, String answer) {
		Viewer v = viewers.current();
		Ev e = feed.load(v.orgId(), id);
		if (CalendarFeedService.see(e, v) != See.FULL) {
			throw new NotFoundException("Event not found");
		}
		String a = answer == null ? "" : answer.trim().toUpperCase();
		if (!Set.of("YES", "MAYBE", "NO").contains(a) || !CalendarFeedService.rsvpable(e) || e.organiserId().equals(v.id())) {
			throw new UnprocessableException(List.of(ErrorItem.field("answer", "RSVP", "You can’t reply to this event")));
		}
		jdbc.sql("""
				insert into calendar_event_rsvp (event_id, employee_id, answer) values (:e, :me, :a)
				on conflict (event_id, employee_id) do update set answer = excluded.answer, answered_at = now()""")
				.param("e", id).param("me", v.id()).param("a", a).update();
		if ("NO".equals(a)) {
			notifier.inApp(List.of(e.organiserId()), "CALENDAR", v.name() + " can’t make it", "“" + e.title() + "” · " + when(e), link(e));
		}
		return Map.of("answer", a);
	}

	// ---------------------------------------------------------------------------------------------------------------- notifications

	private void tell(Viewer v, Ev e, String title, String body) {
		if ("ME".equals(e.audience()) || e.layer() == CalendarLayer.MY) {
			return;
		}
		List<UUID> to = feed.invitees(v.orgId(), e).stream().filter(x -> !x.equals(v.id()) && !x.equals(e.organiserId())).toList();
		if (!to.isEmpty()) {
			notifier.inApp(to, "CALENDAR", e.sensitive() || e.priv() ? "Calendar update" : title, e.sensitive() || e.priv() ? "Open the calendar for details" : body,
					link(e));
		}
	}

	static String link(Ev e) {
		return "/calendar?event=" + e.id() + "&date=" + e.date();
	}

	static String when(Ev e) {
		return DAY.format(e.date()) + (e.start() != null ? " · " + e.start() + (e.end() != null ? "–" + e.end() : "") : " · all day");
	}

	private List<UUID> admins(UUID org) {
		return jdbc.sql("""
				select distinct u.employee_id from app_user u join user_role ur on ur.user_id = u.id join role r on r.id = ur.role_id
				 where u.org_id = :org and u.status = 'ACTIVE' and r.key in ('hr_admin', 'super_admin')""").param("org", org).query(UUID.class).list();
	}

	// ---------------------------------------------------------------------------------------------------------------- rooms

	List<Map<String, Object>> rooms(UUID org, boolean activeOnly) {
		return jdbc.sql("""
				select r.id, r.name, r.capacity, r.location_id, l.name as location, r.active, r.version,
				       (select count(*) from calendar_event e where e.room_id = r.id) as used
				  from calendar_room r left join location l on l.id = r.location_id
				 where r.org_id = :org""" + (activeOnly ? " and r.active" : "") + " order by r.sort, r.name").param("org", org).query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("id", rs.getObject("id", UUID.class));
					m.put("name", rs.getString("name"));
					m.put("capacity", rs.getInt("capacity"));
					m.put("locationId", rs.getObject("location_id", UUID.class));
					m.put("location", rs.getString("location"));
					m.put("active", rs.getBoolean("active"));
					m.put("version", rs.getLong("version"));
					m.put("used", rs.getLong("used"));
					return m;
				}).list();
	}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> rooms(boolean all) {
		Viewer v = viewers.current();
		return rooms(v.orgId(), !(all && v.admin()));
	}

	private Viewer admin() {
		Viewer v = viewers.current();
		if (!v.admin()) {
			throw new ForbiddenException("Only calendar admins can manage rooms");
		}
		return v;
	}

	private Map<String, Object> room(Viewer v, UUID id) {
		return rooms(v.orgId(), false).stream().filter(r -> id.equals(r.get("id"))).findFirst().orElseThrow(() -> new NotFoundException("Room not found"));
	}

	/** Body: {@code name, capacity, locationId, active}. */
	@Transactional
	public Map<String, Object> saveRoom(UUID id, Map<String, Object> b) {
		Viewer v = admin();
		Map<String, Object> cur = id == null ? null : room(v, id);
		String name = b.containsKey("name") ? str(b.get("name")) : cur == null ? null : (String) cur.get("name");
		List<ErrorItem> errors = new ArrayList<>();
		if (name == null) {
			errors.add(ErrorItem.field("name", "REQUIRED", "Add a name"));
		}
		else if (jdbc.sql("select count(*) from calendar_room where org_id = :org and lower(name) = lower(:n) and id <> coalesce(:id, gen_random_uuid())")
				.param("org", v.orgId()).param("n", name).param("id", id, java.sql.Types.OTHER).query(Long.class).single() > 0) {
			errors.add(ErrorItem.field("name", "DUPLICATE", "A room with this name already exists"));
		}
		int cap = 0;
		Object c = b.containsKey("capacity") ? b.get("capacity") : cur == null ? 0 : cur.get("capacity");
		try {
			cap = c == null || String.valueOf(c).isBlank() ? 0 : Integer.parseInt(String.valueOf(c).trim());
			if (cap < 0 || cap > 10000) {
				throw new NumberFormatException();
			}
		}
		catch (NumberFormatException e) {
			errors.add(ErrorItem.field("capacity", "INVALID", "Seats must be a whole number"));
		}
		UUID loc = b.containsKey("locationId") ? uuid(b.get("locationId")) : cur == null ? null : (UUID) cur.get("locationId");
		boolean active = b.containsKey("active") ? Boolean.parseBoolean(String.valueOf(b.get("active"))) : cur == null || (Boolean) cur.get("active");
		if (!errors.isEmpty()) {
			throw new UnprocessableException(errors);
		}
		if (id == null) {
			id = jdbc.sql("""
					insert into calendar_room (org_id, name, capacity, location_id, active, sort, updated_by)
					values (:org, :n, :c, :l, :a, (select coalesce(max(sort), 0) + 10 from calendar_room where org_id = :org), :me) returning id""")
					.param("org", v.orgId()).param("n", name).param("c", cap).param("l", loc, java.sql.Types.OTHER).param("a", active).param("me", v.id())
					.query(UUID.class).single();
			audit.publish("calendar", "CalendarRoom", id, "ADDED", null, Map.of("detail", "Room added: " + name));
		}
		else {
			jdbc.sql("update calendar_room set name = :n, capacity = :c, location_id = :l, active = :a, updated_at = now(), updated_by = :me, version = version + 1 where id = :id")
					.param("n", name).param("c", cap).param("l", loc, java.sql.Types.OTHER).param("a", active).param("me", v.id()).param("id", id).update();
			audit.publish("calendar", "CalendarRoom", id, "UPDATED", null, Map.of("detail", "Room changed: " + name));
		}
		return room(v, id);
	}

	@Transactional
	public void deleteRoom(UUID id) {
		Viewer v = admin();
		Map<String, Object> r = room(v, id);
		long upcoming = jdbc.sql("""
				select count(*) from calendar_event where room_id = :id and status <> 'REJECTED'
				   and (case when repeat = 'NONE' then starts_on else coalesce(repeat_until, date '9999-12-31') end) >= current_date""")
				.param("id", id).query(Long.class).single();
		if (upcoming > 0) {
			throw new InUseException(r.get("name") + " is booked for " + upcoming + " upcoming event" + (upcoming == 1 ? "" : "s")
					+ ". Switch it off instead, so nobody books it again.");
		}
		jdbc.sql("delete from calendar_room where id = :id").param("id", id).update();
		audit.publish("calendar", "CalendarRoom", id, "DELETED", null, Map.of("detail", "Room removed: " + r.get("name")));
	}

	// ---------------------------------------------------------------------------------------------------------------- helpers

	private static String str(Object o) {
		if (o == null) {
			return null;
		}
		String s = String.valueOf(o).trim();
		return s.isEmpty() ? null : s;
	}

	private static UUID uuid(Object o) {
		try {
			return o == null || String.valueOf(o).isBlank() ? null : UUID.fromString(String.valueOf(o).trim());
		}
		catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static LocalDate date(Object o) {
		try {
			return o == null || String.valueOf(o).isBlank() ? null : LocalDate.parse(String.valueOf(o).trim());
		}
		catch (RuntimeException e) {
			return null;
		}
	}

	private static LocalTime time(Object o) {
		try {
			return o == null || String.valueOf(o).isBlank() ? null : LocalTime.parse(String.valueOf(o).trim());
		}
		catch (RuntimeException e) {
			return null;
		}
	}

	static Set<String> keys(Collection<Map<String, Object>> list) {
		return list.stream().map(m -> String.valueOf(m.get("id"))).collect(Collectors.toSet());
	}
}
