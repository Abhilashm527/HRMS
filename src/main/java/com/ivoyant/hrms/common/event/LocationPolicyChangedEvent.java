package com.ivoyant.hrms.common.event;

import java.util.UUID;
import java.util.List;
import java.time.LocalDate;

/** Published by the org module; see LOCATIONS_BACKEND_GUIDE.md section 7. */
public record LocationPolicyChangedEvent(UUID id, List<String> changed, LocalDate effectiveFrom) {
}
