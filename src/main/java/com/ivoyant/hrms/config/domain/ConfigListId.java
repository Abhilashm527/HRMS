package com.ivoyant.hrms.config.domain;

import java.io.Serializable;
import java.util.UUID;

public record ConfigListId(UUID orgId, String module, String listKey) implements Serializable {
}
