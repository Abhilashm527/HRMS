package com.ivoyant.hrms.leave;

import java.util.Map;
import java.util.UUID;

/** What the employee profile's Leave tab shows: the year's balances and requests. */
public interface LeaveSummary {

	Map<String, Object> forEmployee(UUID employeeId, int year, boolean canApply);
}
