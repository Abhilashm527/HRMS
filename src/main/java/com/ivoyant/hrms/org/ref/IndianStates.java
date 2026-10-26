package com.ivoyant.hrms.org.ref;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** The states a location can be in (country IN), with their GST code and whether professional tax and LWF apply. */
public final class IndianStates {

	public record State(String name, String code, String gst, boolean pt, boolean lwf) {
	}

	public static final List<State> ALL = List.of(
			new State("Karnataka", "KA", "29", true, true),
			new State("Telangana", "TS", "36", true, true),
			new State("Maharashtra", "MH", "27", true, true),
			new State("Tamil Nadu", "TN", "33", true, true),
			new State("Kerala", "KL", "32", true, true),
			new State("Andhra Pradesh", "AP", "37", true, true),
			new State("Gujarat", "GJ", "24", true, true),
			new State("West Bengal", "WB", "19", true, true),
			new State("Delhi", "DL", "07", false, true),
			new State("Haryana", "HR", "06", false, true),
			new State("Uttar Pradesh", "UP", "09", false, false),
			new State("Rajasthan", "RJ", "08", false, false));

	/** States where the labour welfare fund is on by default. */
	public static final Set<String> LWF_STATES = ALL.stream().filter(State::lwf).map(State::name)
			.collect(Collectors.toUnmodifiableSet());

	private IndianStates() {
	}

	/** Is the labour welfare fund on by default in this state? (null-safe: the set itself rejects null) */
	public static boolean lwfDefault(String state) {
		return state != null && LWF_STATES.contains(state);
	}

	public static Optional<State> byName(String name) {
		return name == null ? Optional.empty() : ALL.stream().filter(s -> s.name().equalsIgnoreCase(name.trim())).findFirst();
	}
}
