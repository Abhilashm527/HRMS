package com.ivoyant.hrms.policy.api;

import com.ivoyant.hrms.common.authz.RequiresMember;
import com.ivoyant.hrms.policy.service.PolicyService;
import com.ivoyant.hrms.policy.service.PolicyService.FileContent;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Policies: HR writes and publishes them, employees read and acknowledge them. Who may do what is checked in {@link PolicyService}. */
@RestController
@RequestMapping("/api/v1/policies")
@RequiresMember
public class PolicyController {

	private final PolicyService policies;

	public PolicyController(PolicyService policies) {
		this.policies = policies;
	}

	// ---- HR

	@GetMapping
	public Map<String, Object> list() {
		return policies.list();
	}

	@GetMapping("/new-options")
	public Map<String, Object> newOptions() {
		return policies.newOptions();
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public Map<String, Object> create(@RequestBody Map<String, Object> body) {
		return policies.create(body);
	}

	@GetMapping("/{id}")
	public Map<String, Object> detail(@PathVariable UUID id) {
		return policies.detail(id);
	}

	@PatchMapping("/{id}")
	public Map<String, Object> update(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
		return policies.update(id, body);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable UUID id) {
		policies.delete(id);
	}

	@PostMapping("/{id}/draft")
	public Map<String, Object> startDraft(@PathVariable UUID id) {
		return policies.startDraft(id);
	}

	@PutMapping("/{id}/draft")
	public Map<String, Object> saveDraft(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
		return policies.saveDraft(id, body);
	}

	@DeleteMapping("/{id}/draft")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void discardDraft(@PathVariable UUID id) {
		policies.discardDraft(id);
	}

	@PostMapping("/{id}/submit")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void submit(@PathVariable UUID id) {
		policies.submit(id);
	}

	@PostMapping("/{id}/approve")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void approve(@PathVariable UUID id) {
		policies.approve(id);
	}

	/** Body: {@code {major, note, requireAck}}. */
	@PostMapping("/{id}/publish")
	public Map<String, Object> publish(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
		return policies.publish(id, body);
	}

	@PostMapping("/{id}/reviewed")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void reviewed(@PathVariable UUID id) {
		policies.markReviewed(id);
	}

	@PostMapping("/{id}/archive")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void archive(@PathVariable UUID id) {
		policies.archive(id, true);
	}

	@PostMapping("/{id}/restore")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void restore(@PathVariable UUID id) {
		policies.archive(id, false);
	}

	/** {@code version} = a version number, {@code draft}, or empty for the current version. */
	@GetMapping("/{id}/pdf")
	public ResponseEntity<byte[]> pdf(@PathVariable UUID id, @RequestParam(required = false) String version) {
		FileContent f = policies.pdf(id, version);
		return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
				.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(f.name(), java.nio.charset.StandardCharsets.UTF_8).build().toString())
				.header("X-Content-Type-Options", "nosniff").body(f.bytes());
	}

	@GetMapping("/categories")
	public List<Map<String, Object>> categories() {
		return policies.categories();
	}

	@PostMapping("/categories")
	@ResponseStatus(HttpStatus.CREATED)
	public Map<String, Object> addCategory(@RequestBody Map<String, Object> body) {
		return policies.saveCategory(null, body);
	}

	@PatchMapping("/categories/{id}")
	public Map<String, Object> updateCategory(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
		return policies.saveCategory(id, body);
	}

	@DeleteMapping("/categories/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteCategory(@PathVariable UUID id) {
		policies.deleteCategory(id);
	}

	// ---- employees

	@GetMapping("/mine")
	public Map<String, Object> mine() {
		return policies.mine();
	}

	@GetMapping("/{id}/read")
	public Map<String, Object> read(@PathVariable UUID id) {
		return policies.read(id);
	}

	@PostMapping("/{id}/acknowledge")
	public Map<String, Object> acknowledge(@PathVariable UUID id) {
		return policies.acknowledge(id);
	}
}
