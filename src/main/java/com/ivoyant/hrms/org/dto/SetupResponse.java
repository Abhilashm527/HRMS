package com.ivoyant.hrms.org.dto;

import java.util.List;

public record SetupResponse(int done, int total, int pct, String text, List<Step> steps) {

	/** {@code tab} is the settings tab the step opens; {@code canAck} steps are confirmed by an admin. */
	public record Step(int n, String key, String tab, String title, String desc, boolean done, String status,
			boolean canAck) {
	}
}
