package com.ivoyant.hrms.common.event;

import java.util.UUID;

/** See ASSETS_BACKEND_GUIDE.md, cross-module section. */
public record AssetAssignedEvent(UUID assetId, UUID employeeId) {
}
