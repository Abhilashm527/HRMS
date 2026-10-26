package com.ivoyant.hrms.org;

public enum LocationType {
	HEAD_OFFICE("Head office", "#1D4ED8", "#DBEAFE"),
	BRANCH_OFFICE("Branch office", "#0F766E", "#CCFBF1"),
	CO_WORKING("Co-working", "#B45309", "#FEF3C7"),
	WAREHOUSE("Warehouse", "#6D28D9", "#EDE9FE"),
	REMOTE_HUB("Remote hub", "#475569", "#F1F5F9");

	private final String label;
	private final String fg;
	private final String bg;

	LocationType(String label, String fg, String bg) {
		this.label = label;
		this.fg = fg;
		this.bg = bg;
	}

	public String label() {
		return label;
	}

	public String fg() {
		return fg;
	}

	public String bg() {
		return bg;
	}
}
