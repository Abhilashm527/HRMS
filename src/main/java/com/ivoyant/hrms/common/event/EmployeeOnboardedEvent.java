package com.ivoyant.hrms.common.event;

import java.util.UUID;

/** Published when a new hire joins and becomes an employee: leave, payroll and notifications pick it up. */
public record EmployeeOnboardedEvent(UUID orgId, UUID employeeId, UUID hireId) {
}
