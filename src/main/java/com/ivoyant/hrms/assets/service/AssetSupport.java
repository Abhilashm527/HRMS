package com.ivoyant.hrms.assets.service;

import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.org.OrgProfile;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Small things both registers need: statuses by their system key, people cards, event writers, codes, date states. */
@Component
class AssetSupport {

	record Status(UUID id, String name, int color, boolean assignable, String systemKey, boolean active, String kind) {

		String badge() {
			if (systemKey == null) {
				return "pending";
			}
			return switch (systemKey) {
				case "ASSIGNED", "ACTIVE" -> "healthy";
				case "AVAILABLE", "NOT_IN_USE", "UNASSIGNED" -> "paused";
				case "IN_REPAIR", "EXPIRING" -> "degraded";
				case "RETIRED" -> "stopped";
				case "EXPIRED" -> "failed";
				default -> "pending";
			};
		}

		Map<String, Object> view() {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", id);
			m.put("name", name);
			m.put("systemKey", systemKey);
			m.put("color", color);
			m.put("badge", badge());
			return m;
		}
	}

	private static final String[][] PALETTE = {{"#1D4ED8", "#DBEAFE"}, {"#047857", "#D1FAE5"}, {"#B45309", "#FEF3C7"}, {"#BE185D", "#FCE7F3"},
			{"#6D28D9", "#EDE9FE"}, {"#0E7490", "#CFFAFE"}};

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final OrgProfile orgProfile;

	AssetSupport(JdbcClient jdbc, CurrentUser currentUser, OrgProfile orgProfile) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.orgProfile = orgProfile;
	}

	LocalDate today() {
		return LocalDate.now(orgProfile.zone());
	}

	// ---- statuses ----

	Status status(UUID id) {
		return jdbc.sql("select id, name, color, assignable, system_key, active, kind from asset_status where id = :id and org_id = :org").param("id", id)
				.param("org", currentUser.orgId()).query((rs, i) -> new Status(rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3), rs.getBoolean(4),
						rs.getString(5), rs.getBoolean(6), rs.getString(7))).optional().orElseThrow(() -> new NotFoundException("Status " + id + " not found"));
	}

	Status statusByKey(String kind, String key) {
		return jdbc.sql("select id, name, color, assignable, system_key, active, kind from asset_status where org_id = :org and kind = :k and system_key = :s")
				.param("org", currentUser.orgId()).param("k", kind).param("s", key).query((rs, i) -> new Status(rs.getObject(1, UUID.class), rs.getString(2),
						rs.getInt(3), rs.getBoolean(4), rs.getString(5), rs.getBoolean(6), rs.getString(7))).optional()
				.orElseThrow(() -> new NotFoundException("The " + key + " status is missing from the asset statuses"));
	}

	// ---- people ----

	Map<String, Object> person(UUID id) {
		if (id == null) {
			return null;
		}
		return jdbc.sql("""
				select e.id, e.employee_code, e.display_name, e.first_name, e.last_name, jt.name, d.name, e.photo_version
				from employee e left join job_title jt on jt.id = e.title_id left join department d on d.id = e.department_id where e.id = :id""")
				.param("id", id).query((rs, i) -> card(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
						rs.getString(7), rs.getInt(8))).optional().orElse(null);
	}

	static Map<String, Object> card(UUID id, String code, String name, String first, String last, String title, String department, int photoVersion) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", id);
		m.put("code", code);
		m.put("name", name);
		m.put("initials", (first == null || first.isBlank() ? "" : first.substring(0, 1)) + (last == null || last.isBlank() ? "" : last.substring(0, 1)));
		m.put("title", title);
		m.put("department", department);
		m.put("photoUrl", "/api/v1/employees/" + id + "/photo?s=64&v=" + photoVersion);
		return m;
	}

	String employeeName(UUID id) {
		return id == null ? null : jdbc.sql("select display_name from employee where id = :id").param("id", id).query(String.class).optional().orElse(null);
	}

	// ---- events ----

	void hardwareEvent(UUID assetId, String kind, String title, String description, String prev, String next, String note, UUID employeeId) {
		jdbc.sql("""
				insert into asset_event (org_id, asset_id, kind, title, description, prev_value, new_value, note, employee_id, actor_id)
				values (:org, :a, :k, :t, :d, :p, :n, :note, :e, :by)""").param("org", currentUser.orgId()).param("a", assetId).param("k", kind).param("t", title)
				.param("d", description).param("p", prev).param("n", next).param("note", note).param("e", employeeId, java.sql.Types.OTHER)
				.param("by", currentUser.employeeId(), java.sql.Types.OTHER).update();
	}

	void licenseEvent(UUID licenseId, String kind, String title, String description, String prev, String next, UUID employeeId) {
		jdbc.sql("""
				insert into license_event (org_id, license_id, kind, title, description, prev_value, new_value, employee_id, actor_id)
				values (:org, :l, :k, :t, :d, :p, :n, :e, :by)""").param("org", currentUser.orgId()).param("l", licenseId).param("k", kind).param("t", title)
				.param("d", description).param("p", prev).param("n", next).param("e", employeeId, java.sql.Types.OTHER)
				.param("by", currentUser.employeeId(), java.sql.Types.OTHER).update();
	}

	// ---- codes and states ----

	/** Three letters from a name: the first letter, then consonants, padded with X (Laptop → LPT, Figma → FGM). */
	static String code(String name) {
		String letters = name == null ? "" : name.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
		if (letters.isEmpty()) {
			return "XXX";
		}
		StringBuilder sb = new StringBuilder().append(letters.charAt(0));
		for (int i = 1; i < letters.length() && sb.length() < 3; i++) {
			char c = letters.charAt(i);
			if ("AEIOU".indexOf(c) < 0) {
				sb.append(c);
			}
		}
		for (int i = 1; i < letters.length() && sb.length() < 3; i++) {
			if (sb.indexOf(String.valueOf(letters.charAt(i))) < 0) {
				sb.append(letters.charAt(i));
			}
		}
		while (sb.length() < 3) {
			sb.append('X');
		}
		return sb.substring(0, 3);
	}

	Map<String, Object> dateState(LocalDate from, LocalDate until) {
		LocalDate today = today();
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("from", from);
		m.put("until", until);
		if (until == null) {
			m.put("state", "NONE");
			return m;
		}
		long days = ChronoUnit.DAYS.between(today, until);
		m.put("state", days < 0 ? "EXPIRED" : days <= 30 ? "ENDING" : "ACTIVE");
		m.put("daysLeft", days);
		if (from != null && until.isAfter(from)) {
			long total = ChronoUnit.DAYS.between(from, until);
			m.put("pctUsed", (int) Math.max(0, Math.min(100, 100 * ChronoUnit.DAYS.between(from, today) / total)));
		}
		return m;
	}

	static String[] typeColor(UUID typeId) {
		return PALETTE[Math.floorMod(typeId.hashCode(), PALETTE.length)];
	}
}
