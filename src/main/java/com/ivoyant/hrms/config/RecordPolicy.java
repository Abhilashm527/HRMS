package com.ivoyant.hrms.config;

import java.util.UUID;

/**
 * Implemented by the module that owns the records of an area (employee for the profile). Says who a record belongs
 * to, whether the caller may open it at all, and what to call it. Areas nobody implements fall back to "no owner,
 * anyone may open, field access decides".
 */
public interface RecordPolicy {

	boolean supports(String areaKey);

	/** Employee the record belongs to, or null when it has no owner. */
	UUID owner(String areaKey, UUID recordId);

	boolean canOpen(String areaKey, UUID recordId);

	String label(String areaKey, UUID recordId);
}
