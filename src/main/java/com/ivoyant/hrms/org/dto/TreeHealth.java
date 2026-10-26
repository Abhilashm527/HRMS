package com.ivoyant.hrms.org.dto;

import java.util.List;
import java.util.UUID;

/** Whole-tree health for the hierarchy banners. */
public record TreeHealth(List<List<String>> cycles, List<Item> overDepth, List<Item> nestingOffButNested,
		List<Item> withoutHead, List<Item> inactiveParentOfActive) {

	public record Item(UUID id, String name, Integer level) {
	}
}
