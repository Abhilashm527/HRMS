package com.ivoyant.hrms.iam.dto;

import com.ivoyant.hrms.iam.domain.Scope;
import java.util.UUID;

public record RolePermissionResponse(UUID permissionId, String module, String action, Scope scope) {
}
