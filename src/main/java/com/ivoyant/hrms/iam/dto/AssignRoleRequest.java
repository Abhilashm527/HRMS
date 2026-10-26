package com.ivoyant.hrms.iam.dto;

import com.ivoyant.hrms.iam.domain.Scope;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** {@code scope} defaults to ORGANIZATION; {@code scopeRef} is the department/location id for those scopes. */
public record AssignRoleRequest(@NotNull UUID roleId, Scope scope, UUID scopeRef) {
}
