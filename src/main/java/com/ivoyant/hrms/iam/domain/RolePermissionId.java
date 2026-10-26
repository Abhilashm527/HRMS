package com.ivoyant.hrms.iam.domain;

import java.io.Serializable;
import java.util.UUID;

public record RolePermissionId(UUID roleId, UUID permissionId, Scope scope) implements Serializable {
}
