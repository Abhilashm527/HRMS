package com.ivoyant.hrms.org.dto;

import java.util.UUID;

/** A person who holds a title; {@code primary} false means it is one of their extra titles. */
public record TitleHolder(UUID id, String employeeCode, String displayName, String workEmail, String status,
		UUID departmentId, boolean primary) {
}
