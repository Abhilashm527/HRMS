package com.ivoyant.hrms.employee;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** How other modules (onboarding) turn a person into an employee, and ask what is still missing on a profile. */
public interface EmployeeProvisioning {

	/**
	 * Creates the employee the way HR does: code, history, reporting line, user and access. {@code profile} holds
	 * values of profile fields by key; keys that are not custom profile fields are ignored.
	 */
	UUID create(Provision request);

	/** Labels of required profile fields the employee has not filled in. */
	List<String> missingRequired(UUID employeeId);

	record Provision(String firstName, String lastName, String workEmail, String personalEmail, String phone,
			UUID departmentId, UUID titleId, UUID locationId, UUID managerId, UUID employmentTypeId,
			LocalDate joiningDate, LocalDate contractEndDate, Integer probationMonths, Map<String, Object> profile) {
	}
}
