package com.ivoyant.hrms.config;

public enum FieldType {
	TEXT, TEXTAREA, NUMBER, DATE, SELECT, MULTISELECT, BOOLEAN, EMAIL, PHONE, FILE, EMPLOYEE, CURRENCY,
	/** Layout only, carries no value. */
	HEADING,
	DEPARTMENT, LOCATION, EMPLOYMENT_TYPE;

	public boolean hasValue() {
		return this != HEADING;
	}
}
