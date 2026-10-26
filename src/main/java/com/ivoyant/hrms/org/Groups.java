package com.ivoyant.hrms.org;

import java.util.Map;
import java.util.UUID;

/** A person's teams and projects, for modules that save them together with other things (Reporting lines). */
public interface Groups {

	/**
	 * Replaces the person's current teams and/or projects: {@code teams: [{teamId, primary}]},
	 * {@code projects: [{projectId, role, allocation}]}, {@code effectiveDate}. Throws the usual 409/422 when a rule is broken.
	 */
	void replace(UUID employeeId, Map<String, Object> body);
}
