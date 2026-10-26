package com.ivoyant.hrms.common.event;

import java.util.UUID;

/** Someone asked to change an HR-approves field; approvers should be told. */
public record FieldChangeRequestedEvent(UUID requestId, String areaKey, UUID recordId, String fieldKey,
		UUID requestedBy) {
}
