package com.ivoyant.hrms.config.dto;

import com.ivoyant.hrms.config.FieldAccess;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record AccessEntry(@NotNull UUID roleId, FieldAccess own, FieldAccess others) {
}
