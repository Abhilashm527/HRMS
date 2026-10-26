package com.ivoyant.hrms.employee.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.Map;

/**
 * Partial update by field key (e.g. {@code {"phone": "...", "manager": "<uuid>"}}). Each key must be an active
 * field the caller may edit. A {@code null} value clears an optional field.
 */
public record UpdateEmployeeRequest(@NotEmpty Map<String, Object> fields) {
}
