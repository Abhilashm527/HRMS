package com.ivoyant.hrms.employee.api;

import com.ivoyant.hrms.common.authz.RequiresMember;
import com.ivoyant.hrms.common.web.StatusException;
import com.ivoyant.hrms.employee.service.ExportService;
import com.ivoyant.hrms.employee.service.ImportService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Bulk export and import of employees. */
@RestController
public class ExportImportController {

	private final ExportService export;
	private final ImportService importer;

	public ExportImportController(ExportService export, ImportService importer) {
		this.export = export;
		this.importer = importer;
	}

	@PostMapping("/api/v1/employees/export")
	@RequiresMember
	public ResponseEntity<?> export(@RequestBody Map<String, Object> body) {
		ExportService.Result r = export.export(body);
		ResponseEntity.BodyBuilder b = r.csv() != null ? ResponseEntity.ok() : ResponseEntity.status(HttpStatus.ACCEPTED);
		if (!r.dropped().isEmpty()) {
			b.header("X-Dropped-Columns", String.join(",", r.dropped()));
		}
		if (r.csv() != null) {
			return b.contentType(MediaType.parseMediaType("text/csv"))
					.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("employees.csv").build().toString()).body(r.csv());
		}
		return b.body(Map.of("jobId", r.jobId(), "rows", r.rows(), "state", "DONE"));
	}

	@GetMapping("/api/v1/exports/{jobId}")
	@RequiresMember
	public Map<String, Object> job(@PathVariable UUID jobId) {
		return export.job(jobId);
	}

	@GetMapping("/api/v1/exports/{jobId}/file")
	@RequiresMember
	public ResponseEntity<byte[]> file(@PathVariable UUID jobId) {
		return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv"))
				.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("employees.csv").build().toString()).body(export.file(jobId));
	}

	@PostMapping("/api/v1/employees/import")
	@RequiresMember
	public Map<String, Object> importCsv(@RequestParam("file") MultipartFile file, @RequestParam(defaultValue = "true") boolean dryRun) {
		try {
			return importer.run(new String(file.getBytes(), StandardCharsets.UTF_8), dryRun);
		}
		catch (IOException ex) {
			throw new StatusException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "The file could not be read");
		}
	}
}
