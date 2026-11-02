package com.ivoyant.hrms.reports.api;

import com.ivoyant.hrms.common.authz.RequiresMember;
import com.ivoyant.hrms.reports.service.ReportService;
import com.ivoyant.hrms.reports.service.ReportService.FileOut;
import com.ivoyant.hrms.reports.service.ReportService.Params;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Report library, exports, export history and schedules. Access is checked per report in {@link ReportService}. */
@RestController
@RequestMapping("/api/v1/reports")
@RequiresMember
public class ReportController {

	private final ReportService reports;

	public ReportController(ReportService reports) {
		this.reports = reports;
	}

	@GetMapping
	public Map<String, Object> home() {
		return reports.home();
	}

	/**
	 * Body: {@code from, to, asof, label, dept, loc, scope (active|all|exited), cycle, format, columns: [index], mask}. Returns the export's id; fetch
	 * the file from {@code /exports/{id}/file}.
	 */
	@PostMapping("/{key}/export")
	public Map<String, Object> export(@PathVariable String key, @RequestBody Map<String, Object> b) {
		Params p = new Params(date(b.get("from")), date(b.get("to")), date(b.get("asof")), str(b.get("label")), uuid(b.get("dept")), uuid(b.get("loc")), str(b.get("scope")),
				uuid(b.get("cycle")));
		List<Integer> cols = new ArrayList<>();
		if (b.get("columns") instanceof List<?> l) {
			l.forEach(x -> cols.add(Integer.valueOf(String.valueOf(x))));
		}
		Map<String, Object> out = reports.export(key, p, String.valueOf(b.getOrDefault("format", "CSV")), cols, !Boolean.FALSE.equals(b.get("mask")));
		return Map.of("id", out.get("id"), "rows", out.get("rows"), "name", out.get("name"));
	}

	@GetMapping("/exports")
	public List<Map<String, Object>> history() {
		return reports.history(200);
	}

	@GetMapping("/exports/{id}/file")
	public ResponseEntity<byte[]> file(@PathVariable UUID id) {
		FileOut f = reports.file(id);
		return ResponseEntity.ok().contentType(MediaType.parseMediaType(f.mime()))
				.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(f.name(), StandardCharsets.UTF_8).build().toString())
				.header("X-Content-Type-Options", "nosniff").header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(f.bytes());
	}

	@GetMapping("/schedules")
	public List<Map<String, Object>> schedules() {
		return reports.schedules();
	}

	@PostMapping("/schedules")
	@ResponseStatus(HttpStatus.CREATED)
	public Map<String, Object> create(@RequestBody Map<String, Object> b) {
		return reports.saveSchedule(null, b);
	}

	@PutMapping("/schedules/{id}")
	public Map<String, Object> update(@PathVariable UUID id, @RequestBody Map<String, Object> b) {
		return reports.saveSchedule(id, b);
	}

	/** Body: {@code {enabled}}. */
	@PostMapping("/schedules/{id}/enabled")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void enabled(@PathVariable UUID id, @RequestBody Map<String, Object> b) {
		reports.setEnabled(id, Boolean.TRUE.equals(b.get("enabled")));
	}

	@DeleteMapping("/schedules/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable UUID id) {
		reports.deleteSchedule(id);
	}

	private static String str(Object o) {
		return o == null || String.valueOf(o).isBlank() ? null : String.valueOf(o);
	}

	private static LocalDate date(Object o) {
		try {
			return o == null || String.valueOf(o).isBlank() ? null : LocalDate.parse(String.valueOf(o));
		}
		catch (RuntimeException e) {
			return null;
		}
	}

	private static UUID uuid(Object o) {
		try {
			return o == null || String.valueOf(o).isBlank() ? null : UUID.fromString(String.valueOf(o));
		}
		catch (IllegalArgumentException e) {
			return null;
		}
	}
}
