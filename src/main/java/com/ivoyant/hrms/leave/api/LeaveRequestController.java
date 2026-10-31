package com.ivoyant.hrms.leave.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ivoyant.hrms.common.authz.RequiresMember;
import com.ivoyant.hrms.leave.service.LeaveRequestService;

/** Leave requests: apply, approve in turn, reject, cancel, notes and reminders; the pending list and the decided history. */
@RestController
@RequestMapping("/api/v1/leave")
@RequiresMember
public class LeaveRequestController {

	private final LeaveRequestService requests;

	public LeaveRequestController(LeaveRequestService requests) {
		this.requests = requests;
	}

	@GetMapping("/requests")
	public Map<String, Object> list(@RequestParam(defaultValue = "pending") String view, @RequestParam(required = false) String act, @RequestParam(required = false) String q,
			@RequestParam(required = false) List<String> types, @RequestParam(required = false) UUID departmentId, @RequestParam(required = false) LocalDate from,
			@RequestParam(required = false) LocalDate to, @RequestParam(required = false) String sort, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "10") int size) {
		return requests.list(view, act, q, types, departmentId, from, to, sort, page, size);
	}

	@GetMapping("/requests/{id}")
	public Map<String, Object> get(@PathVariable UUID id) {
		return requests.detail(id);
	}

	@GetMapping("/apply-options")
	public Map<String, Object> applyOptions(@RequestParam(required = false) UUID employeeId) {
		return requests.applyOptions(employeeId);
	}

	/** {@code {employeeId?, typeCode, from, to, half, reason, hasDocument}} */
	@PostMapping("/requests")
	public Map<String, Object> apply(@RequestBody Map<String, Object> body) {
		return requests.apply(body);
	}

	@PostMapping("/requests/{id}/approve")
	public Map<String, Object> approve(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> body) {
		return requests.approve(id, body);
	}

	@PostMapping("/requests/{id}/reject")
	public Map<String, Object> reject(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
		return requests.reject(id, body);
	}

	@PostMapping("/requests/{id}/cancel")
	public Map<String, Object> cancel(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> body) {
		return requests.cancel(id, body);
	}

	@PostMapping("/requests/{id}/notes")
	public Map<String, Object> note(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
		return requests.note(id, body);
	}

	@PostMapping("/requests/{id}/remind")
	public Map<String, Object> remind(@PathVariable UUID id) {
		return requests.remind(id);
	}

	/** A person's own leave: balances and requests for the year. */
	@GetMapping("/me")
	public Map<String, Object> mine(@RequestParam(required = false) UUID employeeId, @RequestParam(required = false) Integer year) {
		return requests.mine(employeeId, year == null ? LocalDate.now().getYear() : year);
	}
}
