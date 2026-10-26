package com.ivoyant.hrms.config;

import java.util.Map;
import java.util.UUID;

/**
 * Implemented when an area's values live in the owning module's own tables instead of {@code config_field_value}
 * (the employee profile: real columns plus the {@code custom} jsonb).
 */
public interface RecordFieldStore {

	String areaKey();

	/** Current value of every field of the record, by field key. */
	Map<String, Object> read(UUID recordId);

	/** Applies already validated and authorised values; the store enforces its own business rules. */
	void apply(UUID recordId, Map<String, Object> values);

	/** How many records have a value for this field. */
	long countValues(String fieldKey);
}
