package com.ivoyant.hrms.org.service;

import com.ivoyant.hrms.org.domain.Designation;
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
 * The position ladder (reports-to) in memory. Every walk stops if it meets a loop, so a damaged ladder is reported
 * instead of hanging. "Steps" count from the top: the top position is 0 steps from itself.
 */
final class PosTree {

	/** Most senior first, then by name: the ladder order of the UI. */
	static final Comparator<Designation> ORDER = Comparator
			.comparingInt((Designation d) -> d.getLevel() == null ? 0 : d.getLevel()).reversed()
			.thenComparing(Designation::getTitle, String.CASE_INSENSITIVE_ORDER);

	private final Map<UUID, Designation> byId = new HashMap<>();
	private final Map<UUID, List<Designation>> children = new HashMap<>();

	PosTree(List<Designation> all) {
		all.forEach(d -> byId.put(d.getId(), d));
		for (Designation d : all) {
			if (d.getReportsToId() != null && byId.containsKey(d.getReportsToId())) {
				children.computeIfAbsent(d.getReportsToId(), k -> new ArrayList<>()).add(d);
			}
		}
		children.values().forEach(l -> l.sort(ORDER));
	}

	Designation get(UUID id) {
		return byId.get(id);
	}

	List<Designation> all() {
		return byId.values().stream().sorted(ORDER).toList();
	}

	/** Positions with no reports-to, or whose reports-to is missing. */
	List<Designation> roots() {
		return byId.values().stream().filter(d -> d.getReportsToId() == null || !byId.containsKey(d.getReportsToId()))
				.sorted(ORDER).toList();
	}

	/** Active positions with no reports-to: there should be exactly one. */
	List<Designation> activeTops() {
		return byId.values().stream().filter(d -> d.isActive() && d.getReportsToId() == null).sorted(ORDER).toList();
	}

	List<Designation> childrenOf(UUID id) {
		return children.getOrDefault(id, List.of());
	}

	/** Positions above it, nearest first. */
	List<Designation> ancestors(UUID id) {
		List<Designation> out = new ArrayList<>();
		Set<UUID> seen = new HashSet<>();
		seen.add(id);
		Designation cursor = byId.get(id);
		while (cursor != null && cursor.getReportsToId() != null && byId.containsKey(cursor.getReportsToId())
				&& seen.add(cursor.getReportsToId())) {
			cursor = byId.get(cursor.getReportsToId());
			out.add(cursor);
		}
		return out;
	}

	/** Steps up to the top of the ladder (0 for a top position). */
	int steps(UUID id) {
		return ancestors(id).size();
	}

	/** The position and everything below it. */
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

	/** Steps from the position down to its furthest descendant (0 for a leaf). */
	int height(UUID id) {
		int max = 0;
		for (UUID d : descendants(id)) {
			max = Math.max(max, steps(d) - steps(id));
		}
		return max;
	}

	int deepest() {
		return byId.keySet().stream().mapToInt(this::steps).max().orElse(0);
	}

	/** Each loop in the reports-to chain, as the names along it (the first name repeated at the end). */
	List<List<String>> cycles() {
		List<List<String>> out = new ArrayList<>();
		Set<UUID> reported = new HashSet<>();
		for (Designation start : byId.values()) {
			List<UUID> chain = new ArrayList<>();
			UUID cursor = start.getId();
			while (cursor != null && byId.containsKey(cursor) && !chain.contains(cursor)) {
				chain.add(cursor);
				cursor = byId.get(cursor).getReportsToId();
			}
			if (cursor != null && chain.contains(cursor) && !reported.contains(cursor)) {
				List<UUID> loop = chain.subList(chain.indexOf(cursor), chain.size());
				reported.addAll(loop);
				List<String> names = new ArrayList<>();
				loop.forEach(u -> names.add(byId.get(u).getTitle()));
				names.add(byId.get(loop.get(0)).getTitle());
				out.add(names);
			}
		}
		return out;
	}

	/** Does the reports-to chain of this position come back to itself? */
	boolean inCycle(UUID id) {
		Set<UUID> seen = new HashSet<>();
		UUID cursor = byId.containsKey(id) ? byId.get(id).getReportsToId() : null;
		while (cursor != null && byId.containsKey(cursor)) {
			if (cursor.equals(id)) {
				return true;
			}
			if (!seen.add(cursor)) {
				return false;
			}
			cursor = byId.get(cursor).getReportsToId();
		}
		return false;
	}

	/** Would making {@code parentId} the reports-to of {@code id} create a loop? */
	boolean wouldCycle(UUID id, UUID parentId) {
		return parentId != null && (id.equals(parentId) || descendants(id).contains(parentId));
	}
}
