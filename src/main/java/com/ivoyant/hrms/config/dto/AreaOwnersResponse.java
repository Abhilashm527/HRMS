package com.ivoyant.hrms.config.dto;

import java.util.List;
import java.util.UUID;

public record AreaOwnersResponse(List<Role> roles, List<Row> areas, boolean canEdit) {

	public record Role(UUID id, String key, String name) {
	}

	public record Row(String key, String name, List<UUID> roleIds, boolean superAdminAlways) {
	}
}
