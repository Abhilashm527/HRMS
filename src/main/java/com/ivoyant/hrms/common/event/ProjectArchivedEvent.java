package com.ivoyant.hrms.common.event;

import java.util.UUID;

/** See TEAMS_PROJECTS_BACKEND_GUIDE.md section 10. */
public record ProjectArchivedEvent(UUID id, boolean archived) {
}
