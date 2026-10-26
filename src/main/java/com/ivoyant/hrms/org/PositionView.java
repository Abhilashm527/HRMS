package com.ivoyant.hrms.org;

import java.math.BigDecimal;
import java.util.UUID;

/** A position as other modules see it. The band is the full yearly amount (not masked: callers apply their own rules). */
public record PositionView(UUID id, String name, String grade, Integer level, String track, BigDecimal bandMin,
		BigDecimal bandMax, UUID reportsToId, boolean approves, boolean active, UUID jobLevelId) {
}
