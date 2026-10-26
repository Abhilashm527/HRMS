package com.ivoyant.hrms.iam.api;

import com.ivoyant.hrms.common.authz.RequiresHrAdmin;
import com.ivoyant.hrms.common.authz.RequiresSuperAdmin;
import com.ivoyant.hrms.iam.dto.AssignRoleRequest;
import com.ivoyant.hrms.iam.dto.UserResponse;
import com.ivoyant.hrms.iam.dto.UserRoleResponse;
import com.ivoyant.hrms.iam.service.UserService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

	private final UserService service;

	public UserController(UserService service) {
		this.service = service;
	}

	@GetMapping
	@RequiresHrAdmin
	public List<UserResponse> list() {
		return service.list();
	}

	@GetMapping("/{id}")
	@RequiresHrAdmin
	public UserResponse get(@PathVariable UUID id) {
		return service.get(id);
	}

	@GetMapping("/{id}/roles")
	@RequiresHrAdmin
	public List<UserRoleResponse> roles(@PathVariable UUID id) {
		return service.rolesOf(id);
	}

	@PostMapping("/{id}/roles")
	@ResponseStatus(HttpStatus.CREATED)
	@RequiresSuperAdmin
	public UserRoleResponse assign(@PathVariable UUID id, @Valid @RequestBody AssignRoleRequest request) {
		return service.assign(id, request);
	}

	@DeleteMapping("/{id}/roles/{userRoleId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@RequiresSuperAdmin
	public void remove(@PathVariable UUID id, @PathVariable UUID userRoleId) {
		service.remove(id, userRoleId);
	}
}
