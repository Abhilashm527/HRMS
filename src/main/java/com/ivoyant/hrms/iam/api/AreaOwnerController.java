package com.ivoyant.hrms.iam.api;

import com.ivoyant.hrms.common.authz.RequiresHrAdmin;
import com.ivoyant.hrms.common.authz.RequiresSuperAdmin;
import com.ivoyant.hrms.iam.dto.AreaOwnerResponse;
import com.ivoyant.hrms.iam.dto.CreateAreaOwnerRequest;
import com.ivoyant.hrms.iam.service.AreaOwnerService;
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
@RequestMapping("/api/v1/area-owners")
public class AreaOwnerController {

	private final AreaOwnerService service;

	public AreaOwnerController(AreaOwnerService service) {
		this.service = service;
	}

	@GetMapping
	@RequiresHrAdmin
	public List<AreaOwnerResponse> list() {
		return service.list();
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@RequiresSuperAdmin
	public AreaOwnerResponse create(@Valid @RequestBody CreateAreaOwnerRequest request) {
		return service.create(request);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@RequiresSuperAdmin
	public void delete(@PathVariable UUID id) {
		service.delete(id);
	}
}
