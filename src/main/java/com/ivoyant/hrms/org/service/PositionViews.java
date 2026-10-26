package com.ivoyant.hrms.org.service;

import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.security.ModuleAccess;
import com.ivoyant.hrms.org.OrgSettings;
import com.ivoyant.hrms.org.domain.Designation;
import com.ivoyant.hrms.org.domain.PositionTrack;
import com.ivoyant.hrms.org.dto.PositionNode;
import com.ivoyant.hrms.org.repo.DesignationRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Builds {@link PositionNode}s for the whole ladder from a handful of queries (no query per position). */
@Component
class PositionViews {

	private static final BigDecimal MONTHS = BigDecimal.valueOf(12);

	/** A job level (Levels & grades) as positions link to it. */
	record Level(UUID id, String code, String name, BigDecimal minExp, BigDecimal maxExp, boolean approves,
			boolean active) {
	}

	/** Everything needed to describe the positions of the organization. */
	record Snapshot(PosTree tree, Map<UUID, Integer> headcount, Map<UUID, Integer> titleCount, Map<UUID, Level> levels,
			String currency, boolean canSeeBand, UUID topId, int maxDepth) {
	}

	private final DesignationRepository repo;
	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final OrgSettings settings;
	private final ModuleAccess access;

	PositionViews(DesignationRepository repo, JdbcClient jdbc, CurrentUser currentUser, OrgSettings settings,
			ModuleAccess access) {
		this.repo = repo;
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.settings = settings;
		this.access = access;
	}

	Snapshot snapshot(boolean withStats) {
		UUID org = currentUser.orgId();
		PosTree tree = new PosTree(repo.findByOrgId(org));
		Map<UUID, Integer> headcount = new HashMap<>();
		Map<UUID, Integer> titles = new HashMap<>();
		if (withStats) {
			counts("select designation_id, count(*) from employee where org_id = :org and status <> 'EXITED' "
					+ "and designation_id is not null group by designation_id", org, headcount);
			counts("select designation_id, count(*) from job_title where org_id = :org and designation_id is not null "
					+ "group by designation_id", org, titles);
		}
		Map<UUID, Level> levels = new HashMap<>();
		jdbc.sql("select id, code, name, min_exp, max_exp, approves, active from job_level where org_id = :org")
				.param("org", org).query((rs, i) -> {
					levels.put(rs.getObject(1, UUID.class), new Level(rs.getObject(1, UUID.class), rs.getString(2),
							rs.getString(3), rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBoolean(6), rs.getBoolean(7)));
					return 0;
				}).list();
		String currency = jdbc.sql("select currency from org where id = :org").param("org", org).query(String.class)
				.optional().orElse("INR");
		List<Designation> tops = tree.activeTops();
		return new Snapshot(tree, headcount, titles, levels, currency, canSeeBand(),
				tops.isEmpty() ? null : tops.get(0).getId(), settings.maxReportingDepth());
	}

	private void counts(String sql, UUID org, Map<UUID, Integer> into) {
		jdbc.sql(sql).param("org", org).query((rs, i) -> {
			into.put(rs.getObject(1, UUID.class), rs.getInt(2));
			return 0;
		}).list();
	}

	/** Who may see the numbers of a band: the Salary visibility setting, on top of being allowed to read positions. */
	boolean canSeeBand() {
		return switch (settings.salaryVisibility()) {
			case EVERYONE, MANAGERS_HR -> true;
			case HR_FINANCE -> access.has("org", "EDIT") || access.has("payroll", "VIEW");
			case ADMINS_ONLY -> access.has("org", "ADMIN");
		};
	}

	/** One position; {@code stats} adds headcount, title count and direct reports. */
	PositionNode node(Snapshot s, Designation d, boolean stats) {
		Designation parent = d.getReportsToId() == null ? null : s.tree().get(d.getReportsToId());
		PositionTrack track = PositionTrack.parse(d.getTrack());
		boolean hasBand = d.getBandMin() != null && d.getBandMax() != null;
		boolean show = s.canSeeBand();
		Level level = d.getJobLevelId() == null ? null : s.levels().get(d.getJobLevelId());
		return new PositionNode(d.getId(), d.getTitle(), d.getCode(), d.getLevel(),
				track == null ? d.getTrack() : track.name(), track == null ? d.getTrack() : track.label(),
				show ? d.getBandMin() : null, show ? d.getBandMax() : null, s.currency(),
				show && hasBand ? BandFormat.range(d.getBandMin(), d.getBandMax(), s.currency()) : null, hasBand,
				d.getId().equals(s.topId()),
				level == null ? null : new PositionNode.Ref(level.id(), level.name(), level.code()),
				parent == null ? null : new PositionNode.Ref(parent.getId(), parent.getTitle(), parent.getCode()),
				d.getReportsToId(), d.getMinExp(), d.isApproves(), d.isActive(), d.getDescription(),
				stats ? s.headcount().getOrDefault(d.getId(), 0) : null,
				stats ? s.titleCount().getOrDefault(d.getId(), 0) : null,
				stats ? s.tree().childrenOf(d.getId()).size() : null, s.tree().steps(d.getId()), null, d.getVersion(), null,
				warnings(s, d));
	}

	/** The soft problems with one position: they never block a save. */
	List<String> warnings(Snapshot s, Designation d) {
		List<String> out = new ArrayList<>();
		Designation parent = d.getReportsToId() == null ? null : s.tree().get(d.getReportsToId());
		if (parent != null && d.getLevel() != null && d.getLevel().equals(parent.getLevel())) {
			out.add("Same level as the position it reports to");
		}
		if (parent != null && d.isActive() && !parent.isActive()) {
			out.add("Reports to an inactive position");
		}
		if (d.getBandMax() != null && d.getLevel() != null) {
			s.tree().all().stream().filter(o -> !o.getId().equals(d.getId()) && o.getLevel() != null
					&& o.getLevel() > d.getLevel() && o.getBandMax() != null && o.getBandMax().compareTo(d.getBandMax()) < 0)
					.findFirst().ifPresent(o -> out.add("Band is out of level order: " + o.getTitle()
							+ " is more senior but its band tops out lower"));
		}
		Level level = d.getJobLevelId() == null ? null : s.levels().get(d.getJobLevelId());
		if (level != null) {
			// a job level keeps its experience range in months, a position's minimum is in years
			BigDecimal lo = level.minExp() == null ? null : level.minExp().divide(MONTHS, 1, RoundingMode.HALF_UP);
			BigDecimal hi = level.maxExp() == null ? null : level.maxExp().divide(MONTHS, 1, RoundingMode.HALF_UP);
			if (d.getMinExp() != null && (lo != null && d.getMinExp().compareTo(lo) < 0
					|| hi != null && d.getMinExp().compareTo(hi) > 0)) {
				out.add("Min experience is outside " + level.code() + " (" + range(lo, hi) + " years)");
			}
			if (level.approves() && !d.isApproves()) {
				out.add("Level " + level.code() + " approves but this position doesn't");
			}
		}
		return out;
	}

	private static String range(BigDecimal lo, BigDecimal hi) {
		return (lo == null ? "0" : lo.stripTrailingZeros().toPlainString()) + "–"
				+ (hi == null ? "∞" : hi.stripTrailingZeros().toPlainString());
	}

	/** The ladder below the positions that pass {@code keep} (and their ancestors, so the path stays visible). */
	List<PositionNode> ladder(Snapshot s, Predicate<Designation> keep, boolean stats) {
		Set<UUID> shown = new HashSet<>();
		for (Designation d : s.tree().all()) {
			if (keep.test(d)) {
				shown.add(d.getId());
				s.tree().ancestors(d.getId()).forEach(a -> shown.add(a.getId()));
			}
		}
		return s.tree().roots().stream().filter(r -> shown.contains(r.getId())).map(r -> subtree(s, r, shown, stats, 0))
				.toList();
	}

	private PositionNode subtree(Snapshot s, Designation d, Set<UUID> shown, boolean stats, int depth) {
		List<PositionNode> kids = depth > 200 ? List.of() : s.tree().childrenOf(d.getId()).stream()
				.filter(c -> shown.contains(c.getId())).map(c -> subtree(s, c, shown, stats, depth + 1)).toList();
		return node(s, d, stats).withChildren(kids);
	}
}
