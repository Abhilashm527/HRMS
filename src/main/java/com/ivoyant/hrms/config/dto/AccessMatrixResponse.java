package com.ivoyant.hrms.config.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ivoyant.hrms.config.FieldAccess;
import java.util.List;
import java.util.UUID;

public record AccessMatrixResponse(String side, String text, List<MatrixRole> roles, List<MatrixRow> rows) {

	public record MatrixRole(UUID id, String key, String name) {
	}

	/** A SECTION heading row (name) or a FIELD row (fieldId, label, cells). */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record MatrixRow(String type, String name, UUID fieldId, String label, List<MatrixCell> cells) {
	}

	public record MatrixCell(UUID roleId, FieldAccess level, boolean explicit) {
	}
}
