package com.ivoyant.hrms.calendar;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * What other modules may ask the calendar module. A regional calendar inherits the national holidays, so the
 * national dates are stored once and added when a regional calendar is read.
 */
public interface HolidayCalendars {

	boolean exists(UUID calendarId);

	Optional<CalendarView> get(UUID calendarId);

	Map<UUID, CalendarView> byIds(Collection<UUID> ids);

	List<CalendarView> all();

	/** The calendar for a state, if one exists. */
	Optional<UUID> forState(String state);

	/** The calendar with national holidays only. */
	Optional<UUID> nationalOnly();

	List<HolidayView> holidays(UUID calendarId, int year);

	/** Days off (national and regional, not optional) in the year. */
	Set<LocalDate> daysOff(UUID calendarId, int year);
}
