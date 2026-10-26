package com.ivoyant.hrms.org;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Dotted lines and mentors that are in force today. They never approve anything. */
public interface Relations {

	Optional<UUID> dottedOf(UUID employeeId);

	List<UUID> mentorsOf(UUID employeeId);

	/** The id of the dotted line the employee has today (or from a date in the past that has not ended), if any. */
	Optional<UUID> currentDottedRelation(UUID employeeId);

	/** Adds a dotted line from {@code from} on, under all the dotted-line rules. */
	void addDotted(UUID employeeId, UUID relatedId, java.time.LocalDate from);

	/** Ends a relation today; it stays in the history. */
	void endRelation(UUID relationId, String reason);
}
