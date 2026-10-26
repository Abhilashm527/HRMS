package com.ivoyant.hrms.common.event;

import java.util.List;
import java.util.UUID;

/** Values of a record were written directly; a notification module can pick this up. */
public record FieldValueChangedEvent(String areaKey, UUID recordId, List<String> fieldKeys, UUID actorId) {
}
