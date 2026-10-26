package com.ivoyant.hrms.config.domain;

import java.io.Serializable;
import java.util.UUID;

public record ConfigFieldAccessId(UUID fieldId, UUID roleId) implements Serializable {
}
