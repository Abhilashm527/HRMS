package com.ivoyant.hrms.employee.api;

import com.ivoyant.hrms.employee.service.EmployeeTitleService;
import com.ivoyant.hrms.employee.service.EmployeeTitleService.EmployeeTitles;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * An employee's titles. Reading follows who may open the employee; changing needs Edit on the org module (the title
 * decides the position, so it is an HR action). Body of PUT: {@code primaryId}, {@code extraIds[]}, {@code effectiveDate},
 * {@code reason} (HIRE, PROMOTION, TRANSFER, CORRECTION), and optionally {@code departmentId} to transfer in the same step.
 */
@RestController
@RequestMapping("/api/v1/employees/{id}/titles")
public class EmployeeTitleController {

	private final EmployeeTitleService service;

	public EmployeeTitleController(EmployeeTitleService service) {
		this.service = service;
	}

	@GetMapping
	@PreAuthorize("@authz.can('can_view_basic', 'employee:' + #id)")
	public EmployeeTitles get(@PathVariable UUID id) {
		return service.get(id);
	}

	@PutMapping
	@PreAuthorize("@moduleAccess.has('org', 'EDIT')")
	public EmployeeTitles set(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
		return service.set(id, body);
	}
}
