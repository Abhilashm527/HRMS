package com.ivoyant.hrms.config.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/** The whole draft: any list left out is not touched. Rows without a UUID {@code id} are new. */
public record SaveListsRequest(@NotNull Map<String, List<Map<String, Object>>> lists) {
}
