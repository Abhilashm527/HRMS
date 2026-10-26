package com.ivoyant.hrms.org.service;

import com.ivoyant.hrms.org.domain.Department;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The department hierarchy in memory. Every walk stops if it meets a loop, so a damaged tree (a cycle that came in with
 * old or bulk data) is reported instead of hanging. Levels start at 1 for a root.
 */
final class DeptTree {

	private static final Comparator<Department> ORDER = Comparator.comparingInt(Department::getSort)
			.thenComparing(Department::getName, String.CASE_INSENSITIVE_ORDER);

	private final Map<UUID, Department> byId = new HashMap<>();
	private final Map<UUID, List<Department>> children = new HashMap<>();

	DeptTree(List<Department> all) {
		all.forEach(d -> byId.put(d.getId(), d));
		for (Department d : all) {
			if (d.getParentId() != null && byId.containsKey(d.getParentId())) {
				children.computeIfAbsent(d.getParentId(), k -> new ArrayList<>()).add(d);
			}
		}
		children.values().forEach(l -> l.sort(ORDER));
	}

	Department get(UUID id) {
		return byId.get(id);
	}

	/** Departments with no parent, or whose parent is missing. */
	List<Department> roots() {
		return byId.values().stream().filter(d -> d.getParentId() == null || !byId.containsKey(d.getParentId()))
				.sorted(ORDER).toList();
	}

	List<Department> childrenOf(UUID id) {
		return children.getOrDefault(id, List.of());
	}

	/** Parents above the department, nearest first. */
	List<Department> ancestors(UUID id) {
		List<Department> out = new ArrayList<>();
		Set<UUID> seen = new HashSet<>();
		seen.add(id);
		Department cursor = byId.get(id);
		while (cursor != null && cursor.getParentId() != null && byId.containsKey(cursor.getParentId())
				&& seen.add(cursor.getParentId())) {
			cursor = byId.get(cursor.getParentId());
			out.add(cursor);
		}
		return out;
	}

	int level(UUID id) {
		return ancestors(id).size() + 1;
	}

	/** Names from the root down to the department. */
	List<String> path(UUID id) {
		List<String> names = new ArrayList<>();
		ancestors(id).forEach(a -> names.add(0, a.getName()));
		Department self = byId.get(id);
		if (self != null) {
			names.add(self.getName());
		}
		return names;
	}

	/** The department and everything below it. */
	Set<UUID> descendants(UUID id) {
		Set<UUID> out = new LinkedHashSet<>();
		List<UUID> queue = new ArrayList<>(List.of(id));
		while (!queue.isEmpty()) {
			UUID next = queue.remove(0);
			if (out.add(next)) {
				childrenOf(next).forEach(c -> queue.add(c.getId()));
			}
		}
		return out;
	}

	/** Levels below the department (0 for a leaf). */
	int height(UUID id) {
		int max = 0;
		for (UUID d : descendants(id)) {
			max = Math.max(max, level(d) - level(id));
		}
		return max;
	}

	int deepest() {
		return byId.keySet().stream().mapToInt(this::level).max().orElse(0);
	}

	/** Each loop in the parent chain, as the names along it (the first name repeated at the end). */
	List<List<String>> cycles() {
		List<List<String>> out = new ArrayList<>();
		Set<UUID> reported = new HashSet<>();
		for (Department start : byId.values()) {
			List<UUID> chain = new ArrayList<>();
			UUID cursor = start.getId();
			while (cursor != null && byId.containsKey(cursor) && !chain.contains(cursor)) {
				chain.add(cursor);
				cursor = byId.get(cursor).getParentId();
			}
			if (cursor != null && chain.contains(cursor) && !reported.contains(cursor)) {
				List<UUID> loop = chain.subList(chain.indexOf(cursor), chain.size());
				reported.addAll(loop);
				List<String> names = new ArrayList<>();
				loop.forEach(u -> names.add(byId.get(u).getName()));
				names.add(byId.get(loop.get(0)).getName());
				out.add(names);
			}
		}
		return out;
	}

	/** Would making {@code parentId} the parent of {@code id} create a loop? */
	boolean wouldCycle(UUID id, UUID parentId) {
		return parentId != null && (id.equals(parentId) || descendants(id).contains(parentId));
	}
}
