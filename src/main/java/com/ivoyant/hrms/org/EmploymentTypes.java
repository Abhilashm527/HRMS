package com.ivoyant.hrms.org;

import java.util.Optional;
import java.util.UUID;

/** What other modules may ask about employment types. */
public interface EmploymentTypes {

	Optional<TypeView> get(UUID id);

	/** Finds a type by id, name or code (the profile field once stored the name); empty when nothing matches. */
	Optional<TypeView> find(String idNameOrCode);

	boolean leaveEligible(UUID id);

	boolean payrollEligible(UUID id);

	int noticeDays(UUID id);

	int probationMonths(UUID id);
}
