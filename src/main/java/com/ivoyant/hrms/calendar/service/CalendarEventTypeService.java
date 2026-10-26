package com.ivoyant.hrms.calendar.service;

import com.ivoyant.hrms.calendar.CalendarTypeUsage;
import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.security.ModuleAccess;
import com.ivoyant.hrms.common.web.CodedConflictException;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.InUseException;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.common.web.StaleException;
import com.ivoyant.hrms.common.web.UnprocessableException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The kinds of calendar event (Meeting, Interview ...) and the three built-in layers (Holiday, Birthday, Work anniversary). Built-in types
 * can be renamed, recoloured and switched off, never deleted. A custom colour is stored as its text colour plus a pale tint computed here.
 */
@Service
public class CalendarEventTypeService {

	private static final String ENTITY = "CalendarEventType";
	private static final Pattern HEX = Pattern.compile("^#[0-9A-Fa-f]{6}$");

	public record EventType(UUID id, String key, String name, String fg, String bg, String who, String whoLabel, boolean system,
			boolean active, int sort, long usage, Long version) {
	}

	public record AuditEntry(Instant at, String by, String action, String detail) {
	}

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final ModuleAccess access;
	private final CalendarTypeUsage usage;
	private final Audit audit;

	public CalendarEventTypeService(JdbcClient jdbc, CurrentUser currentUser, ModuleAccess access, CalendarTypeUsage usage, Audit audit) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.access = access;
		this.usage = usage;
		this.audit = audit;
	}

	private EventType map(java.sql.ResultSet rs) throws java.sql.SQLException {
		UUID id = rs.getObject("id", UUID.class);
		String who = rs.getString("who");
		return new EventType(id, rs.getString("key"), rs.getString("name"), rs.getString("fg"), rs.getString("bg"), who,
				"ADMINS".equals(who) ? "Admins only" : "Everyone", rs.getBoolean("system"), rs.getBoolean("active"), rs.getInt("sort"),
				usage.eventsOfType(id), rs.getLong("version"));
	}

	@Transactional(readOnly = true)
	public List<EventType> list(boolean includeSystem) {
		return jdbc.sql("select * from calendar_event_type where org_id = :org" + (includeSystem ? "" : " and not system") + " order by sort, name")
				.param("org", currentUser.orgId()).query((rs, i) -> map(rs)).list();
	}

	/** What an event form offers: active types the caller may use (admin-only types need calendar admin rights). */
	@Transactional(readOnly = true)
	public List<EventType> options() {
		boolean admin = access.has("calendar", "ADMIN");
		return list(false).stream().filter(t -> t.active() && (admin || "ALL".equals(t.who()))).toList();
	}

	@Transactional(readOnly = true)
	public EventType get(UUID id) {
		return jdbc.sql("select * from calendar_event_type where id = :id and org_id = :org").param("id", id).param("org", currentUser.orgId())
				.query((rs, i) -> map(rs)).optional().orElseThrow(() -> new NotFoundException("Event type " + id + " not found"));
	}

	/** Body: {@code name, fg, who} (Everyone or Admins only). */
	@Transactional
	public EventType create(Map<String, Object> body) {
		String name = name(body.get("name"));
		String fg = color(body.get("fg"));
		String who = who(body.get("who"), "ALL");
		requireFreeName(name, null);
		int sort = jdbc.sql("select coalesce(max(sort), 0) + 10 from calendar_event_type where org_id = :org and not system").param("org", currentUser.orgId())
				.query(Integer.class).single();
		UUID id = jdbc.sql("""
				insert into calendar_event_type (org_id, key, name, fg, bg, who, sort, created_by, updated_by)
				values (:org, :key, :name, :fg, :bg, :who, :sort, :by, :by) returning id""").param("org", currentUser.orgId())
				.param("key", key(name)).param("name", name).param("fg", fg).param("bg", tint(fg)).param("who", who).param("sort", sort)
				.param("by", currentUser.employeeId(), java.sql.Types.OTHER).query(UUID.class).single();
		EventType out = get(id);
		audit.publish("calendar", ENTITY, id, "ADDED", null, Map.of("detail", "Type added: " + name));
		return out;
	}

	/** Merge patch: {@code name, fg, who, active}. */
	@Transactional
	public EventType update(UUID id, Map<String, Object> patch, String ifMatch) {
		EventType cur = get(id);
		if (ifMatch != null && !ifMatch.isBlank() && !ifMatch.replace("W/", "").replace("\"", "").trim().equals(String.valueOf(cur.version()))) {
			throw new StaleException("Someone else changed this event type. Reload and try again.");
		}
		String name = patch.containsKey("name") ? name(patch.get("name")) : cur.name();
		String fg = patch.containsKey("fg") ? color(patch.get("fg")) : cur.fg();
		String who = cur.who();
		if (patch.containsKey("who")) {
			who = who(patch.get("who"), cur.who());
			if (cur.system() && !"ALL".equals(who)) {
				throw invalid("who", "SYSTEM_TYPE", cur.name() + " is a built-in type: everyone can use it");
			}
		}
		boolean active = patch.containsKey("active") ? Boolean.parseBoolean(String.valueOf(patch.get("active"))) : cur.active();
		if (!name.equalsIgnoreCase(cur.name())) {
			requireFreeName(name, id);
		}
		if (cur.active() && !active && !cur.system()
				&& jdbc.sql("select count(*) from calendar_event_type where org_id = :org and active and not system and id <> :id")
						.param("org", currentUser.orgId()).param("id", id).query(Long.class).single() == 0) {
			throw invalid("active", "LAST_ACTIVE", "Keep at least one type on");
		}
		jdbc.sql("update calendar_event_type set name = :name, fg = :fg, bg = :bg, who = :who, active = :active, updated_at = now(), updated_by = :by, version = version + 1 where id = :id")
				.param("name", name).param("fg", fg).param("bg", fg.equalsIgnoreCase(cur.fg()) ? cur.bg() : tint(fg)).param("who", who)
				.param("active", active).param("by", currentUser.employeeId(), java.sql.Types.OTHER).param("id", id).update();
		List<String> what = new ArrayList<>();
		if (!name.equals(cur.name())) {
			what.add("renamed " + cur.name() + " → " + name);
		}
		if (!fg.equalsIgnoreCase(cur.fg())) {
			what.add("recoloured");
		}
		if (active != cur.active()) {
			what.add("switched " + (active ? "on" : "off"));
		}
		if (!who.equals(cur.who())) {
			what.add("who can use: " + ("ADMINS".equals(who) ? "Admins only" : "Everyone"));
		}
		if (!what.isEmpty()) {
			audit.publish("calendar", ENTITY, id, "UPDATED", null, Map.of("detail", cur.name() + ": " + String.join(", ", what)));
		}
		return get(id);
	}

	@Transactional
	public void delete(UUID id) {
		EventType cur = get(id);
		if (cur.system()) {
			throw invalid("id", "SYSTEM_TYPE", cur.name() + " is a built-in type and can't be removed. Switch it off instead");
		}
		long used = usage.eventsOfType(id);
		if (used > 0) {
			throw new InUseException(cur.name() + " is used by " + used + (used == 1 ? " event" : " events") + ". Switch it off instead.", Map.of("events", used));
		}
		if (jdbc.sql("select count(*) from calendar_event_type where org_id = :org and not system and id <> :id").param("org", currentUser.orgId())
				.param("id", id).query(Long.class).single() == 0) {
			throw invalid("id", "LAST_ACTIVE", "Keep at least one type");
		}
		jdbc.sql("delete from calendar_event_type where id = :id").param("id", id).update();
		audit.publish("calendar", ENTITY, id, "REMOVED", null, Map.of("detail", "Type removed: " + cur.name()));
	}

	/** {@code [id, id ...]}: the order of the custom types (built-ins keep their place after them). */
	@Transactional
	public List<EventType> reorder(List<UUID> ids) {
		int sort = 10;
		for (UUID id : ids) {
			jdbc.sql("update calendar_event_type set sort = :s, updated_at = now() where id = :id and org_id = :org and not system")
					.param("s", sort).param("id", id).param("org", currentUser.orgId()).update();
			sort += 10;
		}
		return list(true);
	}

	@Transactional(readOnly = true)
	public List<AuditEntry> audit(int limit) {
		return jdbc.sql("select a.at, e.display_name, a.action, a.after ->> 'detail' from audit_log a left join employee e on e.id = a.actor_id "
				+ "where a.org_id = :org and a.module = 'calendar' and a.entity = :entity order by a.at desc limit :n").param("org", currentUser.orgId())
				.param("entity", ENTITY).param("n", Math.min(Math.max(limit, 1), 200))
				.query((rs, i) -> new AuditEntry(rs.getTimestamp(1).toInstant(), rs.getString(2), rs.getString(3), rs.getString(4))).list();
	}

	// ---- rules ----

	private void requireFreeName(String name, UUID self) {
		boolean taken = jdbc.sql("select count(*) from calendar_event_type where org_id = :org and lower(name) = lower(:n) and (cast(:self as uuid) is null or id <> cast(:self as uuid))")
				.param("org", currentUser.orgId()).param("n", name).param("self", self, java.sql.Types.OTHER).query(Long.class).single() > 0;
		if (taken) {
			throw new CodedConflictException("NAME_TAKEN", name + " already exists");
		}
	}

	private static String name(Object v) {
		String t = v == null ? "" : v.toString().trim().replaceAll("\\s+", " ");
		if (t.isEmpty() || t.length() > 40) {
			throw invalid("name", "NAME_LENGTH", "Name must be 1–40 characters");
		}
		return t;
	}

	private static String color(Object v) {
		String t = v == null ? "" : v.toString().trim();
		if (!HEX.matcher(t).matches()) {
			throw invalid("fg", "BAD_COLOR", "Colour must look like #1D4ED8");
		}
		return t.toUpperCase(Locale.ROOT);
	}

	private static String who(Object v, String fallback) {
		if (v == null || v.toString().isBlank()) {
			return fallback;
		}
		String t = v.toString().trim().toUpperCase(Locale.ROOT).replace(' ', '_');
		return switch (t) {
			case "ALL", "EVERYONE" -> "ALL";
			case "ADMINS", "ADMINS_ONLY", "ADMIN" -> "ADMINS";
			default -> throw invalid("who", "INVALID", "Who can use it must be Everyone or Admins only");
		};
	}

	private static String key(String name) {
		return name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_") + "_" + Long.toString(System.nanoTime(), 36).toUpperCase(Locale.ROOT);
	}

	/** The pale background for a type: its colour at 14% over white. */
	static String tint(String fg) {
		int r = Integer.parseInt(fg.substring(1, 3), 16);
		int g = Integer.parseInt(fg.substring(3, 5), 16);
		int b = Integer.parseInt(fg.substring(5, 7), 16);
		return String.format("#%02X%02X%02X", mix(r), mix(g), mix(b));
	}

	private static int mix(int c) {
		return (int) Math.round(255 - (255 - c) * 0.14);
	}

	private static UnprocessableException invalid(String field, String code, String message) {
		return new UnprocessableException(List.of(ErrorItem.field(field, code, message)));
	}

	@SuppressWarnings("unused")
	private static Map<String, Object> asMap(String k, Object v) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put(k, v);
		return m;
	}
}
