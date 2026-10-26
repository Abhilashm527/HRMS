package com.ivoyant.hrms.employee.service;

import com.ivoyant.hrms.employee.domain.Employee;
import com.ivoyant.hrms.employee.domain.EmployeeStatus;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/** Maps the keys of the system fields of {@code employee_profile} to {@link Employee} columns. */
final class EmployeeFields {

	static final String AREA = "employee_profile";

	/** Keys that live in a real column; every other key lives in {@code employee.custom}. */
	static final Set<String> COLUMN_KEYS = Set.of("first_name", "last_name", "work_email", "personal_email", "phone",
			"joining_date", "exit_date", "department", "designation", "title", "manager", "location", "employment_type",
			"status");

	private EmployeeFields() {
	}

	static boolean isColumn(String key) {
		return COLUMN_KEYS.contains(key);
	}

	static Object read(Employee e, String key) {
		return switch (key) {
			case "first_name" -> e.getFirstName();
			case "last_name" -> e.getLastName();
			case "work_email" -> e.getWorkEmail();
			case "personal_email" -> e.getPersonalEmail();
			case "phone" -> e.getPhone();
			case "joining_date" -> e.getJoiningDate();
			case "exit_date" -> e.getExitDate();
			case "department" -> e.getDepartmentId();
			case "designation" -> e.getDesignationId();
			case "title" -> e.getTitleId();
			case "manager" -> e.getManagerId();
			case "location" -> e.getLocationId();
			case "employment_type" -> e.getEmploymentType();
			case "status" -> e.getStatus();
			default -> e.getCustom().get(key);
		};
	}

	/** {@code value} is already validated and normalised by {@link FieldValues}. */
	static void apply(Employee e, String key, Object value) {
		switch (key) {
			case "first_name" -> e.setFirstName((String) value);
			case "last_name" -> e.setLastName((String) value);
			case "work_email" -> e.setWorkEmail((String) value);
			case "personal_email" -> e.setPersonalEmail((String) value);
			case "phone" -> e.setPhone((String) value);
			case "joining_date" -> e.setJoiningDate(date(value));
			case "exit_date" -> e.setExitDate(date(value));
			case "department" -> e.setDepartmentId(uuid(value));
			case "designation" -> e.setDesignationId(uuid(value));
			case "title" -> e.setTitleId(uuid(value));
			case "manager" -> e.setManagerId(uuid(value));
			case "location" -> e.setLocationId(uuid(value));
			case "employment_type" -> e.setEmploymentType((String) value);
			case "status" -> e.setStatus(EmployeeStatus.valueOf((String) value));
			default -> {
				if (value == null) {
					e.getCustom().remove(key);
				}
				else {
					e.getCustom().put(key, value);
				}
			}
		}
	}

	private static LocalDate date(Object value) {
		return value == null ? null : LocalDate.parse((String) value);
	}

	private static UUID uuid(Object value) {
		return value == null ? null : UUID.fromString((String) value);
	}
}
