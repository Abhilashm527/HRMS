package com.ivoyant.hrms.org;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What other modules may ask the org module. Returns names for the ids that exist in the caller's org; ids that
 * do not exist are simply absent from the result, which doubles as an existence check.
 */
public interface OrgDirectory {

	Map<UUID, String> departmentNames(Collection<UUID> ids);

	/** Like {@link #departmentNames}, but only active departments: what a picker or a new assignment may use. */
	Map<UUID, String> activeDepartmentNames(Collection<UUID> ids);

	Map<UUID, String> designationNames(Collection<UUID> ids);

	/** Like {@link #designationNames} but only positions that can still be given to someone. */
	Map<UUID, String> activeDesignationNames(Collection<UUID> ids);

	Map<UUID, String> locationNames(Collection<UUID> ids);

	/** Like {@link #locationNames}, but only active locations: what a picker or a new assignment may use. */
	Map<UUID, String> activeLocationNames(Collection<UUID> ids);

	/** The default work location of a department, if it has one. */
	Optional<UUID> defaultLocationOfDepartment(UUID departmentId);
}
