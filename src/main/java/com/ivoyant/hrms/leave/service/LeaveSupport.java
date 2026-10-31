package com.ivoyant.hrms.leave.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.UnprocessableException;

/** What the leave screens share: how a person is shown, request and encashment numbers, day and money rounding, leave types by id. */
@Component
public class LeaveSupport {

	static final String PERSON_SQL = """
			select e.id, e.employee_code, e.display_name, e.first_name, e.last_name, e.department_id, dep.name as dept, jt.name as title,
			       e.location_id, l.name as loc, e.photo_version, e.status, e.joining_date, e.exit_date, e.manager_id
			  from employee e
			  left join department dep on dep.id = e.department_id
			  left join job_title jt on jt.id = e.title_id
			  left join location l on l.id = e.location_id
			""";

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;

	LeaveSupport(JdbcClient jdbc, CurrentUser currentUser) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
	}

	/** A leave type row, active or not. */
	public record Type(UUID id, String code, String name, String shortLabel, int color, boolean active, String accrual, BigDecimal quota, String carry, BigDecimal carryMax,
			boolean encashable, BigDecimal encashMax, boolean paid, int sort) {
	}

	public List<Type> types() {
		return jdbc.sql("""
				select id, code, name, short_label, color_index, active, accrual, quota_days, carry, carry_max, encashable, encash_max, paid, sort
				  from leave_type where org_id = :org order by sort, code""").param("org", currentUser.orgId())
				.query((rs, i) -> new Type(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getBoolean(6), rs.getString(7),
						rs.getBigDecimal(8), rs.getString(9), rs.getBigDecimal(10), rs.getBoolean(11), rs.getBigDecimal(12), rs.getBoolean(13), rs.getInt(14)))
				.list();
	}

	public Map<UUID, Type> typesById() {
		Map<UUID, Type> m = new LinkedHashMap<>();
		types().forEach(t -> m.put(t.id(), t));
		return m;
	}

	public Type typeByCode(String code) {
		return types().stream().filter(t -> t.code().equalsIgnoreCase(code)).findFirst()
				.orElseThrow(() -> problem("type", "UNKNOWN_TYPE", "There is no leave type " + code));
	}

	public static Map<String, Object> typeRef(Type t) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", t.id());
		m.put("code", t.code());
		m.put("name", t.name());
		m.put("short", t.shortLabel());
		m.put("color", t.color());
		return m;
	}

	/** People by id, shaped for the screens. Unknown ids are left out. */
	public Map<UUID, Map<String, Object>> people(Collection<UUID> ids) {
		Map<UUID, Map<String, Object>> out = new HashMap<>();
		List<UUID> list = ids.stream().filter(java.util.Objects::nonNull).distinct().toList();
		if (list.isEmpty()) {
			return out;
		}
		jdbc.sql(PERSON_SQL + " where e.org_id = :org and e.id in (:ids)").param("org", currentUser.orgId()).param("ids", list)
				.query((rs, i) -> person(rs)).list().forEach(p -> out.put((UUID) p.get("id"), p));
		return out;
	}

	public Map<String, Object> person(UUID id) {
		return people(List.of(id)).getOrDefault(id, Map.of("id", id, "name", "Former employee", "initials", "—"));
	}

	static Map<String, Object> person(java.sql.ResultSet rs) throws java.sql.SQLException {
		Map<String, Object> m = new LinkedHashMap<>();
		UUID id = rs.getObject("id", UUID.class);
		String first = rs.getString("first_name");
		String last = rs.getString("last_name");
		m.put("id", id);
		m.put("code", rs.getString("employee_code"));
		m.put("name", rs.getString("display_name"));
		m.put("first", first);
		m.put("initials", ((first == null || first.isBlank() ? "" : first.substring(0, 1)) + (last == null || last.isBlank() ? "" : last.substring(0, 1))).toUpperCase(Locale.ROOT));
		m.put("title", rs.getString("title"));
		UUID dept = rs.getObject("department_id", UUID.class);
		if (dept != null) {
			m.put("department", Map.of("id", dept, "name", rs.getString("dept"), "color", Math.floorMod(dept.hashCode(), 8)));
		}
		UUID loc = rs.getObject("location_id", UUID.class);
		if (loc != null) {
			m.put("location", Map.of("id", loc, "name", rs.getString("loc")));
		}
		m.put("photoUrl", "/api/v1/employees/" + id + "/photo?s=64&v=" + rs.getInt("photo_version"));
		m.put("status", rs.getString("status"));
		m.put("joined", rs.getObject("joining_date", LocalDate.class));
		m.put("exitDate", rs.getObject("exit_date", LocalDate.class));
		return m;
	}

	public static String requestNo(long no) {
		return "LR-" + String.format("%04d", no);
	}

	public static String encashNo(long no) {
		return "EN-" + String.format("%04d", no);
	}

	/** Days rounded to the half day and shown without trailing zeros. */
	public static BigDecimal days(BigDecimal d) {
		if (d == null) {
			return BigDecimal.ZERO;
		}
		BigDecimal r = d.multiply(BigDecimal.TWO).setScale(0, RoundingMode.HALF_UP).divide(BigDecimal.TWO).stripTrailingZeros();
		// stripTrailingZeros turns 10 into 1E+1, which JSON writes as 1E+1
		return r.scale() < 0 ? r.setScale(0) : r;
	}

	public static BigDecimal money(BigDecimal m) {
		return m.setScale(2, RoundingMode.HALF_UP);
	}

	public static UnprocessableException problem(String field, String code, String message) {
		return new UnprocessableException(List.of(ErrorItem.field(field, code, message)));
	}

	public static String text(Object o) {
		return o == null || o.toString().isBlank() ? null : o.toString().trim();
	}

	public static List<UUID> uuids(Object o) {
		List<UUID> out = new ArrayList<>();
		if (o instanceof Collection<?> c) {
			for (Object x : c) {
				if (x != null && !x.toString().isBlank()) {
					out.add(UUID.fromString(x.toString()));
				}
			}
		}
		return out;
	}
}
