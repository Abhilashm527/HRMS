package com.ivoyant.hrms.config.dto;

import com.ivoyant.hrms.config.FieldType;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;

/** The UI's "+ Add field" sends only {@code sectionId}, {@code label} and {@code type}; the rest has defaults. */
public record CreateFieldRequest(@NotNull UUID sectionId, String label, @NotNull FieldType type, String key,
		Boolean required, Object defaultValue, String helpText, Object options, Map<String, Object> visibilityRule,
		Boolean maskedDefault) {
}
