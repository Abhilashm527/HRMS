package com.ivoyant.hrms.config.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds asset and licence ids from a format such as {@code CMP-{TYPE}-####}: the {@code #} run is the zero-padded counter. */
public final class IdFormatter {

	private static final Pattern RUN = Pattern.compile("#+");

	private IdFormatter() {
	}

	public static boolean valid(String fmt, String token) {
		return fmt != null && fmt.contains("{" + token + "}") && fmt.contains("#");
	}

	/** {@code fmt} with the token replaced by {@code type} and the first {@code #} run replaced by {@code n}, zero padded. */
	public static String format(String fmt, String token, String type, long n) {
		Matcher m = RUN.matcher(fmt.replace("{" + token + "}", type));
		if (!m.find()) {
			return fmt.replace("{" + token + "}", type);
		}
		String number = String.format("%0" + m.group().length() + "d", n);
		return m.replaceFirst(Matcher.quoteReplacement(number));
	}

	public static String preview(String fmt, String type) {
		String token = fmt != null && fmt.contains("{APP}") ? "APP" : "TYPE";
		return format(fmt, token, type == null || type.isBlank() ? "XXX" : type, 1);
	}
}
