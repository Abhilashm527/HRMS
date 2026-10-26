package com.ivoyant.hrms.org.dto;

/** Counts for the header and checklist step 5; {@code text} is exactly what the checklist shows. */
public record TitleSummary(long total, long active, long withoutPosition, long inactivePosition,
		long employeesWithoutTitle, long deptMismatches, String text, boolean done) {
}
