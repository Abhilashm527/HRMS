package com.ivoyant.hrms.org;

import java.util.UUID;

/** A team as other modules see it. */
public record TeamView(UUID id, String name, UUID departmentId, UUID leadEmployeeId, boolean active) {
}
