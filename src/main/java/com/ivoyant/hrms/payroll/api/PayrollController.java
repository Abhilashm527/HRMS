package com.ivoyant.hrms.payroll.api;

import com.ivoyant.hrms.common.authz.RequiresMember;
import com.ivoyant.hrms.payroll.service.PayrollService;
import com.ivoyant.hrms.payroll.service.PayrollService.FileContent;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Payroll runs, salaries and payslips. Payroll access levels are checked in {@link PayrollService}; {@code /me} is each person's own. */
@RestController
@RequestMapping("/api/v1/payroll")
@RequiresMember
public class PayrollController {

	private final PayrollService payroll;

	public PayrollController(PayrollService payroll) {
		this.payroll = payroll;
	}

	@GetMapping("/settings")
	public Map<String, Object> settings() {
		return payroll.settingsView();
	}

	@PutMapping("/settings")
	public Map<String, Object> saveSettings(@RequestBody Map<String, Object> body) {
		return payroll.saveSettings(body);
	}

	@GetMapping("/salaries")
	public Map<String, Object> salaries() {
		return payroll.salaries();
	}

	@PutMapping("/salaries/{employeeId}")
	public Map<String, Object> saveSalary(@PathVariable UUID employeeId, @RequestBody Map<String, Object> body) {
		return payroll.saveSalary(employeeId, body);
	}

	@GetMapping("/runs")
	public Map<String, Object> runs() {
		return payroll.runs();
	}

	/** Body: {@code {month: "2026-11"}}. */
	@PostMapping("/runs")
	@ResponseStatus(HttpStatus.CREATED)
	public Map<String, Object> create(@RequestBody Map<String, Object> body) {
		return payroll.createRun(String.valueOf(body.get("month")));
	}

	@GetMapping("/runs/{id}")
	public Map<String, Object> run(@PathVariable UUID id) {
		return payroll.run(id);
	}

	@DeleteMapping("/runs/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable UUID id) {
		payroll.deleteRun(id);
	}

	@PostMapping("/runs/{id}/recalculate")
	public Map<String, Object> recalc(@PathVariable UUID id) {
		return payroll.recalc(id);
	}

	@PostMapping("/runs/{id}/advance")
	public Map<String, Object> advance(@PathVariable UUID id) {
		return payroll.advance(id);
	}

	@PostMapping("/runs/{id}/reopen")
	public Map<String, Object> reopen(@PathVariable UUID id) {
		return payroll.reopen(id);
	}

	@GetMapping("/runs/{id}/statutory")
	public Map<String, Object> statutory(@PathVariable UUID id) {
		return payroll.statutory(id);
	}

	/** {@code kind} = pf, esi, pt, tds or register. */
	@GetMapping("/runs/{id}/export/{kind}")
	public ResponseEntity<byte[]> export(@PathVariable UUID id, @PathVariable String kind) {
		return send(payroll.export(id, kind), false);
	}

	@GetMapping("/annual")
	public Map<String, Object> annual(@RequestParam int fy) {
		return payroll.annualAll(fy);
	}

	@GetMapping("/slips/{id}")
	public Map<String, Object> slip(@PathVariable UUID id) {
		return payroll.slip(id);
	}

	/** Body: {@code {adjustments: [{label, amount, kind}], extraLop}}. */
	@PutMapping("/slips/{id}")
	public Map<String, Object> adjust(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
		return payroll.adjust(id, body);
	}

	@GetMapping("/slips/{id}/pdf")
	public ResponseEntity<byte[]> pdf(@PathVariable UUID id) {
		return send(payroll.payslipPdf(id), false);
	}

	@GetMapping("/me")
	public Map<String, Object> mine() {
		return payroll.mine();
	}

	@GetMapping("/statement")
	public ResponseEntity<byte[]> statement(@RequestParam int fy, @RequestParam(required = false) UUID employeeId) {
		return send(payroll.statementPdf(fy, employeeId), false);
	}

	private static ResponseEntity<byte[]> send(FileContent f, boolean inline) {
		return ResponseEntity.ok().contentType(MediaType.parseMediaType(f.mime()))
				.header(HttpHeaders.CONTENT_DISPOSITION, (inline ? ContentDisposition.inline() : ContentDisposition.attachment()).filename(f.name(), StandardCharsets.UTF_8)
						.build().toString())
				.header("X-Content-Type-Options", "nosniff").header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(f.bytes());
	}
}
