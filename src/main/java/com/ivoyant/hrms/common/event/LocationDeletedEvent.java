package com.ivoyant.hrms.common.event;

import java.util.UUID;

/** Published by the org module; see LOCATIONS_BACKEND_GUIDE.md section 7. */
public record LocationDeletedEvent(UUID id) {
}
