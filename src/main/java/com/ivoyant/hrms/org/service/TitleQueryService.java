package com.ivoyant.hrms.org.service;

import com.ivoyant.hrms.common.forms.RecordCustomFields;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.security.ModuleAccess;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.common.web.PageResponse;
import com.ivoyant.hrms.org.domain.Department;
import com.ivoyant.hrms.org.domain.Designation;
import com.ivoyant.hrms.org.domain.JobTitle;
import com.ivoyant.hrms.org.dto.TitleBasic;
import com.ivoyant.hrms.org.dto.TitleHealth;
import com.ivoyant.hrms.org.dto.TitleHolder;
import com.ivoyant.hrms.org.dto.TitleNode;
import com.ivoyant.hrms.org.dto.TitleOption;
import com.ivoyant.hrms.org.dto.TitleSummary;
import com.ivoyant.hrms.org.dto.TitleUsage;
import com.ivoyant.hrms.org.repo.DepartmentRepository;
import com.ivoyant.hrms.org.repo.DesignationRepository;
import com.ivoyant.hrms.org.repo.JobTitleRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Everything that reads titles: list, summary, pickers, detail, holders, usage and health. */
@Service
public class TitleQueryService {

	private static final String SEP = " › ";

	/** Everything needed to describe the titles of the organization. */
	record Snapshot(List<JobTitle> titles, Map<UUID, JobTitle> byId, Map<UUID, Designation> positions, DeptTree depts,
			Map<UUID, Integer> headcount, Map<UUID, Integer> extras, String currency, boolean canSeeBand) {
	}

	private final JobTitleRepository repo;
	private final DesignationRepository positionRepo;
	private final DepartmentRepository deptRepo;
	private final PositionViews positionViews;
	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final ModuleAccess access;
	private final RecordCustomFields customFields;

	public TitleQueryService(JobTitleRepository repo, DesignationRepository positionRepo, DepartmentRepository deptRepo,
			PositionViews positionViews, JdbcClient jdbc, CurrentUser currentUser, ModuleAccess access,
			RecordCustomFields customFields) {
		this.repo = repo;
		this.positionRepo = positionRepo;
		this.deptRepo = deptRepo;
		this.positionViews = positionViews;
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.access = access;
		this.customFields = customFields;
	}

	Snapshot snapshot(boolean withStats) {
		UUID org = currentUser.orgId();
		List<JobTitle> titles = repo.findByOrgId(org);
		Map<UUID, JobTitle> byId = titles.stream().collect(Collectors.toMap(JobTitle::getId, t -> t));
		Map<UUID, Designation> positions = positionRepo.findByOrgId(org).stream().collect(Collectors.toMap(Designation::getId, p -> p));
		Map<UUID, Integer> headcount = new HashMap<>();
		Map<UUID, Integer> extras = new HashMap<>();
		if (withStats) {
			counts("select title_id, count(*) from employee where org_id = :org and status <> 'EXITED' and title_id is not null group by title_id", org, headcount);
			counts("select x.title_id, count(*) from employee_extra_title x join employee e on e.id = x.employee_id "
					+ "where x.org_id = :org and e.status <> 'EXITED' group by x.title_id", org, extras);
		}
		String currency = jdbc.sql("select currency from org where id = :org").param("org", org).query(String.class)
				.optional().orElse("INR");
		return new Snapshot(titles, byId, positions, new DeptTree(deptRepo.findByOrgId(org)), headcount, extras, currency,
				positionViews.canSeeBand());
	}

	private void counts(String sql, UUID org, Map<UUID, Integer> into) {
		jdbc.sql(sql).param("org", org).query((rs, i) -> {
			into.put(rs.getObject(1, UUID.class), rs.getInt(2));
			return 0;
		}).list();
	}

	TitleNode node(Snapshot s, JobTitle t, boolean stats) {
		Designation p = t.getDesignationId() == null ? null : s.positions().get(t.getDesignationId());
		Department d = t.getDepartmentId() == null ? null : s.depts().get(t.getDepartmentId());
		boolean hasBand = p != null && p.getBandMin() != null && p.getBandMax() != null;
		List<String> warnings = new ArrayList<>();
		if (p != null && !p.isActive()) {
			warnings.add("Position inactive");
		}
		if (d != null && !d.isActive()) {
			warnings.add("Department inactive");
		}
		return new TitleNode(t.getId(), t.getName(), t.getCode(),
				p == null ? null : new TitleNode.Position(p.getId(), p.getTitle(), p.getCode(), p.getLevel(), p.isActive()),
				s.canSeeBand() && hasBand ? BandFormat.range(p.getBandMin(), p.getBandMax(), s.currency()) : null,
				d == null ? null : new TitleNode.Department(d.getId(), d.getName(), String.join(SEP, s.depts().path(d.getId()))),
				d == null ? "All departments" : d.getName() + " and sub-departments", t.getDescription(), t.isActive(),
				t.getSort(), stats ? s.headcount().getOrDefault(t.getId(), 0) : null,
				stats ? s.extras().getOrDefault(t.getId(), 0) : null, stats ? 0 : null, null, t.getVersion(), warnings);
	}

	/** Members get the short form; admins the full rows, most senior position first. */
	@Transactional(readOnly = true)
	public Object list(Boolean active, String q, UUID positionId, UUID departmentId, boolean stats) {
		Snapshot s = snapshot(stats);
		Predicate<JobTitle> keep = t -> (active == null || t.isActive() == active)
				&& (positionId == null || positionId.equals(t.getDesignationId()))
				&& (departmentId == null || departmentId.equals(t.getDepartmentId())) && matches(s, t, q);
		List<JobTitle> ordered = s.titles().stream().filter(keep).sorted(order(s)).toList();
		if (!access.has("org", "VIEW")) {
			return ordered.stream().map(t -> new TitleBasic(t.getId(), t.getName(), t.getCode(), t.getDesignationId(),
					t.getDepartmentId(), t.isActive())).toList();
		}
		return ordered.stream().map(t -> node(s, t, stats)).toList();
	}

	private static Comparator<JobTitle> order(Snapshot s) {
		return Comparator.comparingInt((JobTitle t) -> {
			Designation p = s.positions().get(t.getDesignationId());
			return p == null || p.getLevel() == null ? 0 : p.getLevel();
		}).reversed().thenComparingInt(JobTitle::getSort).thenComparing(JobTitle::getName, String.CASE_INSENSITIVE_ORDER);
	}

	@Transactional(readOnly = true)
	public TitleSummary summary() {
		Snapshot s = snapshot(false);
		long active = s.titles().stream().filter(JobTitle::isActive).count();
		long without = s.titles().stream().filter(t -> t.getDesignationId() == null).count();
		long inactivePos = s.titles().stream().filter(t -> t.isActive() && t.getDesignationId() != null
				&& s.positions().containsKey(t.getDesignationId()) && !s.positions().get(t.getDesignationId()).isActive()).count();
		long unlisted = unlisted();
		long mismatches = mismatches(s).size();
		String text = s.titles().size() + (s.titles().size() == 1 ? " title" : " titles")
				+ (without > 0 ? " · " + without + " without a position" : "")
				+ (unlisted > 0 ? " · " + unlisted + (unlisted == 1 ? " person" : " people") + " with an unlisted title" : "");
		return new TitleSummary(s.titles().size(), active, without, inactivePos, unlisted, mismatches, text,
				!s.titles().isEmpty() && without == 0 && unlisted == 0);
	}

	/** People who have no listed title. */
	long unlisted() {
		return jdbc.sql("select count(*) from employee where org_id = :org and status <> 'EXITED' and title_id is null")
				.param("org", currentUser.orgId()).query(Long.class).single();
	}

	/** Active titles that fit the department (its own, global, and those of the departments above it). */
	@Transactional(readOnly = true)
	public List<TitleOption> options(UUID departmentId) {
		Snapshot s = snapshot(false);
		Set<UUID> allowed = new java.util.HashSet<>();
		if (departmentId != null) {
			allowed.add(departmentId);
			s.depts().ancestors(departmentId).forEach(a -> allowed.add(a.getId()));
		}
		return s.titles().stream().filter(JobTitle::isActive).filter(t -> {
			Designation p = s.positions().get(t.getDesignationId());
			return p != null && p.isActive() && (departmentId == null || t.getDepartmentId() == null || allowed.contains(t.getDepartmentId()));
		}).sorted(order(s)).map(t -> {
			Designation p = s.positions().get(t.getDesignationId());
			return new TitleOption(t.getId(), t.getName(), t.getCode(), p.getId(), p.getTitle(), p.getCode(), p.getLevel(),
					t.getDepartmentId() == null ? "GLOBAL" : "DEPARTMENT");
		}).toList();
	}

	@Transactional(readOnly = true)
	public TitleNode get(UUID id) {
		Snapshot s = snapshot(true);
		JobTitle t = require(s, id);
		return node(s, t, true).withExtras(customFields.read("title", id), node(s, t, false).warnings());
	}

	@Transactional(readOnly = true)
	public PageResponse<TitleHolder> employees(UUID id, boolean includeExtra, int page, int size) {
		require(snapshot(false), id);
		int pageSize = Math.min(Math.max(size, 1), 100);
		String from = includeExtra
				? "(select e.id, true as is_primary from employee e where e.title_id = :id union "
						+ "select x.employee_id, false from employee_extra_title x where x.title_id = :id) h join employee e on e.id = h.id"
				: "(select e.id, true as is_primary from employee e where e.title_id = :id) h join employee e on e.id = h.id";
		long total = jdbc.sql("select count(*) from " + from).param("id", id).query(Long.class).single();
		List<TitleHolder> items = jdbc.sql("select e.id, e.employee_code, e.display_name, e.work_email, e.status, e.department_id, h.is_primary from "
				+ from + " order by e.display_name limit :limit offset :offset").param("id", id).param("limit", pageSize)
				.param("offset", Math.max(page, 0) * pageSize)
				.query((rs, i) -> new TitleHolder(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
						rs.getString(5), rs.getObject(6, UUID.class), rs.getBoolean(7))).list();
		return new PageResponse<>(items, Math.max(page, 0), pageSize, total);
	}

	/** What blocks deleting a title, with the message the screen shows. */
	@Transactional(readOnly = true)
	public TitleUsage usage(UUID id) {
		JobTitle t = require(snapshot(false), id);
		long employees = count("select count(*) from employee where title_id = :id and status <> 'EXITED'", id);
		long total = count("select count(*) from employee where title_id = :id", id);
		long extras = count("select count(*) from employee_extra_title where title_id = :id", id);
		long history = count("select count(*) from employee_title_history where title_id = :id", id);
		long fieldValues = jdbc.sql("""
				select count(*) from config_field_value v join config_field f on f.id = v.field_id
				where f.type = 'TITLE' and v.value #>> '{}' = :id""").param("id", id.toString()).query(Long.class).single();
		// recruiting jobs, offers and onboarding drafts do not exist yet, so nothing can point at the title from there
		return new TitleUsage(employees, total, extras, history, 0, 0, 0, fieldValues, message(t.getName(), employees, extras, history, total));
	}

	private static String message(String name, long employees, long extras, long history, long total) {
		if (employees > 0) {
			return employees + (employees == 1 ? " person holds " : " people hold ") + name
					+ ". Move them to another title first, or mark it inactive.";
		}
		if (extras > 0) {
			return extras + (extras == 1 ? " person has " : " people have ") + name + " as an extra title. Remove it from them first.";
		}
		if (total > 0 || history > 0) {
			return name + " is in the title history of former or current employees. Merge it into another title, or mark it inactive.";
		}
		return null;
	}

	/** Titles with no position or an inactive one, holders outside the title's department, and people with no title. */
	@Transactional(readOnly = true)
	public TitleHealth validate() {
		Snapshot s = snapshot(false);
		List<TitleHealth.Item> without = s.titles().stream().filter(t -> t.getDesignationId() == null)
				.map(t -> new TitleHealth.Item(t.getId(), t.getName())).toList();
		List<TitleHealth.Item> inactive = s.titles().stream().filter(t -> t.getDesignationId() != null
				&& s.positions().containsKey(t.getDesignationId()) && !s.positions().get(t.getDesignationId()).isActive())
				.map(t -> new TitleHealth.Item(t.getId(), t.getName())).toList();
		return new TitleHealth(without, inactive, mismatches(s), unlisted());
	}

	/** Holders (primary title) whose department is outside the department their title is limited to. */
	private List<TitleHealth.Mismatch> mismatches(Snapshot s) {
		List<TitleHealth.Mismatch> out = new ArrayList<>();
		jdbc.sql("select e.id, e.display_name, e.department_id, e.title_id from employee e "
				+ "where e.org_id = :org and e.status <> 'EXITED' and e.title_id is not null").param("org", currentUser.orgId())
				.query((rs, i) -> {
					UUID title = rs.getObject(4, UUID.class);
					UUID dept = rs.getObject(3, UUID.class);
					JobTitle t = s.byId().get(title);
					if (t != null && t.getDepartmentId() != null
							&& (dept == null || !s.depts().descendants(t.getDepartmentId()).contains(dept))) {
						Department d = dept == null ? null : s.depts().get(dept);
						out.add(new TitleHealth.Mismatch(rs.getObject(1, UUID.class), rs.getString(2), title, t.getName(),
								d == null ? null : d.getName()));
					}
					return 0;
				}).list();
		return out;
	}

	// ---- helpers ----

	private static boolean matches(Snapshot s, JobTitle t, String q) {
		if (q == null || q.isBlank()) {
			return true;
		}
		String needle = q.trim().toLowerCase(Locale.ROOT);
		Designation p = s.positions().get(t.getDesignationId());
		return t.getName().toLowerCase(Locale.ROOT).contains(needle)
				|| t.getCode() != null && t.getCode().toLowerCase(Locale.ROOT).contains(needle)
				|| p != null && p.getTitle().toLowerCase(Locale.ROOT).contains(needle);
	}

	private JobTitle require(Snapshot s, UUID id) {
		JobTitle t = s.byId().get(id);
		if (t == null) {
			throw new NotFoundException("Title " + id + " not found");
		}
		return t;
	}

	private long count(String sql, UUID id) {
		return jdbc.sql(sql).param("id", id).query(Long.class).single();
	}
}
