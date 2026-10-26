package com.ivoyant.hrms.common.event;

import java.time.Instant;
import java.util.UUID;

/** Published when a delegation is created or removed; the employee module maintains the FGA tuples. */
public record DelegationChangedEvent(UUID delegationId, UUID fromEmployeeId, UUID toEmployeeId, String module,
		Instant startsAt, Instant endsAt, boolean removed) {
}
