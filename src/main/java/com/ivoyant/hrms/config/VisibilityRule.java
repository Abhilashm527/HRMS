package com.ivoyant.hrms.config;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Show a field only when another field of the same area satisfies a condition:
 * {@code {"fieldKey":"open_to_relocate","op":"FILLED"|"EMPTY"|"EQUALS","value":"Yes"}}.
 */
public record VisibilityRule(String fieldKey, Op op, String value) {

	public enum Op { FILLED, EMPTY, EQUALS }

	public static VisibilityRule fromJson(Object json) {
		if (!(json instanceof Map<?, ?> m) || m.get("fieldKey") == null || m.get("op") == null) {
			return null;
		}
		Object v = m.get("value");
		return new VisibilityRule(m.get("fieldKey").toString(), Op.valueOf(m.get("op").toString().toUpperCase()),
				v == null ? null : v.toString());
	}

	public Map<String, Object> toJson() {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("fieldKey", fieldKey);
		out.put("op", op.name());
		if (value != null) {
			out.put("value", value);
		}
		return out;
	}

	/** Is the field this rule guards visible, given the current value of every field? */
	public boolean visible(Function<String, Object> valueOf) {
		Object v = valueOf.apply(fieldKey);
		return switch (op) {
			case FILLED -> !blank(v);
			case EMPTY -> blank(v);
			case EQUALS -> !blank(v) && String.valueOf(v).equalsIgnoreCase(value == null ? "" : value);
		};
	}

	/** Blank means null, an empty string or list, or {@code false}. */
	public static boolean blank(Object v) {
		return v == null
				|| (v instanceof CharSequence s && s.toString().isBlank())
				|| (v instanceof Boolean b && !b)
				|| (v instanceof Collection<?> c && c.isEmpty());
	}
}
