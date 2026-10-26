package com.ivoyant.hrms.org.api;

import com.ivoyant.hrms.common.authz.RequiresMember;
import com.ivoyant.hrms.common.web.PageResponse;
import com.ivoyant.hrms.org.dto.RelationDtos;
import com.ivoyant.hrms.org.service.RelationService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
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
 * Dotted lines and mentors. Reading all of them needs View on the org module. Adding or ending needs Edit, except that a manager
 * may add and end MENTOR relations for their own reports. Dotted lines and mentors carry no authority.
 */
@RestController
@RequestMapping("/api/v1")
public class RelationController {

	private static final String VIEW = "@moduleAccess.has('org', 'VIEW')";

	private final RelationService service;

	public RelationController(RelationService service) {
		this.service = service;
	}

	@GetMapping("/relations")
	@PreAuthorize(VIEW)
	public PageResponse<RelationDtos.Node> list(@RequestParam(required = false) String type, @RequestParam(required = false) String status,
			@RequestParam(required = false) UUID employeeId, @RequestParam(required = false) UUID relatedId,
			@RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
		return service.list(type, status, employeeId, relatedId, q, page, size);
	}

	@GetMapping("/relations/summary")
	@PreAuthorize(VIEW)
	public RelationDtos.Summary summary() {
		return service.summary();
	}

	@GetMapping("/employees/{id}/relations")
	@PreAuthorize("@authz.can('can_view_basic', 'employee:' + #id)")
	public RelationDtos.EmployeeRelations ofEmployee(@PathVariable UUID id) {
		return service.ofEmployee(id);
	}

	/** {@code {employeeId, relatedId, type: "DOTTED"|"MENTOR", fromDate, toDate, note}} */
	@PostMapping("/relations")
	@ResponseStatus(HttpStatus.CREATED)
	@RequiresMember
	public RelationDtos.Node create(@RequestBody Map<String, Object> body) {
		return service.create(body);
	}

	@PatchMapping(value = "/relations/{id}", consumes = {"application/json", "application/merge-patch+json"})
	@RequiresMember
	public RelationDtos.Node update(@PathVariable UUID id, @RequestBody Map<String, Object> patch,
			@RequestHeader(value = "If-Match", required = false) String ifMatch) {
		return service.update(id, patch, ifMatch);
	}

	/** {@code {"endDate": "2026-12-31", "reason": "..."}}: the relation stays in the history. */
	@PostMapping("/relations/{id}/end")
	@RequiresMember
	public RelationDtos.Node end(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> body) {
		return service.end(id, body);
	}

	/** Only a relation that never started; everything else is ended. */
	@DeleteMapping("/relations/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@RequiresMember
	public void delete(@PathVariable UUID id) {
		service.delete(id);
	}
}
