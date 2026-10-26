package com.ivoyant.hrms.config.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

public record PutOwnersRequest(@NotNull List<UUID> roleIds) {
}
