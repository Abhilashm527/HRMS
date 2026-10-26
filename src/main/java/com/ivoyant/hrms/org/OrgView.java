package com.ivoyant.hrms.org;

import java.time.ZoneId;
import java.util.UUID;

/** The organization as other modules see it. */
public record OrgView(UUID id, String name, String legalName, String code, String country, ZoneId timezone,
		int fyStartMonth, String currency, String primaryDomain, String status, String employeeIdPrefix) {
}
