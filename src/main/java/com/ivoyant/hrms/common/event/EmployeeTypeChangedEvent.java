package com.ivoyant.hrms.common.event;

import java.time.LocalDate;
import java.util.UUID;

/** See EMPLOYMENT_TYPES_REPORTING_RELATIONS_GUIDE.md section D3. */
public record EmployeeTypeChangedEvent(UUID employeeId, UUID from, UUID to, LocalDate effectiveDate) {
}
