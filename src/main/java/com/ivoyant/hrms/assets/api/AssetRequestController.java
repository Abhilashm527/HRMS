package com.ivoyant.hrms.assets.api;

import com.ivoyant.hrms.assets.service.AssetRequestService;
import com.ivoyant.hrms.common.authz.RequiresMember;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.security.ModuleAccess;
import com.ivoyant.hrms.common.web.ForbiddenException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Asset requests: the employee side (My assets) and the approvers' side. Deciding is checked per request. */
@RestController
@RequestMapping("/api/v1")
public class AssetRequestController {

	private final AssetRequestService service;
	private final CurrentUser currentUser;
	private final ModuleAccess access;

	public AssetRequestController(AssetRequestService service, CurrentUser currentUser, ModuleAccess access) {
		this.service = service;
		this.currentUser = currentUser;
		this.access = access;
	}

	// ---- employee ----

	@GetMapping("/me/assets")
	@RequiresMember
	public Map<String, Object> mine() {
		return service.mine(currentUser.requireEmployeeId());
	}

	@GetMapping("/me/assets/request-options")
	@RequiresMember
	public Map<String, Object> options(@RequestParam(defaultValue = "HARDWARE") String kind) {
		return service.options(kind);
	}

	@PostMapping("/me/assets/requests")
	@ResponseStatus(HttpStatus.CREATED)
	@RequiresMember
	public Map<String, Object> submit(@RequestBody Map<String, Object> body) {
		return service.submit(currentUser.requireEmployeeId(), body);
	}

	@PostMapping("/me/assets/requests/{id}/cancel")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@RequiresMember
	public void cancel(@PathVariable UUID id) {
		service.cancel(currentUser.requireEmployeeId(), id);
	}

	// ---- approvers ----

	@GetMapping("/assets/requests")
	@RequiresMember
	public Map<String, Object> list(@RequestParam(required = false) String kind, @RequestParam(required = false) String status, @RequestParam(required = false) String mine) {
		return service.list(kind, status, mine);
	}

	@PostMapping("/assets/requests/{id}/approve")
	@RequiresMember
	public Map<String, Object> approve(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> body) {
		return service.approve(id, body == null ? Map.of() : body);
	}

	@PostMapping("/assets/requests/{id}/reject")
	@RequiresMember
	public Map<String, Object> reject(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
		return service.reject(id, body);
	}

	// ---- exit and profile ----

	@GetMapping("/assets/recovery/{employeeId}")
	@RequiresMember
	public Map<String, Object> recovery(@PathVariable UUID employeeId) {
		if (!access.has("assets", "VIEW")) {
			throw new ForbiddenException("You cannot see what someone else holds");
		}
		return service.recovery(employeeId);
	}

	/** The profile's Assets tab: the person's own items, or anyone's for the assets team. */
	@GetMapping("/employees/{id}/assets")
	@RequiresMember
	public Map<String, Object> ofEmployee(@PathVariable UUID id) {
		if (!id.equals(currentUser.employeeId()) && !access.has("assets", "VIEW")) {
			throw new ForbiddenException("You cannot see what someone else holds");
		}
		return service.mine(id);
	}
}
