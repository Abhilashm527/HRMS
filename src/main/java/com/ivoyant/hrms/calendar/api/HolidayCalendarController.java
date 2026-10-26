package com.ivoyant.hrms.calendar.api;

import com.ivoyant.hrms.calendar.CalendarView;
import com.ivoyant.hrms.calendar.HolidayCalendars;
import com.ivoyant.hrms.calendar.dto.HolidayCalendarResponse;
import com.ivoyant.hrms.common.authz.RequiresMember;
import com.ivoyant.hrms.common.web.NotFoundException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read side of the holiday calendars; editing them comes with the calendar module. */
@RestController
@RequestMapping("/api/v1/holiday-calendars")
@RequiresMember
public class HolidayCalendarController {

	private final HolidayCalendars calendars;

	public HolidayCalendarController(HolidayCalendars calendars) {
		this.calendars = calendars;
	}

	@GetMapping
	public List<HolidayCalendarResponse> list(@RequestParam(required = false) Integer year) {
		int y = year != null ? year : LocalDate.now().getYear();
		return calendars.all().stream().map(c -> response(c, y)).toList();
	}

	@GetMapping("/{id}")
	public HolidayCalendarResponse get(@PathVariable UUID id, @RequestParam(required = false) Integer year) {
		CalendarView c = calendars.get(id).orElseThrow(() -> new NotFoundException("Holiday calendar " + id + " not found"));
		return response(c, year != null ? year : LocalDate.now().getYear());
	}

	private HolidayCalendarResponse response(CalendarView c, int year) {
		return new HolidayCalendarResponse(c.id(), c.name(), c.state(), c.name() + " " + year, c.active(),
				calendars.holidays(c.id(), year));
	}
}
