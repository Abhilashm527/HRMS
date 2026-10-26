package com.ivoyant.hrms.config.dto;

import java.util.List;

public record FieldUsage(long valueCount, long pendingCount, List<String> usedInConditions, boolean system) {
}
