package com.ivoyant.hrms.org.dto;

import java.util.UUID;

/** What a member without access to department settings may see. */
public record DepartmentBasic(UUID id, String name, String code, UUID parentId, boolean active) {
}
