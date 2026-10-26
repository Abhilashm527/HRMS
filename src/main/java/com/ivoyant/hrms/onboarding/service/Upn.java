package com.ivoyant.hrms.onboarding.service;

import java.util.function.Predicate;

/** Builds the sign-in name (the part before the @) from a name and the rule's format. */
public final class Upn {

	private Upn() {
	}

	private static String letters(String s) {
		return s == null ? "" : s.toLowerCase().replaceAll("[^a-z]", "");
	}

	public static String user(String first, String last, String format) {
		String f = letters(first);
		String l = letters(last);
		String user = switch (format == null ? "first.last" : format) {
			case "firstlast" -> f + l;
			case "f.last" -> (f.isEmpty() ? "" : f.substring(0, 1)) + (l.isEmpty() ? "" : "." + l);
			case "first" -> f;
			default -> l.isEmpty() ? f : f + "." + l;
		};
		return user.isEmpty() ? "user" : user;
	}

	/** The first free name: the plain one, then name2 … name8; null when all are taken. */
	public static String firstFree(String user, Predicate<String> free) {
		if (free.test(user)) {
			return user;
		}
		for (int i = 2; i <= 8; i++) {
			if (free.test(user + i)) {
				return user + i;
			}
		}
		return null;
	}
}
