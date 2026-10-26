package com.ivoyant.hrms.org.dto;

public record DepartmentSummary(long total, long active, long withoutHead, int deepest, int maxDepth, String text,
		String depthText) {
}
