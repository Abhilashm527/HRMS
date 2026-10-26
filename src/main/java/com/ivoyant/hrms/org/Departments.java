package com.ivoyant.hrms.org;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** What other modules may ask about departments (they never touch the repository). Cached and cleared on change. */
public interface Departments {

	Optional<DepartmentView> get(UUID id);

	Map<UUID, String> names(Collection<UUID> ids);

	boolean isActive(UUID id);

	/** Parents above the department, nearest first. */
	List<UUID> ancestors(UUID id);

	/** The department and everything below it (roles scoped to a department cover its sub-departments). */
	Set<UUID> descendants(UUID id);

	/** The department's own head. */
	Optional<UUID> headOf(UUID departmentId);

	/** The head of the department, or of the nearest ancestor that has one. */
	Optional<UUID> effectiveHead(UUID departmentId);

	List<UUID> departmentsHeadedBy(UUID employeeId);

	Optional<UUID> defaultLocation(UUID departmentId);

	/** "Leadership › Engineering" */
	String path(UUID id, String separator);
}
