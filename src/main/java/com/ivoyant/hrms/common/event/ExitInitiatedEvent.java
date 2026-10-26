package com.ivoyant.hrms.common.event;

import java.util.UUID;

/** See EMPLOYEES_BACKEND_GUIDE.md section K. */
public record ExitInitiatedEvent(UUID employeeId, java.time.LocalDate lastWorkingDay) {
}
