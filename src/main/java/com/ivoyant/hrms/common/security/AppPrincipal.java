package com.ivoyant.hrms.common.security;

import java.util.UUID;

/** Principal used by the local-profile dev filter. */
public record AppPrincipal(UUID employeeId, UUID orgId) {
}
