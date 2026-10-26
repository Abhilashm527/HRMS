package com.ivoyant.hrms.org.api;

import com.ivoyant.hrms.common.authz.RequiresMember;
import com.ivoyant.hrms.common.web.BadRequestException;
import com.ivoyant.hrms.common.web.PageResponse;
import com.ivoyant.hrms.org.dto.TitleHealth;
import com.ivoyant.hrms.org.dto.TitleHolder;
import com.ivoyant.hrms.org.dto.TitleNode;
import com.ivoyant.hrms.org.dto.TitleOption;
import com.ivoyant.hrms.org.dto.TitleSummary;
import com.ivoyant.hrms.org.dto.TitleUsage;
import com.ivoyant.hrms.org.service.TitleQueryService;
import com.ivoyant.hrms.org.service.TitleService;
import java.util.List;
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
 * Job titles. Reading everything needs View on the org module (members get a short form and the picker options);
 * changing needs Edit. Bodies of POST and PATCH are flat JSON: name, code (blank means generate one), positionId,
 * departmentId (blank means all departments), description, active, sort, and {@code custom}. PATCH is a JSON merge patch:
 * an explicit null clears the code (a new one is generated), the department or the description. A change of position
 * that moves people answers 409 CONFIRM_REQUIRED with the count; send {@code "confirm": true} to go ahead.
 */
@RestController
@RequestMapping("/api/v1/titles")
public class TitleController {

	private static final String VIEW = "@moduleAccess.has('org', 'VIEW')";
	private static final String EDIT = "@moduleAccess.has('org', 'EDIT')";

	private final TitleService service;
	private final TitleQueryService query;

	public TitleController(TitleService service, TitleQueryService query) {
		this.service = service;
		this.query = query;
	}

	/** Most senior position first, then by name. {@code include=stats} adds holder counts. */
	@GetMapping
	@RequiresMember
	public Object list(@RequestParam(required = false) Boolean active, @RequestParam(required = false) String q,
			@RequestParam(required = false) UUID positionId, @RequestParam(required = false) UUID departmentId,
			@RequestParam(required = false) String include) {
		return query.list(active, q, positionId, departmentId, "stats".equalsIgnoreCase(include));
	}

	@GetMapping("/summary")
	@PreAuthorize(VIEW)
	public TitleSummary summary() {
		return query.summary();
	}

	/** Active titles that fit the department: global ones, its own, and those of the departments above it. */
	@GetMapping("/options")
	@RequiresMember
	public List<TitleOption> options(@RequestParam(required = false) UUID departmentId) {
		return query.options(departmentId);
	}

	@GetMapping("/validate")
	@PreAuthorize(VIEW)
	public TitleHealth validate() {
		return query.validate();
	}

	@GetMapping("/{id}")
	@PreAuthorize(VIEW)
	public TitleNode get(@PathVariable UUID id) {
		return query.get(id);
	}

	@GetMapping("/{id}/employees")
	@PreAuthorize(VIEW)
	public PageResponse<TitleHolder> employees(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean includeExtra,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return query.employees(id, includeExtra, page, size);
	}

	@GetMapping("/{id}/usage")
	@PreAuthorize(VIEW)
	public TitleUsage usage(@PathVariable UUID id) {
		return query.usage(id);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize(EDIT)
	public TitleNode create(@RequestBody Map<String, Object> body) {
		return service.create(body);
	}

	@PatchMapping(value = "/{id}", consumes = {"application/json", "application/merge-patch+json"})
	@PreAuthorize(EDIT)
	public TitleNode update(@PathVariable UUID id, @RequestBody Map<String, Object> patch,
			@RequestHeader(value = "If-Match", required = false) String ifMatch) {
		return service.update(id, patch, ifMatch);
	}

	/** Hard delete, only when nothing points at it; {@code reassignTo} merges it into that title first. */
	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@PreAuthorize(EDIT)
	public void delete(@PathVariable UUID id, @RequestParam(required = false) UUID reassignTo) {
		service.delete(id, reassignTo);
	}

	/** {@code {"intoId": "..."}}: moves people, extra titles and history to the other title, then deletes this one. */
	@PostMapping("/{id}/merge")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@PreAuthorize(EDIT)
	public void merge(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
		Object into = body.get("intoId");
		if (into == null || into.toString().isBlank()) {
			throw new BadRequestException("intoId is required");
		}
		service.merge(id, UUID.fromString(into.toString()));
	}
}
