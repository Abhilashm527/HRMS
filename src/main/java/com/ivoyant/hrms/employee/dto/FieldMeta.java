package com.ivoyant.hrms.employee.dto;

import com.ivoyant.hrms.config.FieldAccess;
import com.ivoyant.hrms.config.FieldType;

/** What the UI needs to render one field: its definition and what the caller may do with it. */
public record FieldMeta(String label, FieldType type, boolean required, FieldAccess access, boolean editable,
		String display) {
}
