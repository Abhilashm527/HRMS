package com.ivoyant.hrms.org;

import java.util.UUID;

/** A project as other modules see it. */
public record ProjectView(UUID id, String name, String code, String client, UUID managerId, String status, boolean billable,
		boolean active) {
}
