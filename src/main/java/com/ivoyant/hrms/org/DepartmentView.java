package com.ivoyant.hrms.org;

import java.util.UUID;

/** A department as other modules see it. */
public record DepartmentView(UUID id, String name, String code, UUID parentId, UUID headEmployeeId, String costCentre,
		UUID defaultLocationId, boolean active) {
}
