package com.ivoyant.hrms.org.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A department as the hierarchy screen, the list and the drawer show it. The same shape serves the tree ({@code children}),
 * the flat list ({@code parentId}, {@code path}) and a single department ({@code custom}); parts that were not asked for
 * are left out.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DepartmentNode(UUID id, String name, String code, UUID parentId, String parentName, List<String> path,
		Integer level, Head head, String costCentre, Ref defaultLocation, boolean active, String description, int sort,
		Integer headcount, Integer headcountTotal, Integer childCount, Boolean overDepth,
		Map<String, Object> custom, Long version, List<DepartmentNode> children,
		@JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> warnings) {

	public record Head(UUID employeeId, String name, String initials) {
	}

	public record Ref(UUID id, String name) {
	}

	public DepartmentNode withChildren(List<DepartmentNode> kids) {
		return new DepartmentNode(id, name, code, parentId, parentName, path, level, head, costCentre, defaultLocation,
				active, description, sort, headcount, headcountTotal, childCount, overDepth, custom, version, kids, warnings);
	}

	public DepartmentNode withExtras(Map<String, Object> customValues, List<String> warningList) {
		return new DepartmentNode(id, name, code, parentId, parentName, path, level, head, costCentre, defaultLocation,
				active, description, sort, headcount, headcountTotal, childCount, overDepth, customValues, version,
				children, warningList);
	}
}
