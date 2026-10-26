package com.ivoyant.hrms.org.domain;

import java.util.Locale;
import java.util.Set;

/** Where a project is in its life; the labels are what the UI shows and accepts. */
public enum ProjectStatus {
	PLANNED("Planned"), ACTIVE("Active"), ON_HOLD("On hold"), COMPLETED("Completed");

	private final String label;

	ProjectStatus(String label) {
		this.label = label;
	}

	public String label() {
		return label;
	}

	/** Accepts "ON_HOLD", "On hold" and "on-hold"; null if unknown. */
	public static ProjectStatus parse(String text) {
		if (text == null) {
			return null;
		}
		String t = text.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
		for (ProjectStatus s : values()) {
			if (s.name().equals(t)) {
				return s;
			}
		}
		return null;
	}

	/** Planned and active work, and work on hold, all keep their people's allocation. */
	public boolean counts() {
		return this != COMPLETED;
	}

	/** Planned → Active or Completed (cancelled); Active → On hold or Completed; On hold → Active or Completed; Completed → Active (reopen). */
	public Set<ProjectStatus> next() {
		return switch (this) {
			case PLANNED -> Set.of(ACTIVE, COMPLETED);
			case ACTIVE -> Set.of(ON_HOLD, COMPLETED);
			case ON_HOLD -> Set.of(ACTIVE, COMPLETED);
			case COMPLETED -> Set.of(ACTIVE);
		};
	}
}
