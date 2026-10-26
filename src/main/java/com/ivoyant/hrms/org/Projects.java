package com.ivoyant.hrms.org;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** What other modules may ask about projects (they never touch the tables). */
public interface Projects {

	Optional<ProjectView> getProject(UUID id);

	/** A person's current project memberships, with role and allocation. */
	List<Membership> ofEmployeeProjects(UUID employeeId);

	/** The sum of their allocation on planned, active and on-hold projects. */
	int totalAllocation(UUID employeeId);

	/** Only active (not on hold, not completed, not archived) projects take timesheet entries. */
	boolean acceptsTimesheets(UUID projectId);

	/** Current members. */
	Set<UUID> projectMembers(UUID projectId);

	record Membership(UUID projectId, String projectName, String code, String role, int allocation) {
	}
}
