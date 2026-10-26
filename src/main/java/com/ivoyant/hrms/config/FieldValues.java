package com.ivoyant.hrms.config;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Validates a raw JSON value against a field definition and returns it in normalised form. */
public final class FieldValues {

	/** A value that does not fit its field; {@code code} is REQUIRED or INVALID. */
	public static final class Invalid extends RuntimeException {

		private final String code;

		Invalid(String code, String message) {
			super(message);
			this.code = code;
		}

		public String code() {
			return code;
		}
	}

	private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
	private static final Pattern PHONE = Pattern.compile("^[+0-9 ()-]{6,20}$");

	private FieldValues() {
	}

	public static Object normalize(FieldDefinition def, Object raw) {
		if (!def.type().hasValue()) {
			throw new Invalid("INVALID", def.label() + " is a heading and has no value");
		}
		if (isBlank(raw)) {
			if (def.required()) {
				throw new Invalid("REQUIRED", def.label() + " is required");
			}
			return null;
		}
		return switch (def.type()) {
			case TEXT, TEXTAREA -> validated(def, string(def, raw));
			case PHONE -> phone(def, raw);
			case EMAIL -> email(def, raw);
			case NUMBER, CURRENCY -> number(def, raw);
			case DATE -> date(def, raw);
			case BOOLEAN -> bool(def, raw);
			case SELECT -> select(def, raw);
			case MULTISELECT -> multiselect(def, raw);
			case FILE -> file(def, raw);
			case EMPLOYEE, DEPARTMENT, LOCATION -> uuid(def, raw);
			case EMPLOYMENT_TYPE -> string(def, raw);
			case HEADING -> throw new Invalid("INVALID", def.label() + " is a heading and has no value");
		};
	}

	public static boolean isBlank(Object raw) {
		return raw == null || (raw instanceof CharSequence s && s.toString().isBlank())
				|| (raw instanceof Collection<?> c && c.isEmpty());
	}

	/** Dropdown options from a JSON list or the UI's comma string: trimmed, empties dropped, de-duplicated. */
	public static List<String> parseOptions(Object raw) {
		if (raw == null) {
			return List.of();
		}
		Collection<?> items = raw instanceof Collection<?> c ? c : Arrays.asList(raw.toString().split(","));
		LinkedHashSet<String> out = new LinkedHashSet<>();
		for (Object item : items) {
			if (item == null) {
				continue;
			}
			String s = item.toString().trim();
			if (!s.isEmpty()) {
				out.add(s);
			}
		}
		return new ArrayList<>(out);
	}

	private static final Pattern PAN = Pattern.compile("^[A-Z]{5}[0-9]{4}[A-Z]$");
	private static final Pattern IFSC = Pattern.compile("^[A-Z]{4}0[A-Z0-9]{6}$");
	private static final Pattern AADHAAR = Pattern.compile("^\\d{12}$");
	private static final Pattern ACCOUNT = Pattern.compile("^\\d{9,18}$");

	/** A text field marked PAN, IFSC, Aadhaar or Account number must look like one. */
	private static String validated(FieldDefinition def, String value) {
		if (def.validator() == null) {
			return value;
		}
		String v = value.replace(" ", "");
		boolean ok = switch (def.validator()) {
			case "PAN" -> PAN.matcher(v.toUpperCase()).matches();
			case "IFSC" -> IFSC.matcher(v.toUpperCase()).matches();
			case "AADHAAR" -> AADHAAR.matcher(v).matches() && verhoeff(v);
			case "ACCOUNT" -> ACCOUNT.matcher(v).matches();
			default -> true;
		};
		if (!ok) {
			throw bad(def, switch (def.validator()) {
				case "PAN" -> "must be a PAN like ABCDE1234F";
				case "IFSC" -> "must be an IFSC like HDFC0001234";
				case "AADHAAR" -> "must be a valid 12-digit Aadhaar number";
				default -> "must be 9–18 digits";
			});
		}
		return "PAN".equals(def.validator()) || "IFSC".equals(def.validator()) ? v.toUpperCase() : v;
	}

	private static final int[][] VD = {{0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, {1, 2, 3, 4, 0, 6, 7, 8, 9, 5}, {2, 3, 4, 0, 1, 7, 8, 9, 5, 6},
			{3, 4, 0, 1, 2, 8, 9, 5, 6, 7}, {4, 0, 1, 2, 3, 9, 5, 6, 7, 8}, {5, 9, 8, 7, 6, 0, 4, 3, 2, 1}, {6, 5, 9, 8, 7, 1, 0, 4, 3, 2},
			{7, 6, 5, 9, 8, 2, 1, 0, 4, 3}, {8, 7, 6, 5, 9, 3, 2, 1, 0, 4}, {9, 8, 7, 6, 5, 4, 3, 2, 1, 0}};
	private static final int[][] VP = {{0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, {1, 5, 7, 6, 2, 8, 3, 0, 9, 4}, {5, 8, 0, 3, 7, 9, 6, 1, 4, 2},
			{8, 9, 1, 6, 0, 4, 3, 5, 2, 7}, {9, 4, 5, 3, 1, 2, 6, 8, 7, 0}, {4, 2, 8, 6, 5, 7, 3, 9, 0, 1}, {2, 7, 9, 3, 8, 0, 6, 4, 1, 5},
			{7, 0, 4, 6, 9, 1, 3, 2, 5, 8}};

	/** The Verhoeff check digit every real Aadhaar number carries. */
	private static boolean verhoeff(String digits) {
		int c = 0;
		for (int i = 0; i < digits.length(); i++) {
			c = VD[c][VP[i % 8][digits.charAt(digits.length() - 1 - i) - '0']];
		}
		return c == 0;
	}

	private static String string(FieldDefinition def, Object raw) {
		if (!(raw instanceof CharSequence cs)) {
			throw bad(def, "must be text");
		}
		return cs.toString().trim();
	}

	private static String phone(FieldDefinition def, Object raw) {
		String s = string(def, raw);
		if (!PHONE.matcher(s).matches()) {
			throw bad(def, "must be a phone number (digits, +, spaces, brackets, dashes)");
		}
		return s;
	}

	private static String email(FieldDefinition def, Object raw) {
		String s = string(def, raw);
		if (!EMAIL.matcher(s).matches()) {
			throw bad(def, "must be a valid email address");
		}
		return s;
	}

	private static Number number(FieldDefinition def, Object raw) {
		if (raw instanceof Number n) {
			return n;
		}
		if (raw instanceof CharSequence cs) {
			try {
				return new BigDecimal(cs.toString().trim());
			}
			catch (NumberFormatException ex) {
				// fall through
			}
		}
		throw bad(def, "must be a number");
	}

	private static String date(FieldDefinition def, Object raw) {
		if (raw instanceof CharSequence cs) {
			try {
				return LocalDate.parse(cs.toString().trim()).toString();
			}
			catch (DateTimeParseException ex) {
				// fall through
			}
		}
		throw bad(def, "must be a date in yyyy-MM-dd format");
	}

	private static Boolean bool(FieldDefinition def, Object raw) {
		if (raw instanceof Boolean b) {
			return b;
		}
		throw bad(def, "must be true or false");
	}

	private static String select(FieldDefinition def, Object raw) {
		String s = string(def, raw);
		if (!def.options().isEmpty() && !def.options().contains(s)) {
			throw bad(def, "must be one of " + def.options());
		}
		return s;
	}

	private static List<String> multiselect(FieldDefinition def, Object raw) {
		if (!(raw instanceof Collection<?> values)) {
			throw bad(def, "must be a list");
		}
		List<String> out = new ArrayList<>();
		for (Object v : values) {
			String s = string(def, v);
			if (!def.options().isEmpty() && !def.options().contains(s)) {
				throw bad(def, "contains '" + s + "', which is not one of " + def.options());
			}
			out.add(s);
		}
		return out;
	}

	private static Map<String, Object> file(FieldDefinition def, Object raw) {
		if (raw instanceof Map<?, ?> m && m.get("fileId") != null && !m.get("fileId").toString().isBlank()) {
			Map<String, Object> out = new LinkedHashMap<>();
			out.put("fileId", m.get("fileId").toString());
			out.put("name", m.get("name") == null ? "" : m.get("name").toString());
			return out;
		}
		throw bad(def, "must be an uploaded file ({\"fileId\":\"…\",\"name\":\"…\"})");
	}

	private static String uuid(FieldDefinition def, Object raw) {
		String s = string(def, raw);
		try {
			return UUID.fromString(s).toString();
		}
		catch (IllegalArgumentException ex) {
			throw bad(def, "must be an id (UUID)");
		}
	}

	private static Invalid bad(FieldDefinition def, String message) {
		return new Invalid("INVALID", def.label() + " " + message);
	}
}
