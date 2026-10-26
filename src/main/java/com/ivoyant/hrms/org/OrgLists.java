package com.ivoyant.hrms.org;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The lookup lists the org module owns (Settings → Onboarding): departments, designations (positions), titles,
 * levels, teams, projects and employment types. Rows are maps keyed by the settings UI's column keys, each with
 * an {@code id}. Saving is split in two so a caller can write every list first and delete afterwards.
 */
public interface OrgLists {

	Set<String> keys();

	List<Map<String, Object>> load(String listKey);

	/** Inserts or updates the rows; every row has a UUID {@code id}. Rows not listed are left alone. */
	void upsert(String listKey, List<Map<String, Object>> rows);

	/** Deletes the rows whose id is not in {@code keep}; throws InUse when something still points at one. */
	void deleteMissing(String listKey, Set<UUID> keep);
}
