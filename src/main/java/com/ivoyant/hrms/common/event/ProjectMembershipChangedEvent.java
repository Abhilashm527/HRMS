package com.ivoyant.hrms.common.event;

import java.util.UUID;

/** See TEAMS_PROJECTS_BACKEND_GUIDE.md section 10. */
public record ProjectMembershipChangedEvent(UUID projectId, UUID employeeId, String role, int oldAllocation, int newAllocation, boolean joined) {
}
