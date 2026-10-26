package com.ivoyant.hrms.common.event;

import java.util.UUID;

/** See POSITIONS_BACKEND_GUIDE.md section 10. */
public record PositionActivationChangedEvent(UUID id, boolean active) {
}
