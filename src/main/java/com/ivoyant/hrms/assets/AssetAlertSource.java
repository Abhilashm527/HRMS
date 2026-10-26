package com.ivoyant.hrms.assets;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Where the daily alert job finds assets and licences whose date has come (warranty end, lease end, renewal ...). The asset register
 * plugs in here; until it exists there is nothing to alert about.
 */
public interface AssetAlertSource {

	/** Records whose {@code event} date (a code such as WARRANTY_END) is exactly {@code date}. */
	List<Due> dueOn(String kind, String event, LocalDate date);

	record Due(UUID recordId, String label, UUID holderEmployeeId) {
	}
}
