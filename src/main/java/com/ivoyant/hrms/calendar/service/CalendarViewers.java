package com.ivoyant.hrms.calendar.service;

import com.ivoyant.hrms.calendar.service.CalendarLayer.Perm;
import com.ivoyant.hrms.calendar.service.CalendarLayer.Viewer;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.security.ModuleAccess;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.common.web.UnprocessableException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Builds the {@link Viewer} for the caller (or for the owner of a calendar subscription link) and keeps each person's calendar
 * settings: hide my birthday, default reminder, time zone. Settings live in {@code user_preference} under the key {@code calendar}; the
 * subscription link's token is stored only as a hash under {@code calendar.ical}.
 */
@Component
public class CalendarViewers {

	static final List<String> REMINDERS = List.of("NONE", "15M", "1H", "1D");
	private static final SecureRandom RANDOM = new SecureRandom();

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final ModuleAccess access;
	private final ObjectMapper json;

	public CalendarViewers(JdbcClient jdbc, CurrentUser currentUser, ModuleAccess access, ObjectMapper json) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.access = access;
		this.json = json;
	}

	Viewer current() {
		UUID me = currentUser.requireEmployeeId();
		return build(me, access.has("calendar", "ADMIN"));
	}

	/** The owner of a subscription link; admin rights come from their roles, as there is no signed-in user. */
	Viewer byToken(String token) {
		UUID me = token == null || token.length() < 20 ? null : jdbc.sql("select employee_id from user_preference where key = 'calendar.ical' and value ->> 'hash' = :h")
				.param("h", hash(token)).query(UUID.class).optional().orElse(null);
		if (me == null) {
			throw new NotFoundException("This calendar link is not valid any more");
		}
		Viewer v = build(me, false);
		boolean admin = v.holds("hr_admin");
		return admin ? build(me, true) : v;
	}

	private Viewer build(UUID me, boolean admin) {
		return jdbc.sql("""
				select e.id, e.org_id, e.display_name, e.manager_id, e.department_id, e.location_id, l.timezone as loc_tz, o.timezone as org_tz,
				       exists (select 1 from employee r where r.manager_id = e.id and r.status <> 'EXITED') as mgr,
				       array(select distinct r.key from app_user u join user_role ur on ur.user_id = u.id join role r on r.id = ur.role_id
				              where u.employee_id = e.id and u.status = 'ACTIVE') as roles,
				       (select value ->> 'timeZone' from user_preference where employee_id = e.id and key = 'calendar') as my_tz
				  from employee e join org o on o.id = e.org_id left join location l on l.id = e.location_id
				 where e.id = :me""").param("me", me).query((rs, i) -> {
					Set<String> roles = Set.copyOf(java.util.Arrays.asList((String[]) rs.getArray("roles").getArray()));
					ZoneId zone = zone(rs.getString("my_tz"), zone(rs.getString("loc_tz"), zone(rs.getString("org_tz"), ZoneId.of("Asia/Kolkata"))));
					return new Viewer(rs.getObject("org_id", UUID.class), me, rs.getString("display_name"), rs.getObject("manager_id", UUID.class),
							rs.getObject("department_id", UUID.class), rs.getObject("location_id", UUID.class), roles, admin,
							rs.getBoolean("mgr") || roles.contains("manager"), zone);
				}).optional().orElseThrow(() -> new NotFoundException("Employee not found"));
	}

	static ZoneId zone(String id, ZoneId fallback) {
		try {
			return id == null || id.isBlank() ? fallback : ZoneId.of(id.trim());
		}
		catch (RuntimeException e) {
			return fallback;
		}
	}

	ZoneId orgZone(UUID org) {
		return zone(jdbc.sql("select timezone from org where id = :o").param("o", org).query(String.class).optional().orElse(null), ZoneId.of("Asia/Kolkata"));
	}

	/** The caller and their layers: name, colour and what they may do on each. */
	Map<String, Object> describe(Viewer v) {
		Map<String, Object> me = new LinkedHashMap<>();
		me.put("id", v.id());
		me.put("name", v.name());
		me.put("admin", v.admin());
		me.put("manager", v.manager());
		me.put("timeZone", v.zone().getId());
		List<Map<String, Object>> layers = new ArrayList<>();
		for (CalendarLayer l : CalendarLayer.values()) {
			Perm p = v.perm(l);
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("key", l.name());
			m.put("name", l.label);
			m.put("description", l.description);
			m.put("color", l.color);
			m.put("see", p.see().name());
			m.put("add", p.add());
			m.put("edit", p.edit().name());
			m.put("needsApproval", p.needsApproval());
			layers.add(m);
		}
		me.put("layers", layers);
		me.putAll(settings(v.id()));
		return me;
	}

	// ---------------------------------------------------------------------------------------------------------------- my settings

	@SuppressWarnings("unchecked")
	Map<String, Object> raw(UUID me, String key) {
		String s = jdbc.sql("select value::text from user_preference where employee_id = :me and key = :k").param("me", me).param("k", key)
				.query(String.class).optional().orElse(null);
		try {
			return s == null ? new LinkedHashMap<>() : new LinkedHashMap<>(json.readValue(s, Map.class));
		}
		catch (JacksonException e) {
			return new LinkedHashMap<>();
		}
	}

	private void put(UUID me, String key, Map<String, Object> value) {
		jdbc.sql("""
				insert into user_preference (employee_id, key, value) values (:me, :k, cast(:v as jsonb))
				on conflict (employee_id, key) do update set value = excluded.value, updated_at = now()""")
				.param("me", me).param("k", key).param("v", json.writeValueAsString(value)).update();
	}

	Map<String, Object> settings(UUID me) {
		Map<String, Object> s = raw(me, "calendar");
		Map<String, Object> ical = raw(me, "calendar.ical");
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("bdayOptOut", Boolean.TRUE.equals(s.get("bdayOptOut")));
		out.put("remind", s.get("remind") instanceof String r && REMINDERS.contains(r) ? r : "15M");
		out.put("myTimeZone", s.get("timeZone"));
		out.put("subscribed", ical.get("hash") != null);
		out.put("subscribedAt", ical.get("at"));
		return out;
	}

	@Transactional(readOnly = true)
	public Map<String, Object> mySettings() {
		Viewer v = current();
		Map<String, Object> out = settings(v.id());
		out.put("timeZone", v.zone().getId());
		return out;
	}

	/** Merge patch: {@code bdayOptOut, remind, timeZone} (blank time zone = follow the office). */
	@Transactional
	public Map<String, Object> updateSettings(Map<String, Object> patch) {
		UUID me = currentUser.requireEmployeeId();
		Map<String, Object> s = raw(me, "calendar");
		if (patch.containsKey("bdayOptOut")) {
			s.put("bdayOptOut", Boolean.parseBoolean(String.valueOf(patch.get("bdayOptOut"))));
		}
		if (patch.containsKey("remind")) {
			String r = String.valueOf(patch.get("remind")).toUpperCase();
			if (!REMINDERS.contains(r)) {
				throw new UnprocessableException(List.of(ErrorItem.field("remind", "INVALID", "Pick a reminder from the list")));
			}
			s.put("remind", r);
		}
		if (patch.containsKey("timeZone")) {
			Object tz = patch.get("timeZone");
			if (tz == null || String.valueOf(tz).isBlank()) {
				s.remove("timeZone");
			}
			else {
				try {
					s.put("timeZone", ZoneId.of(String.valueOf(tz).trim()).getId());
				}
				catch (RuntimeException e) {
					throw new UnprocessableException(List.of(ErrorItem.field("timeZone", "INVALID", "Unknown time zone")));
				}
			}
		}
		put(me, "calendar", s);
		return mySettings();
	}

	/** A new private subscription link (any earlier one stops working). The token is shown once and stored only as a hash. */
	@Transactional
	public Map<String, Object> newSubscription() {
		UUID me = currentUser.requireEmployeeId();
		byte[] b = new byte[24];
		RANDOM.nextBytes(b);
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
		put(me, "calendar.ical", new LinkedHashMap<>(Map.of("hash", hash(token), "at", OffsetDateTime.now().toString())));
		return Map.of("token", token, "path", "/api/v1/calendar/ical/" + token + ".ics");
	}

	@Transactional
	public void revokeSubscription() {
		jdbc.sql("delete from user_preference where employee_id = :me and key = 'calendar.ical'").param("me", currentUser.requireEmployeeId()).update();
	}

	static String hash(String token) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
