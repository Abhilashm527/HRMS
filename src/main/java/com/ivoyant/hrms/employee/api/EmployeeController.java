package com.ivoyant.hrms.employee.api;

import com.ivoyant.hrms.common.authz.RequiresHrAdmin;
import com.ivoyant.hrms.common.authz.RequiresMember;
import com.ivoyant.hrms.common.web.PageResponse;
import com.ivoyant.hrms.employee.domain.EmployeeStatus;
import com.ivoyant.hrms.employee.dto.CreateEmployeeRequest;
import com.ivoyant.hrms.employee.dto.EmployeeResponse;
import com.ivoyant.hrms.employee.dto.EmployeeSummary;
import com.ivoyant.hrms.employee.dto.UpdateEmployeeRequest;
import com.ivoyant.hrms.employee.service.EmployeeService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/employees")
public class EmployeeController {

	private final EmployeeService service;

	public EmployeeController(EmployeeService service) {
		this.service = service;
	}

	@GetMapping("/{id}")
	@PreAuthorize("@authz.can('can_view_basic', 'employee:' + #id)")
	public EmployeeResponse get(@PathVariable UUID id) {
		return service.get(id);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@RequiresHrAdmin
	public EmployeeResponse create(@Valid @RequestBody CreateEmployeeRequest request) {
		return service.create(request);
	}

	/** Each key must be a field the caller may edit; see {@code meta.fields} of the GET response. */
	@PatchMapping("/{id}")
	@PreAuthorize("@authz.can('can_view_basic', 'employee:' + #id)")
	public EmployeeResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateEmployeeRequest request) {
		return service.update(id, request);
	}

	@GetMapping("/{id}/reports")
	@PreAuthorize("@authz.can('can_view_basic', 'employee:' + #id)")
	public List<EmployeeSummary> reports(@PathVariable UUID id, @RequestParam(defaultValue = "1") String depth) {
		return service.reports(id, "all".equalsIgnoreCase(depth));
	}
}
