package com.ivoyant.hrms.iam.dto;

import com.ivoyant.hrms.iam.domain.Scope;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RolePermissionRequest(@NotBlank String module, @NotBlank String action, @NotNull Scope scope) {
}
