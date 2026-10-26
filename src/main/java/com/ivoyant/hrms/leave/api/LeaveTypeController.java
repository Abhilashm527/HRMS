package com.ivoyant.hrms.leave.api;

import com.ivoyant.hrms.common.authz.RequiresMember;
import com.ivoyant.hrms.leave.service.LeaveTypeService;
import com.ivoyant.hrms.leave.service.LeaveTypeService.LeaveType;
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
 * Leave types (Settings → Leave &amp; attendance → Leave policy). Everyone can read them (the apply picker, calendar chips); changing needs
 * Admin on the leave module (HR and super admins always have it).
 */
@RestController
@RequestMapping("/api/v1/leave/types")
public class LeaveTypeController {

	private static final String MANAGE = "@moduleAccess.has('leave', 'ADMIN')";

	private final LeaveTypeService service;

	public LeaveTypeController(LeaveTypeService service) {
		this.service = service;
	}

	/** {@code for=me} gives the active types the caller can apply for (gender, length of service). */
	@GetMapping
	@RequiresMember
	public List<LeaveType> list(@RequestParam(required = false) Boolean active, @RequestParam(name = "for", required = false) String forWho) {
		return "me".equalsIgnoreCase(forWho) ? service.forMe() : service.list(active);
	}

	@GetMapping("/{id}")
	@RequiresMember
	public LeaveType get(@PathVariable UUID id) {
		return service.get(id);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@PreAuthorize(MANAGE)
	public LeaveType create(@RequestBody Map<String, Object> body) {
		return service.create(body);
	}

	/** The side panel's Save. Moving a type in use to or from earned leave answers 409 CONFIRM_REQUIRED; repeat with {@code ?force=true}. */
	@PutMapping("/{id}")
	@PreAuthorize(MANAGE)
	public LeaveType update(@PathVariable UUID id, @RequestBody Map<String, Object> body, @RequestHeader(value = "If-Match", required = false) String ifMatch,
			@RequestParam(defaultValue = "false") boolean force) {
		return service.update(id, body, ifMatch, force);
	}

	/** The inline Days edit: {@code {"quota": 14}}. */
	@PatchMapping("/{id}/quota")
	@PreAuthorize(MANAGE)
	public LeaveType quota(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
		return service.setQuota(id, body.get("quota"));
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@PreAuthorize(MANAGE)
	public void delete(@PathVariable UUID id) {
		service.delete(id);
	}
}
