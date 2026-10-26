package com.ivoyant.hrms.org.service;

import com.ivoyant.hrms.common.forms.RecordCustomFields;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.security.ModuleAccess;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.common.web.PageResponse;
import com.ivoyant.hrms.org.BandCheck;
import com.ivoyant.hrms.org.OrgSettings;
import com.ivoyant.hrms.org.domain.Designation;
import com.ivoyant.hrms.org.domain.PositionTrack;
import com.ivoyant.hrms.org.dto.LadderHealth;
import com.ivoyant.hrms.org.dto.PositionBasic;
import com.ivoyant.hrms.org.dto.PositionEmployee;
import com.ivoyant.hrms.org.dto.PositionNode;
import com.ivoyant.hrms.org.dto.PositionOption;
import com.ivoyant.hrms.org.dto.PositionSummary;
import com.ivoyant.hrms.org.dto.PositionUsage;
import com.ivoyant.hrms.org.dto.SuggestedManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Everything that reads positions: list, ladder, summary, pickers, people, usage, manager suggestions, band check. */
@Service
public class PositionQueryService {

	private final PositionViews views;
	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final ModuleAccess access;
	private final OrgSettings settings;
	private final RecordCustomFields customFields;

	public PositionQueryService(PositionViews views, JdbcClient jdbc, CurrentUser currentUser, ModuleAccess access,
			OrgSettings settings, RecordCustomFields customFields) {
		this.views = views;
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.access = access;
		this.settings = settings;
		this.customFields = customFields;
	}

	/**
	 * Members without access to organization settings get the short form. Admins get the flat list (most senior first)
	 * or, for {@code view=ladder}, the nested reports-to tree.
	 */
	@Transactional(readOnly = true)
	public Object list(Boolean active, String q, String track, String view, boolean stats, String sort) {
		PositionViews.Snapshot s = views.snapshot(stats);
		PositionTrack wanted = track == null || track.isBlank() ? null : PositionTrack.parse(track);
		Predicate<Designation> keep = d -> (active == null || d.isActive() == active)
				&& (wanted == null || wanted.name().equals(d.getTrack())) && matches(d, q);
		List<Designation> ordered = ordered(s, sort);
		if (!access.has("org", "VIEW")) {
			return ordered.stream().filter(keep)
					.map(d -> new PositionBasic(d.getId(), d.getTitle(), d.getCode(), d.getLevel(), d.isActive())).toList();
		}
		if ("ladder".equalsIgnoreCase(view)) {
			return views.ladder(s, keep, stats);
		}
		return ordered.stream().filter(keep).map(d -> views.node(s, d, stats)).toList();
	}

	@Transactional(readOnly = true)
	public PositionSummary summary() {
		PositionViews.Snapshot s = views.snapshot(true);
		List<Designation> all = s.tree().all();
		long active = all.stream().filter(Designation::isActive).count();
		long withoutBand = all.stream().filter(d -> d.isActive() && (d.getBandMax() == null || d.getBandMax().signum() <= 0)).count();
		long unused = all.stream().filter(d -> s.headcount().getOrDefault(d.getId(), 0) == 0
				&& s.titleCount().getOrDefault(d.getId(), 0) == 0).count();
		long without = jdbc.sql("select count(*) from employee where org_id = :org and status <> 'EXITED' and designation_id is null")
				.param("org", currentUser.orgId()).query(Long.class).single();
		Designation top = s.topId() == null ? null : s.tree().get(s.topId());
		String text = active + (active == 1 ? " position" : " positions") + (withoutBand > 0 ? " · " + withoutBand + " without a band" : "");
		String warn = without > 0 && settings.positionMandatory()
				? without + (without == 1 ? " person has" : " people have") + " no position" : null;
		return new PositionSummary(all.size(), active, withoutBand, unused,
				top == null ? null : new PositionSummary.Top(top.getId(), top.getTitle()), without, text, warn);
	}

	/** Active positions for pickers, most senior first. {@code departmentId} is accepted for the hire form but positions are org-wide. */
	@Transactional(readOnly = true)
	public List<PositionOption> options(UUID departmentId) {
		PositionViews.Snapshot s = views.snapshot(false);
		return s.tree().all().stream().filter(Designation::isActive)
				.map(d -> new PositionOption(d.getId(), d.getTitle(), d.getCode(), d.getLevel())).toList();
	}

	@Transactional(readOnly = true)
	public PositionNode get(UUID id) {
		PositionViews.Snapshot s = views.snapshot(true);
		Designation d = require(s, id);
		return views.node(s, d, true).withExtras(customFields.read("position", id), views.warnings(s, d));
	}

	/** The ladder from the top down to the position itself. */
	@Transactional(readOnly = true)
	public List<PositionBasic> ladder(UUID id) {
		PositionViews.Snapshot s = views.snapshot(false);
		require(s, id);
		List<PositionBasic> out = new ArrayList<>();
		s.tree().ancestors(id).forEach(a -> out.add(0, basic(a)));
		out.add(basic(s.tree().get(id)));
		return out;
	}

	@Transactional(readOnly = true)
	public PageResponse<PositionEmployee> employees(UUID id, String status, int page, int size) {
		require(views.snapshot(false), id);
		boolean all = status == null || status.isBlank();
		String where = "designation_id = :id" + (all ? "" : " and status = :status");
		String wanted = all ? "" : status.toUpperCase(Locale.ROOT);
		int pageSize = Math.min(Math.max(size, 1), 100);
		long total = jdbc.sql("select count(*) from employee where " + where).param("id", id).param("status", wanted)
				.query(Long.class).single();
		List<PositionEmployee> items = jdbc.sql("select id, employee_code, display_name, work_email, status, department_id "
				+ "from employee where " + where + " order by display_name limit :limit offset :offset")
				.param("id", id).param("status", wanted).param("limit", pageSize).param("offset", Math.max(page, 0) * pageSize)
				.query((rs, i) -> new PositionEmployee(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
						rs.getString(4), rs.getString(5), rs.getObject(6, UUID.class)))
				.list();
		return new PageResponse<>(items, Math.max(page, 0), pageSize, total);
	}

	/** What blocks deleting or deactivating a position, with the message the screen shows. */
	@Transactional(readOnly = true)
	public PositionUsage usage(UUID id) {
		PositionViews.Snapshot s = views.snapshot(false);
		Designation d = require(s, id);
		long employees = count("select count(*) from employee where designation_id = :id and status <> 'EXITED'", id);
		long exited = count("select count(*) from employee where designation_id = :id and status = 'EXITED'", id);
		long titles = count("select count(*) from job_title where designation_id = :id", id);
		long reports = s.tree().childrenOf(id).size();
		// recruiting jobs, offers and approval rules have no tables yet, so nothing can point at the position from there
		long fieldValues = jdbc.sql("""
				select count(*) from config_field_value v join config_field f on f.id = v.field_id
				where f.type = 'POSITION' and v.value #>> '{}' = :id""").param("id", id.toString()).query(Long.class).single();
		return new PositionUsage(employees, exited, titles, reports, 0, 0, 0, 0, fieldValues,
				message(d.getTitle(), employees, exited, titles, reports));
	}

	private static String message(String name, long employees, long exited, long titles, long reports) {
		List<String> parts = new ArrayList<>();
		if (employees > 0) {
			parts.add(employees + (employees == 1 ? " person" : " people"));
		}
		if (titles > 0) {
			parts.add(titles + (titles == 1 ? " title" : " titles"));
		}
		if (parts.isEmpty() && reports > 0) {
			return reports + (reports == 1 ? " position reports" : " positions report") + " to " + name
					+ ". Change their Reports to first.";
		}
		if (parts.isEmpty() && exited > 0) {
			return exited + (exited == 1 ? " former employee has " : " former employees have ") + name
					+ " in their history. Mark it inactive instead of deleting it.";
		}
		if (parts.isEmpty()) {
			return null;
		}
		return String.join(" and ", parts) + (employees + titles == 1 ? " uses " : " use ") + name
				+ ". Move them first, or mark it inactive.";
	}

	/** Active employees in the position this one usually reports to; the same department and location rank first. */
	@Transactional(readOnly = true)
	public List<SuggestedManager> suggestManager(UUID id, UUID departmentId, UUID locationId) {
		PositionViews.Snapshot s = views.snapshot(false);
		Designation d = require(s, id);
		if (d.getReportsToId() == null) {
			return List.of();
		}
		return jdbc.sql("select id, employee_code, display_name, department_id, location_id from employee "
				+ "where designation_id = :pos and status in ('ACTIVE', 'ON_NOTICE')")
				.param("pos", d.getReportsToId())
				.query((rs, i) -> new SuggestedManager(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
						rs.getObject(4, UUID.class), departmentId != null && departmentId.equals(rs.getObject(4, UUID.class)),
						locationId != null && locationId.equals(rs.getObject(5, UUID.class))))
				.list().stream()
				.sorted(Comparator.comparing((SuggestedManager m) -> !m.sameDepartment())
						.thenComparing(m -> !m.sameLocation()).thenComparing(SuggestedManager::displayName))
				.toList();
	}

	/** Cycles, several tops, level inversions, positions without a band, band overlaps, a ladder that is too deep. */
	@Transactional(readOnly = true)
	public LadderHealth validate() {
		PositionViews.Snapshot s = views.snapshot(false);
		List<Designation> all = s.tree().all();
		List<LadderHealth.Item> tops = s.tree().activeTops().size() > 1
				? s.tree().activeTops().stream().map(d -> new LadderHealth.Item(d.getId(), d.getTitle())).toList() : List.of();
		List<LadderHealth.Inversion> inversions = new ArrayList<>();
		for (Designation d : all) {
			Designation p = d.getReportsToId() == null ? null : s.tree().get(d.getReportsToId());
			if (p != null && d.getLevel() != null && p.getLevel() != null && p.getLevel() < d.getLevel()) {
				inversions.add(new LadderHealth.Inversion(d.getId(), d.getTitle(), d.getLevel(), p.getTitle(), p.getLevel()));
			}
		}
		List<LadderHealth.Item> noBand = all.stream()
				.filter(d -> d.isActive() && (d.getBandMax() == null || d.getBandMax().signum() <= 0))
				.map(d -> new LadderHealth.Item(d.getId(), d.getTitle())).toList();
		List<LadderHealth.Overlap> overlaps = new ArrayList<>();
		List<Designation> banded = all.stream().filter(d -> d.isActive() && d.getBandMin() != null && d.getBandMax() != null).toList();
		for (int i = 0; i < banded.size(); i++) {
			for (int j = i + 1; j < banded.size(); j++) {
				Designation a = banded.get(i);
				Designation b = banded.get(j);
				BigDecimal lo = a.getBandMin().max(b.getBandMin());
				BigDecimal hi = a.getBandMax().min(b.getBandMax());
				if (lo.compareTo(hi) < 0) {
					overlaps.add(new LadderHealth.Overlap(a.getTitle(), b.getTitle(), BandFormat.range(lo, hi, s.currency())));
				}
			}
		}
		List<LadderHealth.Item> over = all.stream().filter(d -> s.tree().steps(d.getId()) > s.maxDepth())
				.map(d -> new LadderHealth.Item(d.getId(), d.getTitle())).toList();
		List<LadderHealth.Item> inactiveParent = all.stream().filter(d -> d.isActive() && d.getReportsToId() != null)
				.filter(d -> {
					Designation p = s.tree().get(d.getReportsToId());
					return p != null && !p.isActive();
				}).map(d -> new LadderHealth.Item(d.getId(), d.getTitle())).toList();
		return new LadderHealth(s.tree().cycles(), tops, inversions, noBand, overlaps, over, inactiveParent);
	}

	/** An offer or salary against the position's band; the same check the hire and offer saves run server-side. */
	@Transactional(readOnly = true)
	public BandCheck bandCheck(UUID id, BigDecimal amount) {
		PositionViews.Snapshot s = views.snapshot(false);
		Designation d = require(s, id);
		String currency = s.currency();
		if (d.getBandMin() == null || d.getBandMax() == null) {
			return new BandCheck("NO_BAND", d.getTitle(), null, null, currency, null, false,
					d.getTitle() + " has no band — can't check this offer");
		}
		String band = BandFormat.range(d.getBandMin(), d.getBandMax(), currency);
		String offer = BandFormat.money(amount, currency);
		if (amount.compareTo(d.getBandMax()) > 0) {
			BigDecimal pct = amount.subtract(d.getBandMax()).multiply(BigDecimal.valueOf(100))
					.divide(d.getBandMax(), 1, RoundingMode.HALF_UP);
			boolean needs = settings.outOfBandNeedsApproval();
			return new BandCheck("ABOVE", d.getTitle(), d.getBandMin(), d.getBandMax(), currency, pct, needs,
					offer + " is " + pct.stripTrailingZeros().toPlainString() + "% above the " + d.getTitle() + " band ("
							+ band + ")." + (needs ? " This offer needs approval." : ""));
		}
		if (amount.compareTo(d.getBandMin()) < 0) {
			BigDecimal pct = d.getBandMin().subtract(amount).multiply(BigDecimal.valueOf(100))
					.divide(d.getBandMin(), 1, RoundingMode.HALF_UP).negate();
			return new BandCheck("BELOW", d.getTitle(), d.getBandMin(), d.getBandMax(), currency, pct, false,
					offer + " is below the " + d.getTitle() + " band (" + band + ")");
		}
		return new BandCheck("WITHIN", d.getTitle(), d.getBandMin(), d.getBandMax(), currency, BigDecimal.ZERO, false,
				offer + " is within the " + d.getTitle() + " band (" + band + ")");
	}

	// ---- helpers ----

	private static List<Designation> ordered(PositionViews.Snapshot s, String sort) {
		List<Designation> all = new ArrayList<>(s.tree().all());
		if ("name".equalsIgnoreCase(sort)) {
			all.sort(Comparator.comparing(Designation::getTitle, String.CASE_INSENSITIVE_ORDER));
		}
		return all;
	}

	private static boolean matches(Designation d, String q) {
		if (q == null || q.isBlank()) {
			return true;
		}
		String needle = q.trim().toLowerCase(Locale.ROOT);
		return d.getTitle().toLowerCase(Locale.ROOT).contains(needle)
				|| d.getCode() != null && d.getCode().toLowerCase(Locale.ROOT).contains(needle);
	}

	private Designation require(PositionViews.Snapshot s, UUID id) {
		Designation d = s.tree().get(id);
		if (d == null) {
			throw new NotFoundException("Position " + id + " not found");
		}
		return d;
	}

	private static PositionBasic basic(Designation d) {
		return new PositionBasic(d.getId(), d.getTitle(), d.getCode(), d.getLevel(), d.isActive());
	}

	private long count(String sql, UUID id) {
		return jdbc.sql(sql).param("id", id).query(Long.class).single();
	}
}
