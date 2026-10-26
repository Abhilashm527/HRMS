package com.ivoyant.hrms.org;

import java.math.BigDecimal;
import java.util.Set;

/** How people may check in at a location; the coordinates are null until someone sets them. */
public record AttendancePolicy(Set<AttendanceMethod> methods, Integer geoRadiusM, BigDecimal latitude,
		BigDecimal longitude, boolean wfhAllowed) {
}
