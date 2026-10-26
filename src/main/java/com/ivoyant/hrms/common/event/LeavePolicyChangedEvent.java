package com.ivoyant.hrms.common.event;

import java.util.UUID;

/** A leave type was created, changed or deleted ({@code typeId} is null when the approval chain changed). */
public record LeavePolicyChangedEvent(UUID typeId) {
}
