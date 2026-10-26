package com.ivoyant.hrms.iam.dto;

import java.util.UUID;

public record RoleResponse(UUID id, String key, String name, String description, boolean system) {
}
