package com.ivoyant.hrms.employee.service;

import com.ivoyant.hrms.config.FieldConfig;
import com.ivoyant.hrms.config.FieldDefinition;
import com.ivoyant.hrms.employee.EmployeeProvisioning;
import com.ivoyant.hrms.employee.dto.CreateEmployeeRequest;
import com.ivoyant.hrms.employee.repo.EmployeeRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class EmployeeProvisioningService implements EmployeeProvisioning {

	private final EmployeeService employees;
	private final EmployeeRecords records;
	private final FieldConfig fieldConfig;
	private final EmployeeRepository repository;

	EmployeeProvisioningService(EmployeeService employees, EmployeeRecords records, FieldConfig fieldConfig, EmployeeRepository repository) {
		this.repository = repository;
		this.employees = employees;
		this.records = records;
		this.fieldConfig = fieldConfig;
	}

	@Override
	@Transactional
	public UUID create(Provision p) {
		Set<String> custom = fieldConfig.activeFields(EmployeeFields.AREA).stream()
				.filter(d -> !EmployeeFields.isColumn(d.key()) && d.type().hasValue()).map(FieldDefinition::key)
				.collect(Collectors.toSet());
		Map<String, Object> values = new HashMap<>();
		if (p.profile() != null) {
			p.profile().forEach((k, v) -> {
				if (custom.contains(k) && v != null) {
					values.put(k, v);
				}
			});
		}
		CreateEmployeeRequest request = new CreateEmployeeRequest(null, p.firstName(), p.lastName(), p.workEmail(),
				p.personalEmail(), p.phone(), p.departmentId(), null, p.titleId(), p.locationId(), p.managerId(),
				p.employmentTypeId().toString(), p.joiningDate(), p.contractEndDate(), values);
		UUID id = employees.create(request).id();
		if (p.probationMonths() != null) {
			repository.findById(id).ifPresent(e -> {
				e.setProbationEndDate(p.probationMonths() > 0 ? p.joiningDate().plusMonths(p.probationMonths()) : null);
				repository.save(e);
			});
		}
		return id;
	}

	@Override
	@Transactional(readOnly = true)
	public List<String> missingRequired(UUID employeeId) {
		return fieldConfig.missingRequired(EmployeeFields.AREA, employeeId, records.read(employeeId));
	}
}
