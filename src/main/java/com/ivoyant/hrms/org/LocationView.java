package com.ivoyant.hrms.org;

import java.time.ZoneId;
import java.util.UUID;

/** A location as other modules see it. */
public record LocationView(UUID id, String name, String code, LocationType type, String addressLine, String city,
		String state, String postalCode, String country, ZoneId timezone, boolean active, UUID headEmployeeId,
		Integer seatCapacity, boolean assetStore, UUID holidayCalendarId) {
}
