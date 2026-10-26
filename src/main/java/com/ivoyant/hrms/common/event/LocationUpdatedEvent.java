package com.ivoyant.hrms.common.event;

import java.util.UUID;
import java.util.List;

/** Published by the org module; see LOCATIONS_BACKEND_GUIDE.md section 7. */
public record LocationUpdatedEvent(UUID id, List<String> changed) {
}
