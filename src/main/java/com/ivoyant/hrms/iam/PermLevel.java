package com.ivoyant.hrms.iam;

/**
 * What a role may do in a module, from nothing to everything. Stored as {@code role_permission} rows whose action is
 * {@code view}, {@code edit}, {@code approve} or {@code admin}; the highest one present wins.
 */
public enum PermLevel {
	NONE, VIEW, EDIT, APPROVE, ADMIN;

	public boolean atLeast(PermLevel other) {
		return compareTo(other) >= 0;
	}

	public static PermLevel ofAction(String action) {
		if (action == null) {
			return NONE;
		}
		return switch (action.toLowerCase()) {
			case "view" -> VIEW;
			case "edit" -> EDIT;
			case "approve" -> APPROVE;
			case "admin" -> ADMIN;
			default -> NONE;
		};
	}

	public static PermLevel max(PermLevel a, PermLevel b) {
		return a.compareTo(b) >= 0 ? a : b;
	}
}
