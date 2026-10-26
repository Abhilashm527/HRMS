package com.ivoyant.hrms.org;

import java.util.UUID;

/** A title as other modules see it. */
public record TitleView(UUID id, String name, String code, UUID positionId, UUID departmentId, boolean active) {
}
