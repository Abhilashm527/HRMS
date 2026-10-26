package com.ivoyant.hrms.iam;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** What other modules may ask the iam module. */
public interface IamDirectory {

	/** Role ids held by the employee; always includes the org's default {@code employee} role. */
	Set<UUID> roleIdsOf(UUID employeeId);

	/** Every role of the caller's org. */
	List<RoleRef> roles();

	Optional<RoleRef> roleByKey(String key);

	/**
	 * The employee's own roles (always including {@code employee}) plus the roles of anyone who has an active
	 * delegation to them for {@code module}.
	 */
	Set<RoleRef> effectiveRoles(UUID employeeId, String module);

	/** Highest permission each role has in the module; roles with none are absent from the map. */
	Map<UUID, PermLevel> permissionLevels(String module);
}
