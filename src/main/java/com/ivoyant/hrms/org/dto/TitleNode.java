package com.ivoyant.hrms.org.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A title as the list, the picker's detail and the drawer show it. The band text is left out for callers who may not see salaries. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TitleNode(UUID id, String name, String code, Position position, String bandText, Department department,
		String scopeText, String description, boolean active, int sort, Integer headcount, Integer extraHolders,
		Integer openJobs, Map<String, Object> custom, Long version,
		@JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> warnings) {

	public record Position(UUID id, String name, String grade, Integer level, boolean active) {
	}

	public record Department(UUID id, String name, String path) {
	}

	public TitleNode withExtras(Map<String, Object> customValues, List<String> warningList) {
		return new TitleNode(id, name, code, position, bandText, department, scopeText, description, active, sort,
				headcount, extraHolders, openJobs, customValues, version, warningList);
	}
}
