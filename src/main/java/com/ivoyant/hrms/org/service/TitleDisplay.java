package com.ivoyant.hrms.org.service;

import java.util.regex.Pattern;

/** Renders the organization's title display format ("{title} · {position}"); empty tokens take their separator with them. */
final class TitleDisplay {

	private static final String SEP = "[\\s·|/,\\-–]";
	private static final Pattern DOUBLE = Pattern.compile("(" + SEP + "*[·|/,\\-–]" + SEP + "*){2,}");
	private static final Pattern EDGES = Pattern.compile("^" + SEP + "+|" + SEP + "+$");
	private static final Pattern EMPTY_BRACKETS = Pattern.compile("\\(\\s*\\)|\\[\\s*]");

	private TitleDisplay() {
	}

	static String render(String format, String title, String position, String grade) {
		String text = format.replace("{title}", nz(title)).replace("{position}", nz(position)).replace("{grade}", nz(grade));
		text = EMPTY_BRACKETS.matcher(text).replaceAll("");
		text = DOUBLE.matcher(text).replaceAll(" · ");
		text = EDGES.matcher(text).replaceAll("");
		return text.replaceAll("\\s{2,}", " ").trim();
	}

	private static String nz(String s) {
		return s == null ? "" : s;
	}
}
