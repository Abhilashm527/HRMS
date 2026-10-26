package com.ivoyant.hrms.org.dto;

import java.util.UUID;

/** Counts for the header and the setup checklist; {@code text} is exactly what the checklist shows. */
public record PositionSummary(long total, long active, long withoutBand, long unused, Top top, long employeesWithout,
		String text, String warnText) {

	public record Top(UUID id, String name) {
	}
}
