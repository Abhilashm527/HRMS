package com.ivoyant.hrms.reports.api;

import com.ivoyant.hrms.common.authz.RequiresMember;
import com.ivoyant.hrms.reports.service.ReportService;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Ready-made reports; each one checks View on its own module. */
@RestController
@RequestMapping("/api/v1/reports")
@RequiresMember
public class ReportController {

	private final ReportService reports;

	public ReportController(ReportService reports) {
		this.reports = reports;
	}

	@GetMapping
	public Map<String, Object> catalog() {
		return reports.catalog();
	}

	@GetMapping("/{key}")
	public Map<String, Object> run(@PathVariable String key, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		return reports.run(key, from, to);
	}

	@GetMapping("/{key}/csv")
	public ResponseEntity<byte[]> csv(@PathVariable String key, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		String body = reports.csv(key, from, to);
		String name = key + "-" + LocalDate.now() + ".csv";
		return ResponseEntity.ok().contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
				.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
				.header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(body.getBytes(StandardCharsets.UTF_8));
	}
}
