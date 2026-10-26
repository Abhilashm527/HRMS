package com.ivoyant.hrms.config;

import com.ivoyant.hrms.common.web.ErrorItem;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** What other modules may ask the config module about the fields and forms of an area. */
public interface FieldConfig {

	/** Active fields of active sections in display order (section, then field). */
	List<FieldDefinition> activeFields(String areaKey);

	/** Level of every active field for the caller on a record owned by {@code ownerEmployeeId} (null = no owner). */
	Map<String, FieldAccess> levels(String areaKey, UUID ownerEmployeeId);

	FieldAccess levelFor(String areaKey, String fieldKey, UUID ownerEmployeeId);

	/** Keys of fields whose visibility condition is false for these values. */
	Set<String> hiddenByCondition(String areaKey, Map<String, Object> values);

	FormView render(String areaKey, UUID recordId, UUID ownerEmployeeId);

	/** Blank form for create: visible fields with their defaults. */
	FormView blank(String areaKey, UUID ownerEmployeeId);

	/**
	 * Writes values for a record: EDIT fields directly, HR_APPROVES fields as change requests (unless {@code draft}).
	 * Throws 403 for a field the caller cannot write and 422 with every validation error.
	 */
	WriteResult write(String areaKey, UUID recordId, UUID ownerEmployeeId, Map<String, Object> values, boolean draft);

	List<ErrorItem> validateDraft(String areaKey, UUID ownerEmployeeId, Map<String, Object> values);

	/** Labels of required, writable, visible fields that have no value in {@code values}. */
	List<String> missingRequired(String areaKey, UUID ownerEmployeeId, Map<String, Object> values);

	/** Removes stored values and open change requests of a record that was deleted. */
	void deleteValues(String areaKey, UUID recordId);
}
