package com.ivoyant.hrms.org;

import java.math.BigDecimal;

/**
 * An offer or salary against a position's band. {@code result} is WITHIN, BELOW (warning), ABOVE (needs approval when
 * the organization says so) or NO_BAND (warning). Amounts are full yearly amounts in {@code currency}.
 */
public record BandCheck(String result, String position, BigDecimal bandMin, BigDecimal bandMax, String currency,
		BigDecimal deltaPct, boolean needsApproval, String message) {
}
