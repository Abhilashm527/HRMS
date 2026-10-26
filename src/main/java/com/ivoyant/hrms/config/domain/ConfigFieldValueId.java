package com.ivoyant.hrms.config.domain;

import java.io.Serializable;
import java.util.UUID;

public record ConfigFieldValueId(UUID orgId, String areaKey, UUID recordId, UUID fieldId) implements Serializable {
}
