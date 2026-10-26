package com.ivoyant.hrms.org.service;

import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.org.OrgSettings;
import com.ivoyant.hrms.org.domain.Department;
import com.ivoyant.hrms.org.domain.Location;
import com.ivoyant.hrms.org.dto.DepartmentNode;
import com.ivoyant.hrms.org.repo.DepartmentRepository;
import com.ivoyant.hrms.org.repo.LocationRepository;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Builds {@link DepartmentNode}s for the whole tree from a handful of queries (no query per department). */
@Component
class DepartmentViews {

	/** Everything needed to describe the departments of the organization. */
	record Snapshot(DeptTree tree, Map<UUID, Integer> headcount, Map<UUID, String> heads, Map<UUID, String> locations,
			int maxDepth, boolean nested) {
	}

	private final DepartmentRepository repo;
	private final LocationRepository locationRepo;
	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final OrgSettings settings;

	DepartmentViews(DepartmentRepository repo, LocationRepository locationRepo, JdbcClient jdbc, CurrentUser currentUser,
			OrgSettings settings) {
		this.repo = repo;
		this.locationRepo = locationRepo;
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.settings = settings;
	}

	Snapshot snapshot(boolean withStats) {
		UUID org = currentUser.orgId();
		List<Department> all = repo.findByOrgId(org);
		Map<UUID, Integer> headcount = new HashMap<>();
		if (withStats) {
			jdbc.sql("""
					select department_id, count(*) from employee
					where org_id = :org and status <> 'EXITED' and department_id is not null group by department_id
					""").param("org", org).query((rs, i) -> {
						headcount.put(rs.getObject(1, UUID.class), rs.getInt(2));
						return 0;
					}).list();
		}
		Set<UUID> headIds = all.stream().map(Department::getHeadEmployeeId).filter(Objects::nonNull).collect(Collectors.toSet());
		Map<UUID, String> heads = new HashMap<>();
		if (!headIds.isEmpty()) {
			jdbc.sql("select id, display_name from employee where id in (:ids)").param("ids", headIds).query((rs, i) -> {
				heads.put(rs.getObject(1, UUID.class), rs.getString(2));
				return 0;
			}).list();
		}
		Set<UUID> locIds = all.stream().map(Department::getDefaultLocationId).filter(Objects::nonNull).collect(Collectors.toSet());
		Map<UUID, String> locations = locIds.isEmpty() ? Map.of() : locationRepo.findByOrgIdAndIdIn(org, locIds).stream()
				.collect(Collectors.toMap(Location::getId, Location::getName));
		return new Snapshot(new DeptTree(all), headcount, heads, locations, settings.maxDepartmentDepth(),
				settings.nestedDepartments());
	}

	/** One department; {@code stats} adds headcount and child count, {@code path} the names from the root. */
	DepartmentNode node(Snapshot s, Department d, boolean stats, boolean path) {
		int level = s.tree().level(d.getId());
		Department parent = d.getParentId() == null ? null : s.tree().get(d.getParentId());
		String headName = d.getHeadEmployeeId() == null ? null : s.heads().get(d.getHeadEmployeeId());
		DepartmentNode.Head head = headName == null ? null
				: new DepartmentNode.Head(d.getHeadEmployeeId(), headName, initials(headName));
		String locName = d.getDefaultLocationId() == null ? null : s.locations().get(d.getDefaultLocationId());
		DepartmentNode.Ref loc = locName == null ? null : new DepartmentNode.Ref(d.getDefaultLocationId(), locName);
		Integer headcount = null;
		Integer total = null;
		Integer childCount = null;
		if (stats) {
			headcount = s.headcount().getOrDefault(d.getId(), 0);
			total = s.tree().descendants(d.getId()).stream().mapToInt(id -> s.headcount().getOrDefault(id, 0)).sum();
			childCount = s.tree().childrenOf(d.getId()).size();
		}
		return new DepartmentNode(d.getId(), d.getName(), d.getCode(), d.getParentId(), parent == null ? null : parent.getName(),
				path ? s.tree().path(d.getId()) : null, level, head, d.getCostCentre(), loc, d.isActive(), d.getDescription(),
				d.getSort(), headcount, total, childCount, level > s.maxDepth(), null, d.getVersion(), null, List.of());
	}

	/** The tree below the departments that pass {@code keep} (and their ancestors, so the path stays visible). */
	List<DepartmentNode> tree(Snapshot s, Predicate<Department> keep, boolean stats) {
		Set<UUID> shown = new java.util.HashSet<>();
		for (Department d : s.tree().roots().stream().flatMap(r -> s.tree().descendants(r.getId()).stream())
				.map(id -> s.tree().get(id)).toList()) {
			if (keep.test(d)) {
				shown.add(d.getId());
				s.tree().ancestors(d.getId()).forEach(a -> shown.add(a.getId()));
			}
		}
		return s.tree().roots().stream().filter(r -> shown.contains(r.getId())).map(r -> subtree(s, r, shown, stats)).toList();
	}

	private DepartmentNode subtree(Snapshot s, Department d, Set<UUID> shown, boolean stats) {
		List<DepartmentNode> kids = s.tree().childrenOf(d.getId()).stream().filter(c -> shown.contains(c.getId()))
				.map(c -> subtree(s, c, shown, stats)).toList();
		return node(s, d, stats, false).withChildren(kids);
	}

	/** Departments in tree order (each parent followed by its children), for flat lists and pickers. */
	List<Department> flatOrder(Snapshot s) {
		List<Department> out = new ArrayList<>();
		s.tree().roots().forEach(r -> walk(s, r, out, 0));
		return out;
	}

	private void walk(Snapshot s, Department d, List<Department> out, int depth) {
		if (depth > 200) {
			return;
		}
		out.add(d);
		s.tree().childrenOf(d.getId()).forEach(c -> walk(s, c, out, depth + 1));
	}

	static String initials(String name) {
		return Arrays.stream(name.trim().split("\\s+")).filter(p -> !p.isEmpty()).limit(2)
				.map(p -> p.substring(0, 1).toUpperCase(Locale.ROOT)).collect(Collectors.joining());
	}
}
