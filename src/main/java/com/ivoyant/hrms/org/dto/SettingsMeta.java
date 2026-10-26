package com.ivoyant.hrms.org.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Drives the settings page: groups, row labels, hints and the choices of each select. */
public record SettingsMeta(List<Group> groups) {

	public record Group(String key, String label, List<Row> rows) {
	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Row(String key, String label, String kind, String hint, Object options, String preview) {
	}
}
