package com.ivoyant.hrms.common.event;

import java.util.List;
import java.util.UUID;

/** See TEAMS_PROJECTS_BACKEND_GUIDE.md section 10. */
public record ProjectStatusChangedEvent(UUID id, String oldStatus, String newStatus, List<UUID> released) {
}
