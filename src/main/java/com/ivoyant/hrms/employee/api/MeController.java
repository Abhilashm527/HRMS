package com.ivoyant.hrms.employee.api;

import com.ivoyant.hrms.employee.dto.EmployeeResponse;
import com.ivoyant.hrms.employee.service.EmployeeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
public class MeController {

	private final EmployeeService service;

	public MeController(EmployeeService service) {
		this.service = service;
	}

	/** The authenticated caller's own employee record. */
	@GetMapping
	public EmployeeResponse me() {
		return service.me();
	}
}
