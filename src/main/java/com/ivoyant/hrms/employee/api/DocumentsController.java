package com.ivoyant.hrms.employee.api;

import com.ivoyant.hrms.common.authz.RequiresMember;
import com.ivoyant.hrms.common.web.StatusException;
import com.ivoyant.hrms.employee.service.EmployeeDocumentService;
import com.ivoyant.hrms.employee.service.PrevEmploymentService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** The Documents tab: documents on file and previous employers. */
@RestController
@RequestMapping("/api/v1/employees/{id}")
public class DocumentsController {

	private final EmployeeDocumentService documents;
	private final PrevEmploymentService previous;

	public DocumentsController(EmployeeDocumentService documents, PrevEmploymentService previous) {
		this.documents = documents;
		this.previous = previous;
	}

	@GetMapping("/documents")
	@RequiresMember
	public List<Map<String, Object>> list(@PathVariable UUID id) {
		return documents.list(id);
	}

	@PostMapping("/documents")
	@ResponseStatus(HttpStatus.CREATED)
	@RequiresMember
	public Map<String, Object> upload(@PathVariable UUID id, @RequestParam("file") MultipartFile file, @RequestParam(required = false) String name,
			@RequestParam(required = false) String category) {
		try {
			return documents.upload(id, name, category, file.getOriginalFilename(), file.getBytes());
		}
		catch (IOException ex) {
			throw new StatusException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "The file could not be read");
		}
	}

	@GetMapping("/documents/{docId}/file")
	@RequiresMember
	public ResponseEntity<byte[]> file(@PathVariable UUID id, @PathVariable UUID docId) {
		EmployeeDocumentService.FileContent f = documents.file(id, docId);
		return ResponseEntity.ok().contentType(MediaType.parseMediaType(f.mime()))
				.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(f.name(), StandardCharsets.UTF_8).build().toString())
				.header("X-Content-Type-Options", "nosniff").body(f.bytes());
	}

	@PostMapping("/documents/{docId}/verify")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@RequiresMember
	public void verify(@PathVariable UUID id, @PathVariable UUID docId) {
		documents.verify(id, docId);
	}

	@PostMapping("/documents/{docId}/reject")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@RequiresMember
	public void reject(@PathVariable UUID id, @PathVariable UUID docId, @RequestBody Map<String, String> body) {
		documents.reject(id, docId, body.get("reason"));
	}

	@DeleteMapping("/documents/{docId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@RequiresMember
	public void delete(@PathVariable UUID id, @PathVariable UUID docId) {
		documents.delete(id, docId);
	}

	@GetMapping("/previous-employment")
	@RequiresMember
	public List<Map<String, Object>> previous(@PathVariable UUID id) {
		return previous.list(id);
	}

	@PostMapping("/previous-employment")
	@ResponseStatus(HttpStatus.CREATED)
	@RequiresMember
	public Map<String, Object> addPrevious(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
		return previous.create(id, body);
	}

	@PutMapping("/previous-employment/{pid}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@RequiresMember
	public void updatePrevious(@PathVariable UUID id, @PathVariable UUID pid, @RequestBody Map<String, Object> body) {
		previous.update(id, pid, body);
	}

	@DeleteMapping("/previous-employment/{pid}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@RequiresMember
	public void deletePrevious(@PathVariable UUID id, @PathVariable UUID pid) {
		previous.delete(id, pid);
	}

	@PostMapping("/previous-employment/{pid}/verify")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@RequiresMember
	public void verifyPrevious(@PathVariable UUID id, @PathVariable UUID pid) {
		previous.verify(id, pid);
	}
}
