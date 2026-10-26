package com.ivoyant.hrms.org.ref;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Time zones offered in the settings screens; any valid IANA id is accepted when saving. */
public final class TimeZones {

	public record Zone(String id, String label) {
	}

	private static final List<String> OFFERED = List.of("Asia/Kolkata", "Asia/Dubai", "Asia/Singapore", "Asia/Tokyo",
			"Asia/Hong_Kong", "Australia/Sydney", "Europe/London", "Europe/Paris", "Europe/Berlin", "America/New_York",
			"America/Chicago", "America/Los_Angeles", "UTC");

	private TimeZones() {
	}

	public static List<Zone> offered() {
		return OFFERED.stream().map(id -> new Zone(id, label(id))).toList();
	}

	/** {@code Asia/Kolkata} gives {@code Asia/Kolkata (IST)}. */
	public static String label(String id) {
		return id + " (" + TimeZone.getTimeZone(id).getDisplayName(false, TimeZone.SHORT, Locale.ENGLISH) + ")";
	}

	/** Accepts an IANA id, or the UI label such as {@code Asia/Kolkata (IST)}; null when it is not a time zone. */
	public static ZoneId parse(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String id = value.trim();
		int paren = id.indexOf(" (");
		if (paren > 0) {
			id = id.substring(0, paren);
		}
		try {
			return ZoneId.of(id);
		}
		catch (DateTimeException ex) {
			return null;
		}
	}
}
