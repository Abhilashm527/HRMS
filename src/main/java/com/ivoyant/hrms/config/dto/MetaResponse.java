package com.ivoyant.hrms.config.dto;

import com.ivoyant.hrms.config.FieldType;
import java.util.List;
import java.util.Map;

public record MetaResponse(List<Option> types, Map<String, List<FieldType>> typesByArea, List<Option> levels,
		List<Option> conditionOps) {

	public record Option(String value, String label) {
	}
}
