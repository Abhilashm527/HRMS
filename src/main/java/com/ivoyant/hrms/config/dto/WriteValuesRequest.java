package com.ivoyant.hrms.config.dto;

import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;

/**
 * {@code values} by field key. {@code draft} is true while a record is being created: HR-approves fields are then
 * written directly and required fields must be present. {@code ownerEmployeeId} (create only) says whose record it is.
 */
public record WriteValuesRequest(@NotNull Map<String, Object> values, Boolean draft, UUID ownerEmployeeId) {

	public boolean isDraft() {
		return Boolean.TRUE.equals(draft);
	}
}
