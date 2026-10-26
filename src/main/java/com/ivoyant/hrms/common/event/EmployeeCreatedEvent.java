package com.ivoyant.hrms.common.event;

import java.util.UUID;

/** Published when an employee is created; the iam module provisions the user and default role. */
public record EmployeeCreatedEvent(UUID orgId, UUID employeeId) {
}
