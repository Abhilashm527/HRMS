package com.ivoyant.hrms.iam.api;

import com.ivoyant.hrms.common.authz.RequiresMember;
import com.ivoyant.hrms.iam.dto.CreateDelegationRequest;
import com.ivoyant.hrms.iam.dto.DelegationResponse;
import com.ivoyant.hrms.iam.service.DelegationService;
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
@RequestMapping("/api/v1/delegations")
public class DelegationController {

	private final DelegationService service;

	public DelegationController(DelegationService service) {
		this.service = service;
	}

	/** Delegations the caller gave or received (all of them for HR admins). */
	@GetMapping
	@RequiresMember
	public List<DelegationResponse> list() {
		return service.list();
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@RequiresMember
	public DelegationResponse create(@Valid @RequestBody CreateDelegationRequest request) {
		return service.create(request);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@RequiresMember
	public void delete(@PathVariable UUID id) {
		service.delete(id);
	}
}
