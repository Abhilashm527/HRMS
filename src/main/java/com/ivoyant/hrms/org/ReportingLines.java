package com.ivoyant.hrms.org;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Who reports to whom (the direct line only: dotted lines and mentors have no authority). */
public interface ReportingLines {

	Optional<UUID> managerOf(UUID employeeId);

	/** The managers above the employee, nearest first. */
	List<UUID> chain(UUID employeeId);

	/** Direct reports, or everyone below when {@code all}. */
	List<UUID> reports(UUID employeeId, boolean all);

	/** Is {@code a} somewhere above {@code b} in the direct chain? */
	boolean isAbove(UUID a, UUID b);
}
