package com.ivoyant.hrms.config.domain;

import java.io.Serializable;
import java.util.UUID;

public record AreaOwnerRoleId(UUID orgId, String areaKey, UUID roleId) implements Serializable {
}
