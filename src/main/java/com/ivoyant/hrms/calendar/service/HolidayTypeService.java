package com.ivoyant.hrms.calendar.service;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.event.HolidayCalendarChangedEvent;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.CodedConflictException;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.common.web.UnprocessableException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Holiday types (National, Regional, Optional ...): a colour and whether the type is a day off. Day-off types are left out of leave day
 * counts and attendance; working-day types are listed but still count as working days. Holidays point at their type by id, so renaming
 * or recolouring needs no cascade.
 */
@Service
public class HolidayTypeService {

	/** The swatches the screen offers (text colour, background). Any other pair is refused. */
	static final List<String[]> PALETTE = List.of(new String[] {"#1D4ED8", "#DBEAFE"}, new String[] {"#0F766E", "#CCFBF1"},
			new String[] {"#B45309", "#FEF3C7"}, new String[] {"#6D28D9", "#EDE9FE"}, new String[] {"#B91C1C", "#FEE2E2"},
			new String[] {"#BE185D", "#FCE7F3"}, new String[] {"#0369A1", "#E0F2FE"}, new String[] {"#15803D", "#DCFCE7"},
			new String[] {"#475569", "#F1F5F9"});

	public record HolidayType(UUID id, String name, String fg, String bg, boolean dayOff, int sort, long used) {
	}

	public record Saved(HolidayType type, List<Map<String, Object>> affectedPending) {
	}

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final Audit audit;
	private final ApplicationEventPublisher events;

	public HolidayTypeService(JdbcClient jdbc, CurrentUser currentUser, Audit audit, ApplicationEventPublisher events) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.audit = audit;
		this.events = events;
	}

	private static final String SELECT = "select t.id, t.name, t.fg, t.bg, t.day_off, t.sort, (select count(*) from holiday h where h.type_id = t.id) as used "
			+ "from holiday_type t where t.org_id = :org";

	@Transactional(readOnly = true)
	public List<HolidayType> list() {
		return jdbc.sql(SELECT + " order by t.sort, t.name").param("org", currentUser.orgId()).query((rs, i) -> map(rs)).list();
	}

	@Transactional(readOnly = true)
	public HolidayType get(UUID id) {
		return jdbc.sql(SELECT + " and t.id = :id").param("org", currentUser.orgId()).param("id", id).query((rs, i) -> map(rs)).optional()
				.orElseThrow(() -> new NotFoundException("Holiday type " + id + " not found"));
	}

	private static HolidayType map(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new HolidayType(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5), rs.getInt(6), rs.getLong(7));
	}

	/** Body: {@code name, fg, bg, dayOff} (day off unless said otherwise). */
	@Transactional
	public HolidayType create(Map<String, Object> body) {
		String name = name(body.get("name"));
		String[] pair = colours(body.get("fg"), body.get("bg"));
		requireFree(name, null);
		boolean dayOff = body.get("dayOff") == null || Boolean.parseBoolean(String.valueOf(body.get("dayOff")));
		int sort = jdbc.sql("select coalesce(max(sort), -1) + 1 from holiday_type where org_id = :org").param("org", currentUser.orgId()).query(Integer.class).single();
		UUID id = jdbc.sql("insert into holiday_type (org_id, name, fg, bg, day_off, sort, updated_by) values (:org, :n, :fg, :bg, :d, :s, :by) returning id")
				.param("org", currentUser.orgId()).param("n", name).param("fg", pair[0]).param("bg", pair[1]).param("d", dayOff).param("s", sort)
				.param("by", currentUser.employeeId(), java.sql.Types.OTHER).query(UUID.class).single();
		audit.publish("leave", "HolidayType", id, "ADDED", null, Map.of("detail", "Holiday type added: " + name));
		return get(id);
	}

	/** Any of {@code name, fg, bg, dayOff}. A day-off change names the pending leave requests that span affected holidays (none exist yet). */
	@Transactional
	public Saved update(UUID id, Map<String, Object> patch) {
		HolidayType cur = get(id);
		String name = patch.containsKey("name") ? name(patch.get("name")) : cur.name();
		String[] pair = patch.containsKey("fg") || patch.containsKey("bg")
				? colours(patch.getOrDefault("fg", cur.fg()), patch.getOrDefault("bg", cur.bg())) : new String[] {cur.fg(), cur.bg()};
		boolean dayOff = patch.containsKey("dayOff") ? Boolean.parseBoolean(String.valueOf(patch.get("dayOff"))) : cur.dayOff();
		if (!name.equalsIgnoreCase(cur.name())) {
			requireFree(name, id);
		}
		jdbc.sql("update holiday_type set name = :n, fg = :fg, bg = :bg, day_off = :d, updated_at = now(), updated_by = :by where id = :id")
				.param("n", name).param("fg", pair[0]).param("bg", pair[1]).param("d", dayOff).param("by", currentUser.employeeId(), java.sql.Types.OTHER)
				.param("id", id).update();
		if (!name.equals(cur.name())) {
			audit.publish("leave", "HolidayType", id, "RENAMED", null, Map.of("detail", cur.name() + " → " + name));
		}
		if (dayOff != cur.dayOff()) {
			audit.publish("leave", "HolidayType", id, "CHANGED", null, Map.of("detail", name + " now counts as " + (dayOff ? "a day off" : "a working day")));
			events.publishEvent(new HolidayCalendarChangedEvent("day off of " + name));
		}
		// leave requests do not exist yet, so none can span an affected holiday
		return new Saved(get(id), List.of());
	}

	/** Moves the holidays of this type to {@code moveTo} (the first other type when not given), then deletes the type. */
	@Transactional
	public void delete(UUID id, UUID moveTo) {
		HolidayType cur = get(id);
		List<HolidayType> others = list().stream().filter(t -> !t.id().equals(id)).toList();
		if (others.isEmpty()) {
			throw invalid("id", "LAST_TYPE", "Keep at least one holiday type");
		}
		UUID target = moveTo != null ? moveTo : others.get(0).id();
		if (target.equals(id) || others.stream().noneMatch(t -> t.id().equals(target))) {
			throw invalid("moveTo", "BAD_MOVE_TARGET", "Choose another holiday type to move the holidays to");
		}
		String to = others.stream().filter(t -> t.id().equals(target)).findFirst().orElseThrow().name();
		long moved = cur.used();
		if (moved > 0) {
			long closed = jdbc.sql("select count(distinct h.calendar_id || '-' || extract(year from h.date)) from holiday h join holiday_year_lock l "
					+ "on l.calendar_id = h.calendar_id and l.year = extract(year from h.date)::int where h.type_id = :id").param("id", id).query(Long.class).single();
			jdbc.sql("update holiday set type_id = :to where type_id = :id").param("to", target).param("id", id).update();
			if (closed > 0) {
				audit.publish("leave", "HolidayType", id, "MOVED_CLOSED_YEAR", null, Map.of("detail", moved + " holidays moved, including " + closed + " calendar years already closed"));
			}
		}
		jdbc.sql("delete from holiday_type where id = :id").param("id", id).update();
		audit.publish("leave", "HolidayType", id, "REMOVED", null, Map.of("detail", "Holiday type " + cur.name() + " deleted (" + moved + " moved to " + to + ")"));
		events.publishEvent(new HolidayCalendarChangedEvent("type " + cur.name() + " deleted"));
	}

	private void requireFree(String name, UUID self) {
		boolean taken = jdbc.sql("select count(*) from holiday_type where org_id = :org and lower(name) = lower(:n) and (cast(:self as uuid) is null or id <> cast(:self as uuid))")
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

	private static String[] colours(Object fg, Object bg) {
		String f = fg == null ? "" : fg.toString().trim().toUpperCase(Locale.ROOT);
		String b = bg == null ? "" : bg.toString().trim().toUpperCase(Locale.ROOT);
		for (String[] p : PALETTE) {
			if (p[0].equalsIgnoreCase(f) && p[1].equalsIgnoreCase(b)) {
				return new String[] {p[0], p[1]};
			}
		}
		throw invalid("fg", "BAD_COLOR", "Pick one of the colours on offer");
	}

	private static UnprocessableException invalid(String field, String code, String message) {
		return new UnprocessableException(List.of(ErrorItem.field(field, code, message)));
	}
}
