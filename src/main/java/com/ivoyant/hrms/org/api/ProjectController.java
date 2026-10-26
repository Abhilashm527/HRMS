package com.ivoyant.hrms.org.api;

import com.ivoyant.hrms.common.authz.RequiresMember;
import com.ivoyant.hrms.org.dto.ProjectDtos;
import com.ivoyant.hrms.org.service.ProjectQueryService;
import com.ivoyant.hrms.org.service.ProjectService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Projects. Reading everything needs View on the org module (members get a short form and the picker options); changing a
 * project needs Edit. Members can also be changed by the project's own manager. Bodies of POST and PATCH: name, code, client,
 * ownerEmployeeId, status (Planned, Active, On hold, Completed), startDate, endDate, departmentId, billable, description,
 * active (false archives).
 */
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

	private static final String VIEW = "@moduleAccess.has('org', 'VIEW')";
	private static final String EDIT = "@moduleAccess.has('org', 'EDIT')";

	private final ProjectService service;
	private final ProjectQueryService query;

	public ProjectController(ProjectService service, ProjectQueryService query) {
		this.service = service;
		this.query = query;
	}

	@GetMapping
	@RequiresMember
	public Object list(@RequestParam(required = false) String status, @RequestParam(required = false) Boolean active,
			@RequestParam(required = false) String client, @RequestParam(required = false) UUID managerId,
			@RequestParam(required = false) UUID memberId, @RequestParam(required = false) String q,
			@RequestParam(required = false) String include) {
		return query.list(status, active, client, managerId, memberId, q, "stats".equalsIgnoreCase(include));
	}

	/** Pickers: for timesheets only Active projects; otherwise Planned, Active and On hold. Archived ones never show. */
	@GetMapping("/options")
	@RequiresMember
	public List<ProjectDtos.Option> options(@RequestParam(defaultValue = "false") boolean forTimesheet) {
		return query.options(forTimesheet);
	}

	@GetMapping("/summary")
	@PreAuthorize(VIEW)
	public ProjectDtos.Summary summary() {
		return query.summary();
	}

	@GetMapping("/{id}")
	@PreAuthorize(VIEW)
	public ProjectDtos.Node get(@PathVariable UUID id) {
		return query.get(id);
	}

	@GetMapping("/{id}/members")
	@PreAuthorize(VIEW)
	public List<ProjectDtos.Member> members(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean includePast) {
		return query.members(id, includePast);
	}

	@GetMapping("/{id}/usage")
	@PreAuthorize(VIEW)
	public ProjectDtos.Usage usage(@PathVariable UUID id) {
		return query.usage(id);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize(EDIT)
	public ProjectDtos.Node create(@RequestBody Map<String, Object> body) {
		return service.create(body);
	}

	@PatchMapping(value = "/{id}", consumes = {"application/json", "application/merge-patch+json"})
	@PreAuthorize(EDIT)
	public ProjectDtos.Node update(@PathVariable UUID id, @RequestBody Map<String, Object> patch,
			@RequestHeader(value = "If-Match", required = false) String ifMatch) {
		return service.update(id, patch, ifMatch);
	}

	/** {@code {"status": "COMPLETED", "endDate": "2026-12-31"}}: completing ends every membership and frees the allocation. */
	@PostMapping("/{id}/status")
	@PreAuthorize(EDIT)
	public ProjectDtos.Node status(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
		return service.changeStatus(id, body);
	}

	/** Hard delete, only for a project that never had a member; everything else is archived with {@code active: false}. */
	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@PreAuthorize(EDIT)
	public void delete(@PathVariable UUID id) {
		service.delete(id);
	}

	/**
	 * {@code {"upsert": [{"employeeId": "...", "role": "CONTRIBUTOR", "allocation": 60}], "remove": ["..."], "effectiveDate": "..."}}.
	 * HR, or the project's own manager.
	 */
	@PutMapping("/{id}/members")
	@RequiresMember
	public List<ProjectDtos.Member> setMembers(@PathVariable UUID id, @RequestBody Map<String, Object> body,
			@org.springframework.web.bind.annotation.RequestHeader(value = "If-Match", required = false) String ifMatch) {
		return service.setMembers(id, body, ifMatch);
	}
}
