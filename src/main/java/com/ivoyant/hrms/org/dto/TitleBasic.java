package com.ivoyant.hrms.org.dto;

import java.util.UUID;

/** What every member may see of a title. */
public record TitleBasic(UUID id, String name, String code, UUID positionId, UUID departmentId, boolean active) {
}
