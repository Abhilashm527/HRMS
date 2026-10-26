package com.ivoyant.hrms.org;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** What other modules may ask about job titles (they never touch the repository). */
public interface Titles {

	Optional<TitleView> get(UUID id);

	Map<UUID, String> names(Collection<UUID> ids);

	/** Is the title usable in the department? Titles with no department fit everywhere; others fit that department and below. */
	boolean validFor(UUID titleId, UUID departmentId);

	/** Active titles that fit the department (all active titles when the department is null). */
	List<TitleView> optionsFor(UUID departmentId);

	/** "Senior Software Engineer · Senior Associate", from the organization's title display format. */
	String display(UUID employeeId);

	/** {@link #display} for many employees at once. */
	Map<UUID, String> displays(Collection<UUID> employeeIds);

	Optional<UUID> positionOf(UUID titleId);
}
