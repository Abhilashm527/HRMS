package com.ivoyant.hrms.assets.api;

import com.ivoyant.hrms.assets.service.LicenseService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Software licences and their seats. Viewing needs View on the assets module; changes need Edit. */
@RestController
@RequestMapping("/api/v1/assets/software")
public class SoftwareController {

	private static final String VIEW = "@moduleAccess.has('assets', 'VIEW')";
	private static final String EDIT = "@moduleAccess.has('assets', 'EDIT')";

	private final LicenseService service;

	public SoftwareController(LicenseService service) {
		this.service = service;
	}

	@GetMapping("/dashboard")
	@PreAuthorize(VIEW)
	public Map<String, Object> dashboard(@RequestParam(required = false) UUID categoryId) {
		return service.dashboard(categoryId);
	}

	/** {@code state}: ACTIVE, RENEWAL_DUE, EXPIRED, FULLY_USED, UNDERUSED or a manual status id. */
	@GetMapping
	@PreAuthorize(VIEW)
	public Map<String, Object> list(@RequestParam(required = false) String q, @RequestParam(required = false) UUID categoryId, @RequestParam(required = false) String state,
			@RequestParam(required = false) UUID ownerId, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "25") int size,
			@RequestParam(required = false) String sort) {
		return service.list(q, categoryId, state, ownerId, page, size, sort);
	}

	@GetMapping("/available")
	@PreAuthorize(VIEW)
	public Map<String, Object> available() {
		return service.available();
	}

	@GetMapping("/preview-id")
	@PreAuthorize(EDIT)
	public Map<String, Object> previewId(@RequestParam UUID typeId, @RequestParam(required = false) String name) {
		return service.previewId(typeId, name);
	}

	@GetMapping("/seat-candidates")
	@PreAuthorize(EDIT)
	public List<Map<String, Object>> candidates(@RequestParam(required = false) UUID categoryId, @RequestParam(required = false) String q) {
		return service.seatCandidates(categoryId, q);
	}

	@GetMapping("/{id}")
	@PreAuthorize(VIEW)
	public Map<String, Object> get(@PathVariable UUID id) {
		return service.detail(id);
	}

	@GetMapping("/{id}/seats")
	@PreAuthorize(VIEW)
	public List<Map<String, Object>> seats(@PathVariable UUID id, @RequestParam(required = false) String q) {
		return service.seats(id, q);
	}

	@GetMapping("/{id}/timeline")
	@PreAuthorize(VIEW)
	public Object timeline(@PathVariable UUID id, @RequestParam(required = false) String q, @RequestParam(required = false) String kind,
			@RequestParam(defaultValue = "desc") String order, @RequestParam(defaultValue = "0") int page, @RequestParam(required = false) String format) {
		Object out = service.timeline(id, q, kind, order, page, "csv".equalsIgnoreCase(format));
		if (out instanceof String csv) {
			return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv"))
					.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("timeline.csv").build().toString()).body(csv.getBytes(StandardCharsets.UTF_8));
		}
		return out;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize(EDIT)
	public Map<String, Object> create(@RequestBody Map<String, Object> body) {
		return service.create(body, false);
	}

	@PutMapping("/{id}")
	@PreAuthorize(EDIT)
	public Map<String, Object> update(@PathVariable UUID id, @RequestBody Map<String, Object> body, @RequestHeader(value = "If-Match", required = false) String ifMatch) {
		return service.update(id, body, ifMatch);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@PreAuthorize(EDIT)
	public void delete(@PathVariable UUID id) {
		service.delete(id);
	}

	/** {@code {employeeIds: [], requestId?}}: all or nothing; 409 when there are not enough free seats. */
	@PostMapping("/{id}/seats")
	@PreAuthorize(EDIT)
	public List<Map<String, Object>> assign(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
		return service.assignSeats(id, body);
	}

	@DeleteMapping("/{id}/seats/{employeeId}")
	@PreAuthorize(EDIT)
	public List<Map<String, Object>> revoke(@PathVariable UUID id, @PathVariable UUID employeeId) {
		return service.revokeSeat(id, employeeId, null);
	}

	@PostMapping("/{id}/seats/add")
	@PreAuthorize(EDIT)
	public Map<String, Object> addSeats(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
		return service.addSeats(id, body);
	}

	@PostMapping("/{id}/renew")
	@PreAuthorize(EDIT)
	public Map<String, Object> renew(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> body) {
		return service.renew(id, body == null ? Map.of() : body);
	}

	@PatchMapping("/{id}/auto-renew")
	@PreAuthorize(EDIT)
	public Map<String, Object> autoRenew(@PathVariable UUID id, @RequestBody Map<String, Boolean> body) {
		return service.autoRenew(id, Boolean.TRUE.equals(body.get("on")));
	}
}
