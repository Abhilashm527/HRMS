package com.ivoyant.hrms.org.dto;

import java.util.List;
import java.util.UUID;

public record LocationMeta(List<TypeOption> types, List<Option> methods, List<Option> weekPatterns, List<StateOption> states,
		List<ZoneOption> timezones, List<CalendarOption> holidayCalendars) {

	public record TypeOption(String value, String label, String fg, String bg) {
	}

	public record Option(String value, String label) {
	}

	public record StateOption(String name, String code, String gst, boolean pt, boolean lwf) {
	}

	public record ZoneOption(String id, String label) {
	}

	public record CalendarOption(UUID id, String name, String state, String label) {
	}
}
