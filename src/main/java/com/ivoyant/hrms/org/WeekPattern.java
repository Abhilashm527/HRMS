package com.ivoyant.hrms.org;

public enum WeekPattern {
	MON_FRI("Mon – Fri"), MON_SAT("Mon – Sat"), MON_FRI_ALT_SAT("Mon – Fri + alternate Sat");

	private final String label;

	WeekPattern(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}
}
