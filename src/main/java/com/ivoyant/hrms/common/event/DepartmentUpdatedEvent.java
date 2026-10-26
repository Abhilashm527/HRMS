package com.ivoyant.hrms.common.event;

import java.util.List;
import java.util.UUID;

/** See DEPARTMENTS_BACKEND_GUIDE.md section 10. */
public record DepartmentUpdatedEvent(UUID id, List<String> changed) {
}
