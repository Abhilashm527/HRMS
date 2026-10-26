package com.ivoyant.hrms.employee.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An employee as the caller may see them. {@code fields} only contains fields the caller can see (HIDDEN ones are
 * omitted, MASKED ones are masked); everything configurable lives in {@code fields}, keyed by field key.
 * {@code pendingApproval} lists the keys of an edit that became a change request instead of being saved.
 */
public record EmployeeResponse(UUID id, String employeeCode, String displayName, Map<String, Object> fields,
		EmployeeMeta meta, List<String> pendingApproval, String titleDisplay) {
}
