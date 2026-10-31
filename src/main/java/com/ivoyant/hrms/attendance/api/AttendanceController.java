package com.ivoyant.hrms.attendance.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ivoyant.hrms.attendance.service.AttendanceService;
import com.ivoyant.hrms.common.authz.RequiresMember;

/** The attendance board for a day, the person's own check-in and check-out, and HR corrections. */
@RestController
@RequestMapping("/api/v1/attendance")
@RequiresMember
public class AttendanceController {

	private final AttendanceService attendance;

	public AttendanceController(AttendanceService attendance) {
		this.attendance = attendance;
	}

	@GetMapping("/board")
	public Map<String, Object> board(@RequestParam(required = false) LocalDate date, @RequestParam(required = false) String q, @RequestParam(required = false) UUID departmentId,
			@RequestParam(required = false) UUID locationId, @RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return attendance.board(date, q, departmentId, locationId, status, page, size);
	}

	@GetMapping("/holidays")
	public List<Map<String, Object>> holidays(@RequestParam int year) {
		return attendance.holidays(year);
	}

	@GetMapping("/me")
	public Map<String, Object> me() {
		return attendance.me();
	}

	@PostMapping("/check-in")
	public Map<String, Object> checkIn(@RequestBody(required = false) Map<String, Object> body) {
		return attendance.checkIn(body);
	}

	@PostMapping("/check-out")
	public Map<String, Object> checkOut() {
		return attendance.checkOut();
	}

	/** {@code {in, out, wfh, note}} */
	@PutMapping("/{employeeId}/{day}")
	public Map<String, Object> correct(@PathVariable UUID employeeId, @PathVariable LocalDate day, @RequestBody Map<String, Object> body) {
		return attendance.correct(employeeId, day, body);
	}
}
