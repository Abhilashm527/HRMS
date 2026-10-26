package com.ivoyant.hrms.leave;

import java.math.BigDecimal;
import java.util.UUID;

/** A leave type as the leave engine sees it. */
public record LeaveTypeView(UUID id, String code, String name, String shortLabel, int colorIndex, boolean active, boolean system, String accrual,
		BigDecimal quota, String carry, BigDecimal carryMax, int expiryDays, boolean encashable, BigDecimal encashMax, String countAs,
		boolean paid, String gender, boolean halfDay, int noticeDays, int maxConsecutive, int docAfterDays, int minServiceDays) {
}
