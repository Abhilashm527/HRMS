package com.ivoyant.hrms.common.event;

import java.util.List;

/** Published by the org module; see ORGANIZATION_BACKEND_GUIDE.md. */
public record OrgUpdatedEvent(List<String> changed) {
}
