package com.ivoyant.hrms.config.dto;

import java.time.Instant;
import java.util.UUID;

public record ChangeRequestResponse(UUID id, String area, String areaName, UUID recordId, String recordLabel,
		String fieldKey, String fieldLabel, Object oldValue, Object newValue, String requestedBy, Instant requestedAt,
		String status, String note) {
}
