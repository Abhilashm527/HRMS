package com.ivoyant.hrms.calendar;

import java.util.UUID;

/** How many calendar events use an event type. The events part of the calendar plugs in here; until it exists nothing is in use. */
public interface CalendarTypeUsage {

	long eventsOfType(UUID typeId);
}
