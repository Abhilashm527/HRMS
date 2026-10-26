package com.ivoyant.hrms.org.domain;

import java.util.Locale;

/** The career track of a position: stored as the enum name, shown and accepted as the label too. */
public enum PositionTrack {
	EXECUTIVE("Executive"), MANAGEMENT("Management"), INDIVIDUAL_CONTRIBUTOR("Individual contributor");

	private final String label;

	PositionTrack(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

	/** Accepts "EXECUTIVE", "Executive", "individual contributor" and "INDIVIDUAL-CONTRIBUTOR"; null if unknown. */
	public static PositionTrack parse(String text) {
		if (text == null) {
			return null;
		}
		String t = text.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
		for (PositionTrack track : values()) {
			if (track.name().equals(t)) {
				return track;
			}
		}
		return null;
	}
}
