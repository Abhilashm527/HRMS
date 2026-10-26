package com.ivoyant.hrms.iam.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateAreaOwnerRequest(@NotBlank String areaKey, @NotNull UUID userId) {
}
