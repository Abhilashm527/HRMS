package com.ivoyant.hrms.org.dto;

import java.util.List;
import java.util.UUID;

/** Whole-ladder health for the banners. Overlaps are informational; the rest are errors. */
public record LadderHealth(List<List<String>> cycles, List<Item> multipleTops, List<Inversion> levelInversions,
		List<Item> withoutBand, List<Overlap> bandOverlaps, List<Item> overDepth, List<Item> inactiveReportsTo) {

	public record Item(UUID id, String name) {
	}

	public record Inversion(UUID id, String name, Integer level, String reportsTo, Integer reportsToLevel) {
	}

	public record Overlap(String a, String b, String overlap) {
	}
}
