package com.ivoyant.hrms.config.dto;

import java.util.List;

public record AreaSummary(String key, String name, String description, String icon, String module, int sort,
		int sectionCount, int fieldCount, List<String> owners) {
}
