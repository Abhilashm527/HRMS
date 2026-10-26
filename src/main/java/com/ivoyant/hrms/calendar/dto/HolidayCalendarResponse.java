package com.ivoyant.hrms.calendar.dto;

import com.ivoyant.hrms.calendar.HolidayView;
import java.util.List;
import java.util.UUID;

/** {@code label} is the name plus the year being viewed, e.g. "Karnataka 2026". */
public record HolidayCalendarResponse(UUID id, String name, String state, String label, boolean active,
		List<HolidayView> holidays) {
}
