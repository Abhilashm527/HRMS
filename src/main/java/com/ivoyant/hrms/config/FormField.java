package com.ivoyant.hrms.config;

import java.util.List;

/**
 * One field of a rendered form, as the caller may see it. {@code value} is null for a MASKED field (see
 * {@code display}).
 */
public record FormField(String key, String label, FieldType type, FieldAccess level, boolean required,
		String helpText, List<String> options, Object value, String display, PendingChange pending) {
}
