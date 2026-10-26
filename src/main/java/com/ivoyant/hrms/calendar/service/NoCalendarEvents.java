package com.ivoyant.hrms.calendar.service;

import com.ivoyant.hrms.calendar.CalendarTypeUsage;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Placeholder until calendar events exist: no event uses any type. */
@Component
class NoCalendarEvents implements CalendarTypeUsage {

	@Override
	public long eventsOfType(UUID typeId) {
		return 0;
	}
}
