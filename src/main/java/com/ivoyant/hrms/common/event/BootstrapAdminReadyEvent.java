package com.ivoyant.hrms.common.event;

import java.util.UUID;

/** The first super admin was just set up; sign-in sends them their account link. */
public record BootstrapAdminReadyEvent(UUID employeeId) {
}
