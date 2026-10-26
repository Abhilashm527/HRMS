package com.ivoyant.hrms.org.api;

import com.ivoyant.hrms.common.web.BadRequestException;
import com.ivoyant.hrms.org.dto.ProjectDtos;
import com.ivoyant.hrms.org.service.GroupsService;
import com.ivoyant.hrms.org.service.ProjectQueryService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** A person's teams and projects, the allocation views, and the Reporting lines save. */
@RestController
@RequestMapping("/api/v1")
public class GroupsController {

	private static final String VIEW = "@moduleAccess.has('org', 'VIEW')";
	private static final String EDIT = "@moduleAccess.has('org', 'EDIT')";

	private final GroupsService groups;
	private final ProjectQueryService query;

	public GroupsController(GroupsService groups, ProjectQueryService query) {
		this.groups = groups;
		this.query = query;
	}

	@GetMapping("/employees/{id}/groups")
	@PreAuthorize("@authz.can('can_view_basic', 'employee:' + #id)")
	public ProjectDtos.Groups get(@PathVariable UUID id) {
		return groups.get(id);
	}

	/** Replaces the person's current teams and projects: {@code {teams: [{teamId, primary}], projects: [{projectId, role, allocation}], effectiveDate}}. */
	@PutMapping("/employees/{id}/groups")
	@PreAuthorize(EDIT)
	public ProjectDtos.Groups put(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
		return groups.put(id, body);
	}

	/** {@code state=FULL} is the "Fully allocated" panel; OVER, UNDER and NONE work too. */
	@GetMapping("/allocation")
	@PreAuthorize(VIEW)
	public List<ProjectDtos.AllocationRow> allocation(@RequestParam(required = false) String state, @RequestParam(required = false) UUID departmentId) {
		return query.allocation(state, departmentId);
	}

	/** {@code {employeeId, changes: [{projectId, allocation}]}}: what the total would be (nothing is saved). */
	@PostMapping("/allocation/check")
	@PreAuthorize(VIEW)
	@SuppressWarnings("unchecked")
	public ProjectDtos.AllocationCheck check(@RequestBody Map<String, Object> body) {
		if (body.get("employeeId") == null) {
			throw new BadRequestException("employeeId is required");
		}
		return groups.check(UUID.fromString(body.get("employeeId").toString()),
				body.get("changes") instanceof List<?> l ? (List<Map<String, Object>>) l : List.of());
	}
}
