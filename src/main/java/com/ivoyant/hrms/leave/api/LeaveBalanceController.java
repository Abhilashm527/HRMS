package com.ivoyant.hrms.leave.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ivoyant.hrms.common.authz.RequiresMember;
import com.ivoyant.hrms.leave.service.LeaveEncashService;
import com.ivoyant.hrms.leave.service.LeaveLedgerService;
import com.ivoyant.hrms.leave.service.LeaveNoticeService;

/** Balances, the per-person leave history (ledger), encashment, year-end and leave notifications. */
@RestController
@RequestMapping("/api/v1/leave")
@RequiresMember
public class LeaveBalanceController {

	private final LeaveLedgerService ledger;
	private final LeaveEncashService encash;
	private final LeaveNoticeService notices;

	public LeaveBalanceController(LeaveLedgerService ledger, LeaveEncashService encash, LeaveNoticeService notices) {
		this.ledger = ledger;
		this.encash = encash;
		this.notices = notices;
	}

	private static int year(Integer y) {
		return y == null ? LocalDate.now().getYear() : y;
	}

	// ---------------------------------------------------------------- balances and history

	@GetMapping("/balances")
	public Map<String, Object> balances(@RequestParam(required = false) String q, @RequestParam(required = false) UUID departmentId,
			@RequestParam(required = false) UUID locationId, @RequestParam(required = false) String filter, @RequestParam(required = false) Integer year,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return ledger.board(q, departmentId, locationId, filter, year(year), page, Math.max(1, Math.min(size, 100)));
	}

	@GetMapping("/ledger/people")
	public List<Map<String, Object>> people(@RequestParam(required = false) String q, @RequestParam(defaultValue = "false") boolean exited) {
		return ledger.people(q, exited);
	}

	@GetMapping("/ledger/{employeeId}")
	public Map<String, Object> ledger(@PathVariable UUID employeeId, @RequestParam(required = false) Integer year) {
		return ledger.ledger(employeeId, year(year));
	}

	/** {@code {typeCode, year, direction: CREDIT|DEBIT, days, reason}} */
	@PostMapping("/ledger/{employeeId}/adjust")
	public Map<String, Object> adjust(@PathVariable UUID employeeId, @RequestBody Map<String, Object> body) {
		return ledger.adjust(employeeId, body);
	}

	// ---------------------------------------------------------------- encashment

	@GetMapping("/encashments")
	public Map<String, Object> encashments(@RequestParam(required = false) String status) {
		return encash.list(status);
	}

	@GetMapping("/encashments/options")
	public Map<String, Object> encashOptions(@RequestParam(required = false) UUID employeeId, @RequestParam(required = false) String kind) {
		return encash.options(employeeId, kind);
	}

	@PostMapping("/encashments")
	public Map<String, Object> createEncashment(@RequestBody Map<String, Object> body) {
		return encash.create(body);
	}

	/** {@code action}: approve, reject or paid. */
	@PostMapping("/encashments/{id}/{action}")
	public Map<String, Object> decideEncashment(@PathVariable UUID id, @PathVariable String action, @RequestBody(required = false) Map<String, Object> body) {
		return encash.decide(id, action, body);
	}

	// ---------------------------------------------------------------- year-end

	@GetMapping("/year-end/{year}")
	public Map<String, Object> yearEnd(@PathVariable int year, @RequestParam(required = false) String type, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "10") int size) {
		return encash.yearEnd(year, type, page, size);
	}

	/** {@code {encash: boolean}} */
	@PostMapping("/year-end/{year}/close")
	public Map<String, Object> close(@PathVariable int year, @RequestBody(required = false) Map<String, Object> body) {
		return encash.runYearEnd(year, body);
	}

	@DeleteMapping("/year-end/{year}/close")
	public Map<String, Object> reopen(@PathVariable int year) {
		return encash.reopen(year);
	}

	// ---------------------------------------------------------------- notifications

	@GetMapping("/notices")
	public Map<String, Object> notices() {
		return notices.list();
	}

	@GetMapping("/notices/{id}")
	public Map<String, Object> notice(@PathVariable UUID id) {
		return notices.get(id);
	}

	@PostMapping("/notices/preview")
	public Map<String, Object> preview(@RequestBody Map<String, Object> body) {
		return notices.preview(body);
	}

	@PostMapping("/notices")
	public Map<String, Object> createNotice(@RequestBody Map<String, Object> body) {
		return notices.save(null, body);
	}

	@PutMapping("/notices/{id}")
	public Map<String, Object> updateNotice(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
		return notices.save(id, body);
	}

	@PostMapping("/notices/{id}/cancel")
	public Map<String, Object> cancelNotice(@PathVariable UUID id) {
		return notices.cancel(id);
	}

	@PostMapping("/notices/{id}/send")
	public Map<String, Object> sendNotice(@PathVariable UUID id) {
		return notices.sendNow(id);
	}
}
