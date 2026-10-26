package com.ivoyant.hrms.config.api;

import com.ivoyant.hrms.common.authz.AuthzService;
import com.ivoyant.hrms.common.authz.RequiresMember;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.ForbiddenException;
import com.ivoyant.hrms.config.FieldConfig;
import com.ivoyant.hrms.config.FormView;
import com.ivoyant.hrms.config.WriteResult;
import com.ivoyant.hrms.config.dto.ChangeRequestResponse;
import com.ivoyant.hrms.config.dto.RejectRequest;
import com.ivoyant.hrms.config.dto.WriteValuesRequest;
import com.ivoyant.hrms.config.service.ChangeRequestService;
import com.ivoyant.hrms.config.service.FormService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Forms at runtime: what a screen of any module shows and saves. Access is per field; record-level access (may the
 * caller open this record at all) is the owning module's {@code RecordPolicy}.
 */
@RestController
@RequestMapping("/api/v1/forms")
@RequiresMember
public class FormController {

	private final FormService forms;
	private final FieldConfig facade;
	private final ChangeRequestService changeRequests;
	private final AuthzService authz;
	private final CurrentUser currentUser;
	private final com.ivoyant.hrms.org.OrgDirectory org;
	private final com.ivoyant.hrms.org.Departments departments;
	private final com.ivoyant.hrms.org.Teams teams;

	public FormController(FormService forms, FieldConfig facade, ChangeRequestService changeRequests,
			AuthzService authz, CurrentUser currentUser, com.ivoyant.hrms.org.OrgDirectory org,
			com.ivoyant.hrms.org.Departments departments, com.ivoyant.hrms.org.Teams teams) {
		this.departments = departments;
		this.teams = teams;
		this.forms = forms;
		this.facade = facade;
		this.changeRequests = changeRequests;
		this.authz = authz;
		this.currentUser = currentUser;
		this.org = org;
	}

	/** Blank form for create; {@code ownerEmployeeId} only counts when it is the caller (or an HR admin asks). */
	@GetMapping("/{areaKey}/schema")
	public FormView schema(@PathVariable String areaKey, @RequestParam(required = false) UUID ownerEmployeeId,
			@RequestParam(required = false) UUID departmentId, @RequestParam(required = false) UUID teamId) {
		Map<String, Object> prefill = new java.util.HashMap<>();
		if (departmentId != null && "onboarding".equals(areaKey)) {
			// the hire form's work location starts as the department's default location
			org.defaultLocationOfDepartment(departmentId).filter(loc -> !org.activeLocationNames(List.of(loc)).isEmpty())
					.ifPresent(loc -> prefill.put("loc", loc.toString()));
			// ...and "Reports to" starts as the department's head (or the nearest head above it)
			departments.effectiveHead(departmentId).ifPresent(head -> prefill.put("mgr", head.toString()));
		}
		if (teamId != null && "onboarding".equals(areaKey)) {
			// a team's lead is the default manager for its new members (only a suggestion: nothing existing changes)
			teams.leadOf(teamId).ifPresent(lead -> prefill.put("mgr", lead.toString()));
		}
		return forms.blank(areaKey, trustedOwner(ownerEmployeeId), prefill);
	}

	@PostMapping("/{areaKey}/validate")
	public Map<String, List<ErrorItem>> validate(@PathVariable String areaKey, @Valid @RequestBody WriteValuesRequest request) {
		return Map.of("errors", facade.validateDraft(areaKey, trustedOwner(request.ownerEmployeeId()), request.values()));
	}

	@GetMapping("/{areaKey}/records/{recordId}")
	public FormView record(@PathVariable String areaKey, @PathVariable UUID recordId) {
		requireOpen(areaKey, recordId);
		return facade.render(areaKey, recordId, forms.ownerOf(areaKey, recordId));
	}

	@PatchMapping("/{areaKey}/records/{recordId}")
	public WriteResult write(@PathVariable String areaKey, @PathVariable UUID recordId,
			@Valid @RequestBody WriteValuesRequest request) {
		requireOpen(areaKey, recordId);
		UUID owner = forms.ownerOf(areaKey, recordId);
		if (owner == null && request.isDraft()) {
			owner = trustedOwner(request.ownerEmployeeId());
		}
		return facade.write(areaKey, recordId, owner, request.values(), request.isDraft());
	}

	// ---- approval inbox ----

	@GetMapping("/change-requests")
	public List<ChangeRequestResponse> changeRequests(@RequestParam(required = false) String status,
			@RequestParam(required = false) String area, @RequestParam(defaultValue = "false") boolean mine) {
		return changeRequests.list(status, area, mine);
	}

	@PostMapping("/change-requests/{id}/approve")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void approve(@PathVariable UUID id) {
		changeRequests.approve(id);
	}

	@PostMapping("/change-requests/{id}/reject")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void reject(@PathVariable UUID id, @RequestBody(required = false) RejectRequest request) {
		changeRequests.reject(id, request == null ? null : request.note());
	}

	@PostMapping("/change-requests/{id}/cancel")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void cancel(@PathVariable UUID id) {
		changeRequests.cancel(id);
	}

	private void requireOpen(String areaKey, UUID recordId) {
		if (!forms.canOpen(areaKey, recordId)) {
			throw new ForbiddenException("You cannot open this record");
		}
	}

	private UUID trustedOwner(UUID requested) {
		if (requested == null) {
			return null;
		}
		return requested.equals(currentUser.employeeId()) || authz.isHrAdmin() ? requested : null;
	}
}
