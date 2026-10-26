package com.ivoyant.hrms.config;

import java.util.UUID;

/** Access of one role to one field; {@code own}/{@code others} override {@code access} for self/other records. */
public record FieldAccessRule(String fieldKey, UUID roleId, FieldAccess access, FieldAccess ownAccess,
		FieldAccess othersAccess) {
}
