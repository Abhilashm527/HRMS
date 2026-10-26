package com.ivoyant.hrms.config.dto;

import com.ivoyant.hrms.config.FieldAccess;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/** {@code side} is OWN or OTHERS; a cell with a null {@code level} removes the explicit rule for that side. */
public record AccessMatrixPatch(@NotNull String side, @NotNull List<CellChange> cells) {

	public record CellChange(@NotNull UUID fieldId, @NotNull UUID roleId, FieldAccess level) {
	}
}
