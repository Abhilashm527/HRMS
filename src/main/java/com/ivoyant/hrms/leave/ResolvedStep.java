package com.ivoyant.hrms.leave;

import java.util.UUID;

/** One step of a request's approval chain after it was worked out for a person: who approves, and who they are standing in for (a delegation). */
public record ResolvedStep(int sort, String approver, UUID employeeId, String name, UUID delegatedFrom) {
}
