package com.ivoyant.hrms.org;

import java.util.UUID;

/** An employment type as other modules see it. */
public record TypeView(UUID id, String name, String code, int probationMonths, int noticeDays, boolean leaveEligible,
		boolean payrollEligible, boolean fixedTerm, boolean active) {
}
