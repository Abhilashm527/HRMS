package com.ivoyant.hrms.config;

/** How a role may see a field, from least to most permissive. */
public enum FieldAccess {
	HIDDEN, MASKED, VIEW, HR_APPROVES, EDIT;

	public boolean atLeast(FieldAccess other) {
		return compareTo(other) >= 0;
	}

	public static FieldAccess max(FieldAccess a, FieldAccess b) {
		return a.compareTo(b) >= 0 ? a : b;
	}
}
