package com.ivoyant.hrms.org.dto;

import java.util.UUID;

/** An active employee in the position a new hire usually reports to; same department and location rank first. */
public record SuggestedManager(UUID id, String employeeCode, String displayName, UUID departmentId,
		boolean sameDepartment, boolean sameLocation) {
}
