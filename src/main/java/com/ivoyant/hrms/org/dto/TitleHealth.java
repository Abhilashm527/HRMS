package com.ivoyant.hrms.org.dto;

import java.util.List;
import java.util.UUID;

/** Whole-list health: titles with no position or an inactive one, holders outside the title's department, people with no title. */
public record TitleHealth(List<Item> withoutPosition, List<Item> inactivePosition, List<Mismatch> departmentMismatches,
		long employeesWithoutTitle) {

	public record Item(UUID id, String name) {
	}

	public record Mismatch(UUID employeeId, String employee, UUID titleId, String title, String department) {
	}
}
