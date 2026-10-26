package com.ivoyant.hrms.common.event;

import java.util.List;
import java.util.UUID;

/** See TEAMS_PROJECTS_BACKEND_GUIDE.md section 10. */
public record TeamUpdatedEvent(UUID id, List<String> changed) {
}
