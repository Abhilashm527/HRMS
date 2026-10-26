package com.ivoyant.hrms.iam.dto;

import java.util.UUID;

public record AreaOwnerResponse(UUID id, String areaKey, UUID userId, UUID employeeId) {
}
