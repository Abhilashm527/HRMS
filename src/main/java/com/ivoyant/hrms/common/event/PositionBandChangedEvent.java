package com.ivoyant.hrms.common.event;

import java.math.BigDecimal;
import java.util.UUID;

/** See POSITIONS_BACKEND_GUIDE.md section 10. */
public record PositionBandChangedEvent(UUID id, BigDecimal oldMin, BigDecimal oldMax, BigDecimal newMin, BigDecimal newMax) {
}
