package com.ivoyant.hrms.employee.service;

/** Masks a field value for roles whose access is MASKED: {@code ••••} plus the last four letters or digits. */
final class Masking {

	private Masking() {
	}

	static String mask(Object value) {
		if (value == null) {
			return null;
		}
		String alnum = String.valueOf(value).replaceAll("[^A-Za-z0-9]", "");
		return alnum.length() <= 4 ? "••••" : "••••" + alnum.substring(alnum.length() - 4);
	}
}
