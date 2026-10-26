package com.ivoyant.hrms.org.api;

import com.ivoyant.hrms.org.service.OrgViewsService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Org chart views by team and by project. Needs View on the org module. Project members are edited with PUT /projects/{id}/members. */
@RestController
@RequestMapping("/api/v1/org")
public class OrgViewsController {

	private static final String VIEW = "@moduleAccess.has('org', 'VIEW')";

	private final OrgViewsService views;

	public OrgViewsController(OrgViewsService views) {
		this.views = views;
	}

	@GetMapping("/teams-view")
	@PreAuthorize(VIEW)
	public List<Map<String, Object>> teams(@RequestParam(required = false) UUID departmentId) {
		return views.teamsView(departmentId);
	}

	@GetMapping("/projects-view")
	@PreAuthorize(VIEW)
	public Map<String, Object> projects(@RequestParam(defaultValue = "false") boolean includeCompleted) {
		return views.projectsView(includeCompleted);
	}

	@GetMapping("/projects-view/{id}/candidates")
	@PreAuthorize(VIEW)
	public List<Map<String, Object>> candidates(@PathVariable UUID id) {
		return views.candidates(id);
	}
}
