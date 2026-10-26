package com.ivoyant.hrms.common.authz;

import java.util.Map;

/**
 * A relationship tuple, e.g. {@code user:42 manager employee:7}, optionally with a condition
 * (e.g. {@code active_window} with its static context).
 */
public record FgaTuple(String user, String relation, String object, String condition, Map<String, Object> context) {

	public FgaTuple(String user, String relation, String object) {
		this(user, relation, object, null, null);
	}

	public FgaTuple withCondition(String name, Map<String, Object> context) {
		return new FgaTuple(user, relation, object, name, context);
	}
}
