package com.ivoyant.hrms.iam.dto;

import com.ivoyant.hrms.iam.domain.Scope;
import java.util.UUID;

public record UserRoleResponse(UUID id, UUID roleId, String roleKey, String roleName, Scope scope, UUID scopeRef) {
}
