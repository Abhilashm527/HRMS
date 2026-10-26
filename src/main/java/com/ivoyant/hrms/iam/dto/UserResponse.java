package com.ivoyant.hrms.iam.dto;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(UUID id, UUID employeeId, String externalSubject, String status, Instant lastLoginAt) {
}
