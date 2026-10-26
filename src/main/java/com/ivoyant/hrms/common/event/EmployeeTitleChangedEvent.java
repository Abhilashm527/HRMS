package com.ivoyant.hrms.common.event;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** See TITLES_BACKEND_GUIDE.md section 10. */
public record EmployeeTitleChangedEvent(UUID employeeId, UUID fromPrimary, UUID toPrimary, List<UUID> extrasAdded, List<UUID> extrasRemoved, String reason, LocalDate effectiveDate) {
}
