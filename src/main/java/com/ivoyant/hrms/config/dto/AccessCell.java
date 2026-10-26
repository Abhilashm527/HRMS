package com.ivoyant.hrms.config.dto;

import com.ivoyant.hrms.config.FieldAccess;
import java.util.UUID;

/** A role's level on a field for its own record and for other people's; {@code explicit} = set by an admin. */
public record AccessCell(UUID roleId, String roleKey, String roleName, FieldAccess own, FieldAccess others,
		boolean explicit) {
}
