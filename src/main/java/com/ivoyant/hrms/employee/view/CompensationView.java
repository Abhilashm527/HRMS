package com.ivoyant.hrms.employee.view;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** What the Compensation tab shows. Payroll will implement this; until then the estimate below stands in. */
public interface CompensationView {

	Breakdown forEmployee(UUID employeeId, BigDecimal annualCtc);

	record Line(String key, String label, BigDecimal amount) {
	}

	record Breakdown(BigDecimal ctcAnnual, String regime, String ptState, List<Line> earnings, List<Line> deductions, BigDecimal netMonthly,
			boolean estimated) {
	}
}
