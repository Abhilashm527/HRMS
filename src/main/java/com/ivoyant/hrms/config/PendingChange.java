package com.ivoyant.hrms.config;

import java.time.Instant;
import java.util.UUID;

/** A change to an HR-approves field that is waiting for a decision. */
public record PendingChange(UUID id, Object value, String by, Instant at, String text, boolean canDecide,
		boolean canCancel) {
}
