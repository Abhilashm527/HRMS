package com.ivoyant.hrms.config.dto;

import com.ivoyant.hrms.config.FieldType;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record FieldDetail(UUID id, UUID sectionId, String key, String label, FieldType type, boolean required,
		Object defaultValue, String helpText, List<String> options, Map<String, Object> visibilityRule, int sort,
		boolean system, boolean active, boolean maskedDefault, String source, boolean hasData, Long version,
		List<AccessCell> access) {
}
