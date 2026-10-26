package com.ivoyant.hrms.iam.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/** {@code fromUserId} defaults to the caller; only HR admins may delegate on someone else's behalf. */
public record CreateDelegationRequest(UUID fromUserId, @NotNull UUID toUserId, @NotBlank String module,
		@NotNull Instant startsAt, @NotNull Instant endsAt, String reason) {
}
