package com.ivoyant.hrms.common.forms;

import java.util.Map;
import java.util.UUID;

/**
 * The custom (admin-defined) fields of a record, for modules that hold the record but not its form definition. The
 * config module implements it, so a module such as org can show and save a department's custom fields without
 * depending on config.
 */
public interface RecordCustomFields {

	/** What the caller may see, by field key (a masked field shows its masked text). */
	Map<String, Object> read(String areaKey, UUID recordId);

	/** Validates and stores values; {@code creating} also enforces required fields and applies defaults. */
	void write(String areaKey, UUID recordId, Map<String, Object> values, boolean creating);

	void delete(String areaKey, UUID recordId);
}
