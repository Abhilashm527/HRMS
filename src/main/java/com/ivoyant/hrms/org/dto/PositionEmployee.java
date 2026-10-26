package com.ivoyant.hrms.org.dto;

import java.util.UUID;

/** A person who holds a position. */
public record PositionEmployee(UUID id, String employeeCode, String displayName, String workEmail, String status,
		UUID departmentId) {
}
