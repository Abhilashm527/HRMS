package com.ivoyant.hrms.employee.dto;

import java.util.Map;

/** {@code fields} tells the UI, per visible field, whether the caller may edit it. */
public record EmployeeMeta(boolean self, Map<String, FieldMeta> fields) {
}
