package com.ivoyant.hrms.assets.api;

import com.ivoyant.hrms.assets.service.HardwareService;
import com.ivoyant.hrms.common.web.StatusException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** The hardware register. Viewing needs View on the assets module; creating, editing and every action need Edit. */
@RestController
@RequestMapping("/api/v1/assets/hardware")
public class HardwareController {

	private static final String VIEW = "@moduleAccess.has('assets', 'VIEW')";
	private static final String EDIT = "@moduleAccess.has('assets', 'EDIT')";

	private final HardwareService service;

	public HardwareController(HardwareService service) {
		this.service = service;
	}

	@GetMapping("/dashboard")
	@PreAuthorize(VIEW)
	public Map<String, Object> dashboard() {
		return service.dashboard();
	}

	@GetMapping
	@PreAuthorize(VIEW)
	public Map<String, Object> list(@RequestParam(required = false) String q, @RequestParam(required = false) UUID typeId, @RequestParam(required = false) UUID statusId,
			@RequestParam(required = false) UUID categoryId, @RequestParam(required = false) UUID locationId, @RequestParam(required = false) UUID employeeId,
			@RequestParam(required = false) String warranty, @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "25") int size,
			@RequestParam(required = false) String sort) {
		return service.list(q, typeId, statusId, categoryId, locationId, employeeId, warranty, page, size, sort);
	}

	@GetMapping("/available")
	@PreAuthorize(VIEW)
	public Map<String, Object> available() {
		return service.available();
	}

	@GetMapping("/{id}")
	@PreAuthorize(VIEW)
	public Map<String, Object> get(@PathVariable UUID id) {
		return service.detail(id);
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

	@PostMapping("/{id}/assign")
	@PreAuthorize(EDIT)
	public Map<String, Object> assign(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
		return service.assign(id, body);
	}

	@PostMapping("/{id}/recover")
	@PreAuthorize(EDIT)
	public Map<String, Object> recover(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> body) {
		return service.recover(id, body == null ? Map.of() : body);
	}

	@PostMapping("/{id}/repair")
	@PreAuthorize(EDIT)
	public Map<String, Object> repair(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> body) {
		return service.repair(id, body == null ? Map.of() : body);
	}

	@PostMapping("/{id}/repaired")
	@PreAuthorize(EDIT)
	public Map<String, Object> repaired(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> body) {
		return service.repaired(id, body == null ? Map.of() : body);
	}

	@PostMapping("/{id}/retire")
	@PreAuthorize(EDIT)
	public Map<String, Object> retire(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
		return service.retire(id, body);
	}

	@PostMapping("/{id}/files")
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize(EDIT)
	public Map<String, Object> addFile(@PathVariable UUID id, @RequestParam("file") MultipartFile file) {
		try {
			return service.addFile(id, file.getOriginalFilename(), file.getContentType(), file.getBytes());
		}
		catch (IOException ex) {
			throw new StatusException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "The file could not be read");
		}
	}

	@GetMapping("/{id}/files/{fileId}")
	@PreAuthorize(VIEW)
	public ResponseEntity<byte[]> file(@PathVariable UUID id, @PathVariable UUID fileId) {
		Object[] f = service.file(id, fileId);
		MediaType type;
		try {
			type = MediaType.parseMediaType((String) f[2]);
		}
		catch (RuntimeException ex) {
			type = MediaType.APPLICATION_OCTET_STREAM;
		}
		return ResponseEntity.ok().contentType(type)
				.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename((String) f[1], StandardCharsets.UTF_8).build().toString())
				.header("X-Content-Type-Options", "nosniff").body((byte[]) f[0]);
	}

	@DeleteMapping("/{id}/files/{fileId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@PreAuthorize(EDIT)
	public void deleteFile(@PathVariable UUID id, @PathVariable UUID fileId) {
		service.deleteFile(id, fileId);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@PreAuthorize(EDIT)
	public void delete(@PathVariable UUID id) {
		service.delete(id);
	}
}
