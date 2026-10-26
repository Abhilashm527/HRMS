package com.ivoyant.hrms.common.event;

import java.util.UUID;

/** See DEPARTMENTS_BACKEND_GUIDE.md section 10. */
public record DepartmentActivationChangedEvent(UUID id, boolean active) {
}
