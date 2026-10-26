package com.ivoyant.hrms.org.api;

import com.ivoyant.hrms.common.authz.RequiresMember;
import com.ivoyant.hrms.org.dto.TeamDtos;
import com.ivoyant.hrms.org.service.TeamQueryService;
import com.ivoyant.hrms.org.service.TeamService;
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
 * Teams. Reading everything needs View on the org module (members get a short form and the picker options); changing needs
 * Edit. Bodies of POST and PATCH: name, departmentId, leadEmployeeId, description, active (msChannelId is accepted and
 * ignored until the Microsoft Teams integration exists). Deactivating a team that has members answers 409 CONFIRM_REQUIRED
 * with who is in it; send {@code "confirm": true} to end their membership.
 */
@RestController
@RequestMapping("/api/v1/teams")
public class TeamController {

	private static final String VIEW = "@moduleAccess.has('org', 'VIEW')";
	private static final String EDIT = "@moduleAccess.has('org', 'EDIT')";

	private final TeamService service;
	private final TeamQueryService query;

	public TeamController(TeamService service, TeamQueryService query) {
		this.service = service;
		this.query = query;
	}

	@GetMapping
	@RequiresMember
	public Object list(@RequestParam(required = false) Boolean active, @RequestParam(required = false) UUID departmentId,
			@RequestParam(defaultValue = "false") boolean includeChildren, @RequestParam(required = false) String q,
			@RequestParam(required = false) String include) {
		return query.list(active, departmentId, includeChildren, q, "stats".equalsIgnoreCase(include));
	}

	/** Active teams in the department and the ones above it (for the hire form, which then suggests the team lead as manager). */
	@GetMapping("/options")
	@RequiresMember
	public List<TeamDtos.Option> options(@RequestParam(required = false) UUID departmentId) {
		return query.options(departmentId);
	}

	@GetMapping("/{id}")
	@PreAuthorize(VIEW)
	public TeamDtos.Node get(@PathVariable UUID id) {
		return query.get(id);
	}

	@GetMapping("/{id}/members")
	@PreAuthorize(VIEW)
	public List<TeamDtos.Member> members(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean includePast) {
		return query.members(id, includePast);
	}

	@GetMapping("/{id}/usage")
	@PreAuthorize(VIEW)
	public TeamDtos.Usage usage(@PathVariable UUID id) {
		return query.usage(id);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize(EDIT)
	public TeamDtos.Node create(@RequestBody Map<String, Object> body) {
		return service.create(body);
	}

	@PatchMapping(value = "/{id}", consumes = {"application/json", "application/merge-patch+json"})
	@PreAuthorize(EDIT)
	public TeamDtos.Node update(@PathVariable UUID id, @RequestBody Map<String, Object> patch,
			@RequestHeader(value = "If-Match", required = false) String ifMatch) {
		return service.update(id, patch, ifMatch);
	}

	/** Hard delete, only for a team that never had members; {@code reassignTo} first moves the current members. */
	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@PreAuthorize(EDIT)
	public void delete(@PathVariable UUID id, @RequestParam(required = false) UUID reassignTo) {
		service.delete(id, reassignTo);
	}

	/** {@code {"add": [{"employeeId": "...", "primary": false}], "remove": ["..."], "effectiveDate": "2026-11-01"}} */
	@PutMapping("/{id}/members")
	@PreAuthorize(EDIT)
	public List<TeamDtos.Member> setMembers(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
		return service.setMembers(id, body);
	}
}
