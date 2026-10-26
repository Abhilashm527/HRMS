package com.ivoyant.hrms.org;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** What other modules may ask about teams (they never touch the tables). */
public interface Teams {

	Optional<TeamView> get(UUID id);

	/** The teams a person is in now, primary first. */
	List<TeamView> ofEmployee(UUID employeeId);

	Optional<UUID> leadOf(UUID teamId);

	/** Current members. */
	Set<UUID> members(UUID teamId);

	/** Active teams in the department and the departments above it. */
	List<TeamView> optionsFor(UUID departmentId);
}
