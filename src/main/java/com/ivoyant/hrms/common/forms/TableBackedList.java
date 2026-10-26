package com.ivoyant.hrms.common.forms;

import com.ivoyant.hrms.common.web.ErrorItem;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A settings list (Settings → ... list editors) that lives in a real table instead of JSON. The config module collects every
 * implementation and saves a whole module in one transaction: all lists are upserted in {@link #order()} order, then rows missing
 * from the saved list are deleted in the reverse order, so a list can point at the lists written before it.
 */
public interface TableBackedList {

	/** The list editor module: {@code hardware}, {@code software}, {@code onboarding}. */
	String module();

	/** The list key inside the module: {@code statuses}, {@code types}, {@code m365} ... */
	String key();

	/** Lists that others point at come first. */
	int order();

	List<Map<String, Object>> load();

	/** Inserts or updates the rows (every row has a UUID {@code id}); rows not listed are left alone. */
	void upsert(List<Map<String, Object>> rows);

	/** Deletes the rows whose id is not in {@code keep}; throws InUse when something still points at one. */
	void deleteMissing(Set<UUID> keep);

	/**
	 * Rules that look at the list as a whole (at least one assignable status, a default rule ...). {@code all} holds every list of the
	 * module as it will be after the save.
	 */
	default void validate(List<Map<String, Object>> rows, Map<String, List<Map<String, Object>>> all, List<ErrorItem> errors) {
	}
}
