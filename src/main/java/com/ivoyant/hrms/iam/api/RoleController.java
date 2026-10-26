package com.ivoyant.hrms.iam.api;

import com.ivoyant.hrms.common.authz.RequiresHrAdmin;
import com.ivoyant.hrms.common.authz.RequiresSuperAdmin;
import com.ivoyant.hrms.iam.dto.CreateRoleRequest;
import com.ivoyant.hrms.iam.dto.RolePermissionRequest;
import com.ivoyant.hrms.iam.dto.RolePermissionResponse;
import com.ivoyant.hrms.iam.dto.RoleResponse;
import com.ivoyant.hrms.iam.dto.UpdateRoleRequest;
import com.ivoyant.hrms.iam.service.RoleService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/roles")
public class RoleController {

	private final RoleService service;

	public RoleController(RoleService service) {
		this.service = service;
	}

	@GetMapping
	@RequiresHrAdmin
	public List<RoleResponse> list() {
		return service.list();
	}

	@GetMapping("/{id}")
	@RequiresHrAdmin
	public RoleResponse get(@PathVariable UUID id) {
		return service.get(id);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@RequiresSuperAdmin
	public RoleResponse create(@Valid @RequestBody CreateRoleRequest request) {
		return service.create(request);
	}

	@PatchMapping("/{id}")
	@RequiresSuperAdmin
	public RoleResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateRoleRequest request) {
		return service.update(id, request);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@RequiresSuperAdmin
	public void delete(@PathVariable UUID id) {
		service.delete(id);
	}

	@GetMapping("/{id}/permissions")
	@RequiresHrAdmin
	public List<RolePermissionResponse> permissions(@PathVariable UUID id) {
		return service.permissionsOf(id);
	}

	/** Replaces the whole permission set of the role. */
	@PutMapping("/{id}/permissions")
	@RequiresSuperAdmin
	public List<RolePermissionResponse> replacePermissions(@PathVariable UUID id,
			@Valid @RequestBody List<@Valid RolePermissionRequest> request) {
		return service.replacePermissions(id, request);
	}
}
