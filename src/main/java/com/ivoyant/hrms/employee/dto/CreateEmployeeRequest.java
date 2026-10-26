package com.ivoyant.hrms.employee.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/** {@code custom} holds values of admin-configured fields, keyed by field key. */
public record CreateEmployeeRequest(
		/** Needed only when automatic employee IDs are off in the organization settings. */
		String employeeCode,
		@NotBlank String firstName,
		@NotBlank String lastName,
		@NotBlank @Email String workEmail,
		@Email String personalEmail,
		String phone,
		UUID departmentId,
		UUID designationId,
		/** The job title; it sets the position (a position given as well must match). */
		UUID titleId,
		UUID locationId,
		UUID managerId,
		@NotBlank String employmentType,
		@NotNull LocalDate joiningDate,
		/** Required when the employment type is fixed-term (contract, intern). */
		LocalDate contractEndDate,
		Map<String, Object> custom) {
}
