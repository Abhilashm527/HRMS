package com.ivoyant.hrms.common.event;

import java.util.UUID;

/** See POSITIONS_BACKEND_GUIDE.md section 10. */
public record PositionReportsToChangedEvent(UUID id, UUID oldReportsTo, UUID newReportsTo) {
}
