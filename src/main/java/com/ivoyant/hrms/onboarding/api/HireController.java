package com.ivoyant.hrms.onboarding.api;

import com.ivoyant.hrms.common.security.ModuleAccess;
import com.ivoyant.hrms.common.web.ForbiddenException;
import com.ivoyant.hrms.onboarding.service.HireDocumentService;
import com.ivoyant.hrms.onboarding.service.HireJoinService;
import com.ivoyant.hrms.onboarding.service.HireQueryService;
import com.ivoyant.hrms.onboarding.service.HireService;
import com.ivoyant.hrms.onboarding.service.InviteService;
import com.ivoyant.hrms.onboarding.service.M365Service;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The onboarding pipeline and a single hire: list/board/export, create, edit, invite, remind, cancel, join, documents,
 * checklist, activity. Viewing needs View on the recruiting module, changing needs Edit, cancel and forced join need Admin.
 */
@RestController
@RequestMapping("/api/v1/onboarding/hires")
public class HireController {

	private static final String VIEW = "@moduleAccess.has('recruiting', 'VIEW')";
	private static final String EDIT = "@moduleAccess.has('recruiting', 'EDIT')";
	private static final String FULL = "@moduleAccess.has('recruiting', 'ADMIN')";

	private final HireService hires;
	private final HireQueryService query;
	private final InviteService invites;
	private final HireDocumentService documents;
	private final M365Service m365;
	private final HireJoinService join;
	private final ModuleAccess access;

	public HireController(HireService hires, HireQueryService query, InviteService invites, HireDocumentService documents, M365Service m365,
			HireJoinService join, ModuleAccess access) {
		this.access = access;
		this.hires = hires;
		this.query = query;
		this.invites = invites;
		this.documents = documents;
		this.m365 = m365;
		this.join = join;
	}

	private static HireQueryService.Filter filter(String q, String status, UUID departmentId, UUID locationId, String window, String sort, int page,
			int size) {
		return new HireQueryService.Filter(q, status, departmentId, locationId, window, sort, page, size);
	}

	// ---- pipeline ----

	@GetMapping
	@PreAuthorize(VIEW)
	public Map<String, Object> list(@RequestParam(required = false) String q, @RequestParam(required = false) String status,
			@RequestParam(required = false) UUID departmentId, @RequestParam(required = false) UUID locationId,
			@RequestParam(required = false) String window, @RequestParam(required = false) String sort,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "25") int size) {
		return query.list(filter(q, status, departmentId, locationId, window, sort, page, size));
	}

	@GetMapping("/board")
	@PreAuthorize(VIEW)
	public Map<String, Object> board(@RequestParam(required = false) String q, @RequestParam(required = false) UUID departmentId,
			@RequestParam(required = false) UUID locationId, @RequestParam(required = false) String window, @RequestParam(required = false) String sort) {
		return query.board(filter(q, null, departmentId, locationId, window, sort, 0, 0));
	}

	@PostMapping("/bulk/remind")
	@PreAuthorize(EDIT)
	public Map<String, Object> bulkRemind(@RequestBody Map<String, List<UUID>> body) {
		return invites.bulkRemind(body.getOrDefault("ids", List.of()));
	}

	@PostMapping("/bulk/invite")
	@PreAuthorize(EDIT)
	public Map<String, Object> bulkInvite(@RequestBody Map<String, List<UUID>> body) {
		return invites.bulkInvite(body.getOrDefault("ids", List.of()));
	}

	@GetMapping("/export.csv")
	@PreAuthorize(VIEW)
	public ResponseEntity<byte[]> export(@RequestParam(required = false) List<UUID> ids, @RequestParam(required = false) String q,
			@RequestParam(required = false) String status, @RequestParam(required = false) UUID departmentId,
			@RequestParam(required = false) UUID locationId, @RequestParam(required = false) String window, @RequestParam(required = false) String sort) {
		String csv = query.exportCsv(filter(q, status, departmentId, locationId, window, sort, 0, 0), ids);
		return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename("onboarding.csv").build().toString())
				.contentType(MediaType.parseMediaType("text/csv")).body(csv.getBytes(StandardCharsets.UTF_8));
	}

	// ---- one hire ----

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize(EDIT)
	public Map<String, Object> create(@RequestBody Map<String, Object> body, @RequestParam(defaultValue = "false") boolean invite) {
		return hires.create(body, invite);
	}

	@GetMapping("/{id}")
	@PreAuthorize(VIEW)
	public Map<String, Object> get(@PathVariable UUID id) {
		return hires.detail(id);
	}

	@PatchMapping(value = "/{id}", consumes = {"application/json", "application/merge-patch+json"})
	@PreAuthorize(EDIT)
	public Map<String, Object> update(@PathVariable UUID id, @RequestBody Map<String, Object> body,
			@RequestHeader(value = "If-Match", required = false) String ifMatch, @RequestParam(defaultValue = "false") boolean force) {
		return hires.update(id, body, ifMatch, force);
	}

	@PostMapping("/{id}/invite")
	@PreAuthorize(EDIT)
	public Map<String, Object> invite(@PathVariable UUID id) {
		return invites.send(id);
	}

	@PostMapping("/{id}/invite/resend")
	@PreAuthorize(EDIT)
	public Map<String, Object> resend(@PathVariable UUID id) {
		return invites.resend(id);
	}

	@PostMapping("/{id}/remind")
	@PreAuthorize(EDIT)
	public Map<String, Object> remind(@PathVariable UUID id, @RequestBody(required = false) Map<String, String> body) {
		return invites.remind(id, body == null ? null : body.get("docKey"));
	}

	@PostMapping("/{id}/cancel")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@PreAuthorize(FULL)
	public void cancel(@PathVariable UUID id, @RequestBody Map<String, String> body) {
		hires.cancel(id, body.get("reason"));
	}

	@PostMapping("/{id}/join")
	@PreAuthorize(EDIT)
	public Map<String, Object> join(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean force, @RequestParam(required = false) String reason) {
		if (force && !access.has("recruiting", "ADMIN")) {
			throw new ForbiddenException("Only an administrator can join a hire that is not ready");
		}
		return join.join(id, force, reason);
	}

	@GetMapping("/{id}/details")
	@PreAuthorize(VIEW)
	public List<Map<String, Object>> details(@PathVariable UUID id) {
		return hires.details(id);
	}

	@GetMapping("/{id}/portal-preview")
	@PreAuthorize(VIEW)
	public Map<String, Object> portalPreview(@PathVariable UUID id) {
		return hires.portalPreview(id);
	}

	@GetMapping("/{id}/events")
	@PreAuthorize(VIEW)
	public Map<String, Object> events(@PathVariable UUID id, @RequestParam(required = false) Long before) {
		return hires.events(id, before);
	}

	// ---- checklist ----

	@GetMapping("/{id}/tasks")
	@PreAuthorize(VIEW)
	public List<Map<String, Object>> tasks(@PathVariable UUID id) {
		return hires.tasks(id);
	}

	@PatchMapping("/{id}/tasks/{taskId}")
	@PreAuthorize(EDIT)
	public Map<String, Object> task(@PathVariable UUID id, @PathVariable UUID taskId, @RequestBody Map<String, Boolean> body) {
		return hires.setTask(id, taskId, Boolean.TRUE.equals(body.get("done")));
	}

	@PostMapping("/{id}/tasks/sync")
	@PreAuthorize(EDIT)
	public Map<String, Object> syncTasks(@PathVariable UUID id) {
		return hires.syncTasks(id);
	}

	// ---- documents ----

	@GetMapping("/{id}/documents")
	@PreAuthorize(VIEW)
	public List<Map<String, Object>> documents(@PathVariable UUID id) {
		return documents.list(id);
	}

	@PostMapping("/{id}/documents")
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize(EDIT)
	public Map<String, Object> requestDocument(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
		return documents.request(id, String.valueOf(body.get("key")), !Boolean.FALSE.equals(body.get("required")));
	}

	@PostMapping("/{id}/documents/verify-all")
	@PreAuthorize(EDIT)
	public Map<String, Object> verifyAll(@PathVariable UUID id) {
		return Map.of("verified", documents.verifyAll(id));
	}

	@GetMapping("/{id}/documents/{docId}/file")
	@PreAuthorize(VIEW)
	public ResponseEntity<byte[]> file(@PathVariable UUID id, @PathVariable UUID docId) {
		HireDocumentService.FileContent f = documents.file(id, docId);
		return ResponseEntity.ok().contentType(MediaType.parseMediaType(f.mime()))
				.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(f.name(), StandardCharsets.UTF_8).build().toString())
				.header("X-Content-Type-Options", "nosniff").body(f.bytes());
	}

	@PostMapping("/{id}/documents/{docId}/verify")
	@PreAuthorize(EDIT)
	public void verify(@PathVariable UUID id, @PathVariable UUID docId) {
		documents.verify(id, docId);
	}

	@PostMapping("/{id}/documents/{docId}/reject")
	@PreAuthorize(EDIT)
	public void reject(@PathVariable UUID id, @PathVariable UUID docId, @RequestBody Map<String, String> body) {
		documents.reject(id, docId, body.get("reason"));
	}

	@PostMapping("/{id}/documents/{docId}/unverify")
	@PreAuthorize(EDIT)
	public void unverify(@PathVariable UUID id, @PathVariable UUID docId, @RequestBody Map<String, String> body) {
		documents.unverify(id, docId, body.get("reason"));
	}

	@PatchMapping("/{id}/documents/{docId}")
	@PreAuthorize(EDIT)
	public void setRequired(@PathVariable UUID id, @PathVariable UUID docId, @RequestBody Map<String, Boolean> body) {
		documents.setRequired(id, docId, !Boolean.FALSE.equals(body.get("required")));
	}

	@DeleteMapping("/{id}/documents/{docId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@PreAuthorize(EDIT)
	public void deleteDocument(@PathVariable UUID id, @PathVariable UUID docId) {
		documents.delete(id, docId);
	}

	// ---- Microsoft 365 ----

	@GetMapping("/{id}/m365")
	@PreAuthorize(VIEW)
	public Map<String, Object> m365(@PathVariable UUID id) {
		return m365.view(id);
	}

	@PostMapping("/{id}/m365")
	@ResponseStatus(HttpStatus.ACCEPTED)
	@PreAuthorize(EDIT)
	public Map<String, Object> createM365(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
		m365.create(id, body);
		return m365.view(id);
	}

	@PostMapping("/{id}/m365/reset-password")
	@PreAuthorize(EDIT)
	public Map<String, Object> resetPassword(@PathVariable UUID id) {
		return m365.resetPassword(id, false);
	}

	@PostMapping("/{id}/m365/resend")
	@PreAuthorize(EDIT)
	public Map<String, Object> resendSignIn(@PathVariable UUID id) {
		return m365.resetPassword(id, true);
	}

	@PostMapping("/{id}/m365/retry")
	@PreAuthorize(EDIT)
	public Map<String, Object> retry(@PathVariable UUID id) {
		return m365.retry(id);
	}
}
