package com.ivoyant.hrms.recruiting.api;

import com.ivoyant.hrms.common.authz.RequiresMember;
import com.ivoyant.hrms.recruiting.service.RecruitingService;
import com.ivoyant.hrms.recruiting.service.RecruitingService.FileContent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Jobs, candidates, interviews and offers. Who may do what is checked in {@link RecruitingService}. */
@RestController
@RequestMapping("/api/v1/recruiting")
@RequiresMember
public class RecruitingController {

	private final RecruitingService rec;

	public RecruitingController(RecruitingService rec) {
		this.rec = rec;
	}

	@GetMapping("/overview")
	public Map<String, Object> overview() {
		return rec.overview();
	}

	@GetMapping("/jobs")
	public Map<String, Object> jobs() {
		return rec.jobs();
	}

	@PostMapping("/jobs")
	@ResponseStatus(HttpStatus.CREATED)
	public Map<String, Object> createJob(@RequestBody Map<String, Object> body) {
		return rec.saveJob(null, body);
	}

	@PutMapping("/jobs/{id}")
	public Map<String, Object> updateJob(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
		return rec.saveJob(id, body);
	}

	@GetMapping("/candidates")
	public Map<String, Object> pipeline(@RequestParam(required = false) UUID jobId) {
		return rec.pipeline(jobId);
	}

	/** Multipart: the candidate's fields plus an optional {@code resume} file. */
	@PostMapping(path = "/candidates", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	public Map<String, Object> add(@RequestParam Map<String, String> fields, @RequestParam(value = "resume", required = false) MultipartFile resume) throws IOException {
		Map<String, Object> b = new LinkedHashMap<>(fields);
		b.remove("resume");
		return rec.addCandidate(b, resume == null ? null : resume.getOriginalFilename(), resume == null ? null : resume.getBytes());
	}

	@GetMapping("/candidates/{id}")
	public Map<String, Object> candidate(@PathVariable UUID id) {
		return rec.candidate(id);
	}

	@PutMapping("/candidates/{id}")
	public Map<String, Object> update(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
		return rec.updateCandidate(id, body);
	}

	@PostMapping("/candidates/{id}/stage")
	public Map<String, Object> move(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
		return rec.move(id, body);
	}

	@PostMapping(path = "/candidates/{id}/resume", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public Map<String, Object> resume(@PathVariable UUID id, @RequestParam("resume") MultipartFile file) throws IOException {
		return rec.uploadResume(id, file.getOriginalFilename(), file.getBytes());
	}

	@GetMapping("/candidates/{id}/resume")
	public ResponseEntity<byte[]> resumeFile(@PathVariable UUID id) {
		FileContent f = rec.resume(id);
		boolean pdf = f.mime().equals("application/pdf");
		return ResponseEntity.ok().contentType(MediaType.parseMediaType(f.mime()))
				.header(HttpHeaders.CONTENT_DISPOSITION,
						(pdf ? ContentDisposition.inline() : ContentDisposition.attachment()).filename(f.name(), StandardCharsets.UTF_8).build().toString())
				.header("X-Content-Type-Options", "nosniff").header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(f.bytes());
	}

	@PostMapping("/candidates/{id}/interviews")
	public Map<String, Object> schedule(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
		return rec.schedule(id, body);
	}

	@PostMapping("/candidates/{id}/offers")
	public Map<String, Object> offer(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
		return rec.offer(id, body);
	}

	@GetMapping("/interviews")
	public Map<String, Object> interviews() {
		return rec.allInterviews();
	}

	/** Body: {@code {status: SCHEDULED | DONE | CANCELLED}}. */
	@PostMapping("/interviews/{id}/status")
	public Map<String, Object> interviewStatus(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
		return rec.setInterviewStatus(id, String.valueOf(body.get("status")));
	}

	@PostMapping("/interviews/{id}/feedback")
	public Map<String, Object> feedback(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
		return rec.giveFeedback(id, body);
	}

	@GetMapping("/me/interviews")
	public Map<String, Object> mine() {
		return rec.myInterviews();
	}

	@GetMapping("/offers")
	public Map<String, Object> offers() {
		return rec.offers();
	}

	/** Body: {@code {status: ACCEPTED | DECLINED | WITHDRAWN}}. */
	@PostMapping("/offers/{id}/decision")
	public Map<String, Object> decide(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
		return rec.decideOffer(id, String.valueOf(body.get("status")));
	}
}
