package com.ivoyant.hrms.config;

import java.util.List;
import java.util.UUID;

/** An active field of an area, as other modules see it. */
public record FieldDefinition(UUID id, String areaKey, String key, String label, FieldType type, boolean required,
		boolean system, Object defaultValue, List<String> options, String helpText, VisibilityRule visibilityRule,
		int sort, UUID sectionId, String sectionKey, boolean maskedDefault, String source, String validator) {
}
