package com.ivoyant.hrms.common.event;

import java.util.List;
import java.util.UUID;

/** See POSITIONS_BACKEND_GUIDE.md section 10. */
public record PositionUpdatedEvent(UUID id, List<String> changed) {
}
