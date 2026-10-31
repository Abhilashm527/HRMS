package com.ivoyant.hrms.calendar.api;

import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ivoyant.hrms.calendar.service.HolidayAdminService;
import com.ivoyant.hrms.common.authz.RequiresMember;

/** Editing a holiday calendar's year: add, change and delete holidays; close and reopen the year. */
@RestController
@RequestMapping("/api/v1/holiday-calendars/{calendarId}")
@RequiresMember
public class HolidayAdminController {

	private final HolidayAdminService holidays;

	public HolidayAdminController(HolidayAdminService holidays) {
		this.holidays = holidays;
	}

	@GetMapping("/year/{year}")
	public Map<String, Object> year(@PathVariable UUID calendarId, @PathVariable int year) {
		return holidays.year(calendarId, year);
	}

	@PostMapping("/holidays")
	public Map<String, Object> add(@PathVariable UUID calendarId, @RequestBody Map<String, Object> body) {
		return holidays.add(calendarId, body);
	}

	@PatchMapping("/holidays/{holidayId}")
	public Map<String, Object> update(@PathVariable UUID calendarId, @PathVariable UUID holidayId, @RequestBody Map<String, Object> body) {
		return holidays.update(calendarId, holidayId, body);
	}

	@DeleteMapping("/holidays/{holidayId}")
	public Map<String, Object> delete(@PathVariable UUID calendarId, @PathVariable UUID holidayId) {
		return holidays.delete(calendarId, holidayId);
	}

	@PostMapping("/year/{year}/close")
	public Map<String, Object> close(@PathVariable UUID calendarId, @PathVariable int year) {
		return holidays.close(calendarId, year);
	}

	@DeleteMapping("/year/{year}/close")
	public Map<String, Object> reopen(@PathVariable UUID calendarId, @PathVariable int year) {
		return holidays.reopen(calendarId, year);
	}
}
