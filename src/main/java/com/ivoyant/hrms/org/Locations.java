package com.ivoyant.hrms.org;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What other modules may ask about locations (they never touch the repository). Results are cached for five minutes
 * and evicted when a location changes.
 */
public interface Locations {

	Optional<LocationView> get(UUID locationId);

	boolean isActive(UUID locationId);

	WorkSchedule scheduleFor(UUID locationId);

	/** The schedule of the employee's base location; the organisation's default when they have none. */
	WorkSchedule scheduleForEmployee(UUID employeeId);

	/** Employee's location time zone, else the organisation's. */
	ZoneId zoneForEmployee(UUID employeeId);

	UUID holidayCalendarFor(UUID locationId);

	StatutoryProfile statutoryFor(UUID locationId);

	AttendancePolicy attendanceFor(UUID locationId);

	/** Active locations that hold assets (stock, issue, return). */
	List<UUID> assetStores();

	/**
	 * Days the employee works between the dates (inclusive): the schedule says it is a working day and the location's
	 * holiday calendar does not make it a day off.
	 */
	List<LocalDate> workingDays(UUID employeeId, LocalDate from, LocalDate to);
}
