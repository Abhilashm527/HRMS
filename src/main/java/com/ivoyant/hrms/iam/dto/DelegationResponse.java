package com.ivoyant.hrms.iam.dto;

import java.time.Instant;
import java.util.UUID;

public record DelegationResponse(UUID id, UUID fromUserId, UUID toUserId, String module, Instant startsAt,
		Instant endsAt, String reason) {
}
