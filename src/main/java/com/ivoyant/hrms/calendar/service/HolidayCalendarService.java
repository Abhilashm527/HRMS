package com.ivoyant.hrms.calendar.service;

import com.ivoyant.hrms.calendar.CalendarView;
import com.ivoyant.hrms.calendar.HolidayCalendars;
import com.ivoyant.hrms.calendar.HolidayView;
import com.ivoyant.hrms.calendar.domain.Holiday;
import com.ivoyant.hrms.calendar.domain.HolidayCalendar;
import com.ivoyant.hrms.calendar.repo.HolidayCalendarRepository;
import com.ivoyant.hrms.calendar.repo.HolidayRepository;
import com.ivoyant.hrms.common.security.CurrentUser;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class HolidayCalendarService implements HolidayCalendars {

	private final HolidayCalendarRepository calendars;
	private final HolidayRepository holidays;
	private final CurrentUser currentUser;
	private final org.springframework.jdbc.core.simple.JdbcClient jdbc;

	HolidayCalendarService(HolidayCalendarRepository calendars, HolidayRepository holidays, CurrentUser currentUser,
			org.springframework.jdbc.core.simple.JdbcClient jdbc) {
		this.calendars = calendars;
		this.holidays = holidays;
		this.currentUser = currentUser;
		this.jdbc = jdbc;
	}

	/** id, name, colours and day-off flag of every holiday type of the organization. */
	private record Type(UUID id, String name, String fg, String bg, boolean dayOff) {
	}

	private Map<UUID, Type> types() {
		Map<UUID, Type> out = new java.util.HashMap<>();
		jdbc.sql("select id, name, fg, bg, day_off from holiday_type where org_id = :org").param("org", currentUser.orgId()).query((rs, i) -> {
			UUID id = rs.getObject(1, UUID.class);
			out.put(id, new Type(id, rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5)));
			return 0;
		}).list();
		return out;
	}

	private static HolidayView view(Holiday h, Map<UUID, Type> types, boolean inherited) {
		Type t = types.get(h.getTypeId());
		return new HolidayView(h.getDay(), h.getName(), h.getTypeId(), t == null ? null : t.name(), t == null ? null : t.fg(),
				t == null ? null : t.bg(), t == null || t.dayOff(), inherited);
	}

	@Override
	@Transactional(readOnly = true)
	public boolean exists(UUID calendarId) {
		return calendars.findByIdAndOrgId(calendarId, currentUser.orgId()).isPresent();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<CalendarView> get(UUID calendarId) {
		return calendars.findByIdAndOrgId(calendarId, currentUser.orgId()).map(HolidayCalendarService::view);
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, CalendarView> byIds(Collection<UUID> ids) {
		if (ids.isEmpty()) {
			return Map.of();
		}
		return calendars.findByOrgIdAndIdIn(currentUser.orgId(), ids).stream()
				.collect(Collectors.toMap(HolidayCalendar::getId, HolidayCalendarService::view));
	}

	@Override
	@Transactional(readOnly = true)
	public List<CalendarView> all() {
		return calendars.findByOrgIdOrderByName(currentUser.orgId()).stream().map(HolidayCalendarService::view).toList();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<UUID> forState(String state) {
		if (state == null || state.isBlank()) {
			return Optional.empty();
		}
		return calendars.findFirstByOrgIdAndStateIgnoreCaseAndActiveTrue(currentUser.orgId(), state.trim())
				.map(HolidayCalendar::getId);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<UUID> nationalOnly() {
		return calendars.findFirstByOrgIdAndStateIsNullAndActiveTrueOrderByName(currentUser.orgId())
				.map(HolidayCalendar::getId);
	}

	@Override
	@Transactional(readOnly = true)
	public List<HolidayView> holidays(UUID calendarId, int year) {
		HolidayCalendar calendar = calendars.findByIdAndOrgId(calendarId, currentUser.orgId()).orElse(null);
		if (calendar == null) {
			return List.of();
		}
		LocalDate from = LocalDate.of(year, 1, 1);
		LocalDate to = LocalDate.of(year, 12, 31);
		Map<UUID, Type> types = types();
		List<HolidayView> out = new ArrayList<>();
		for (Holiday h : holidays.findByCalendarIdAndDayBetweenOrderByDay(calendarId, from, to)) {
			out.add(view(h, types, false));
		}
		if (calendar.getState() != null) {
			// a regional calendar also observes the national holidays
			nationalOnly().filter(id -> !id.equals(calendarId)).ifPresent(national -> {
				Set<LocalDate> own = out.stream().map(HolidayView::date).collect(Collectors.toSet());
				for (Holiday h : holidays.findByCalendarIdAndDayBetweenOrderByDay(national, from, to)) {
					if (!own.contains(h.getDay())) {
						out.add(view(h, types, true));
					}
				}
			});
		}
		out.sort(Comparator.comparing(HolidayView::date));
		return out;
	}

	@Override
	@Transactional(readOnly = true)
	public Set<LocalDate> daysOff(UUID calendarId, int year) {
		return holidays(calendarId, year).stream().filter(HolidayView::dayOff).map(HolidayView::date)
				.collect(Collectors.toSet());
	}

	private static CalendarView view(HolidayCalendar c) {
		return new CalendarView(c.getId(), c.getName(), c.getState(), c.isActive());
	}
}
