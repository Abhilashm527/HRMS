package com.ivoyant.hrms.config.domain;

import java.io.Serializable;
import java.util.UUID;

public record ConfigRecordOwnerId(UUID orgId, String areaKey, UUID recordId) implements Serializable {
}
