package com.ivoyant.hrms.config.service;

import com.ivoyant.hrms.common.forms.RecordCustomFields;
import com.ivoyant.hrms.config.FieldAccess;
import com.ivoyant.hrms.config.FormField;
import com.ivoyant.hrms.config.FormSection;
import com.ivoyant.hrms.config.FormView;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Lets other modules read and write the custom fields of their records through the config module. */
@Component
class CustomFieldsBridge implements RecordCustomFields {

	private final FormService forms;
	private final AreaCatalog catalog;

	CustomFieldsBridge(FormService forms, AreaCatalog catalog) {
		this.forms = forms;
		this.catalog = catalog;
	}

	@Override
	public Map<String, Object> read(String areaKey, UUID recordId) {
		if (!catalog.exists(areaKey)) {
			return Map.of();
		}
		FormView view = forms.render(areaKey, recordId, null);
		Map<String, Object> out = new LinkedHashMap<>();
		for (FormSection s : view.sections()) {
			for (FormField f : s.fields()) {
				out.put(f.key(), f.level() == FieldAccess.MASKED ? f.display() : f.value());
			}
		}
		return out;
	}

	@Override
	public void write(String areaKey, UUID recordId, Map<String, Object> values, boolean creating) {
		if (catalog.exists(areaKey)) {
			forms.write(areaKey, recordId, null, values, creating);
		}
	}

	@Override
	public void delete(String areaKey, UUID recordId) {
		if (catalog.exists(areaKey)) {
			forms.deleteValues(areaKey, recordId);
		}
	}
}
