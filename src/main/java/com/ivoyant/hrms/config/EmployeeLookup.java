package com.ivoyant.hrms.config;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/** Employee names for display, implemented by the employee module. */
public interface EmployeeLookup {

	/** Names of the employees that exist; unknown ids are absent. */
	Map<UUID, String> names(Collection<UUID> ids);
}
