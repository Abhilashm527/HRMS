package com.ivoyant.hrms.common.event;

import java.util.UUID;

/** See EMPLOYMENT_TYPES_REPORTING_RELATIONS_GUIDE.md section D3. */
public record EmploymentTypeCreatedEvent(UUID id) {
}
