package com.ivoyant.hrms.common.web;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One problem in a 422 response: either a field ({@code fieldKey}) or a cell of a lookup list
 * ({@code listKey}, {@code index}, {@code column}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorItem(String fieldKey, String listKey, Integer index, String column, String code, String message) {

	public static ErrorItem field(String fieldKey, String code, String message) {
		return new ErrorItem(fieldKey, null, null, null, code, message);
	}

	public static ErrorItem cell(String listKey, int index, String column, String message) {
		return new ErrorItem(null, listKey, index, column, "VALIDATION", message);
	}
}
