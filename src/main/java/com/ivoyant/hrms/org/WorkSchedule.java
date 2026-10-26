package com.ivoyant.hrms.org;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * A location's working week and hours. Every module counts working days through {@link #isWorkingDay} so they agree.
 * An {@code end} before {@code start} is a night shift.
 */
public record WorkSchedule(WeekPattern weekPattern, LocalTime start, LocalTime end, int graceMinutes, ZoneId zone) {

	/**
	 * MON_FRI: Monday to Friday. MON_SAT: Monday to Saturday. MON_FRI_ALT_SAT: Monday to Friday, and the 1st, 3rd and
	 * 5th Saturdays; the 2nd and 4th Saturdays are off. (The alternate-Saturday rule is an assumption to confirm with HR.)
	 */
	public boolean isWorkingDay(LocalDate date) {
		DayOfWeek day = date.getDayOfWeek();
		if (day == DayOfWeek.SUNDAY) {
			return false;
		}
		if (day != DayOfWeek.SATURDAY) {
			return true;
		}
		return switch (weekPattern) {
			case MON_FRI -> false;
			case MON_SAT -> true;
			case MON_FRI_ALT_SAT -> {
				int nth = (date.getDayOfMonth() - 1) / 7 + 1;
				yield nth != 2 && nth != 4;
			}
		};
	}

	public boolean overnight() {
		return end.isBefore(start);
	}
}
