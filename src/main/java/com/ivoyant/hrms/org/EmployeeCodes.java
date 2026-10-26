package com.ivoyant.hrms.org;

import java.time.LocalDate;

/** Generates employee codes from the organization's pattern, e.g. {@code {PREFIX}-{SEQ4}} gives IVY-1031. */
public interface EmployeeCodes {

	/** Reserves and returns the next code; call it in the same transaction as the employee insert. */
	String next(LocalDate joiningDate);

	/** What the next code would be, without reserving it. */
	String preview(String pattern);
}
