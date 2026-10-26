package com.ivoyant.hrms.employee.dto;

import com.ivoyant.hrms.employee.domain.EmployeeStatus;
import java.util.UUID;

/** Org-structure view of an employee, safe to show to every member (no sensitive fields). */
public record EmployeeSummary(UUID id, String employeeCode, String displayName, String workEmail,
		UUID departmentId, UUID designationId, UUID locationId, UUID managerId, EmployeeStatus status, UUID titleId, String titleDisplay) {
}
