package com.ivoyant.hrms.org.service;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.event.EmployeePositionChangedEvent;
import com.ivoyant.hrms.common.event.PositionActivationChangedEvent;
import com.ivoyant.hrms.common.event.PositionApprovesChangedEvent;
import com.ivoyant.hrms.common.event.PositionBandChangedEvent;
import com.ivoyant.hrms.common.event.PositionCreatedEvent;
import com.ivoyant.hrms.common.event.PositionDeletedEvent;
import com.ivoyant.hrms.common.event.PositionLevelChangedEvent;
import com.ivoyant.hrms.common.event.PositionReportsToChangedEvent;
import com.ivoyant.hrms.common.event.PositionUpdatedEvent;
import com.ivoyant.hrms.common.forms.RecordCustomFields;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.CodedConflictException;
import com.ivoyant.hrms.common.web.ConflictException;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.InUseException;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.common.web.StaleException;
import com.ivoyant.hrms.common.web.UnprocessableException;
import com.ivoyant.hrms.org.OrgSettings;
import com.ivoyant.hrms.org.domain.Designation;
import com.ivoyant.hrms.org.domain.PositionTrack;
import com.ivoyant.hrms.org.dto.PositionNode;
import com.ivoyant.hrms.org.dto.PositionUsage;
import com.ivoyant.hrms.org.repo.DesignationRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creating, changing, reassigning and deleting positions (the {@code designation} table), with every rule the ladder,
 * the bands and the organization settings impose. Ladder rules are always checked against the state the data would be
 * in after the change, so a save that swaps two positions or adds a new top and its child in one go is judged as a whole.
 */
@Service
public class DesignationService {

	private static final String ENTITY = "Position";
	private static final Pattern GRADE = Pattern.compile("^[A-Z0-9][A-Z0-9-]{0,9}$");
	private static final BigDecimal LAKH = BigDecimal.valueOf(100_000);
	/** Request key to the settings list's column, so a bulk save can point at the cell that is wrong. */
	private static final Map<String, String> COLUMNS = Map.ofEntries(Map.entry("name", "name"),
			Map.entry("grade", "grade"), Map.entry("level", "lvl"), Map.entry("track", "track"),
			Map.entry("bandMin", "bmin"), Map.entry("bandMax", "bmax"), Map.entry("reportsToId", "rep"),
			Map.entry("minExp", "minExp"), Map.entry("approves", "appr"), Map.entry("active", "active"),
			Map.entry("jobLevelId", "jl"));

	/** One thing wrong with the position after the change: {@code code} CONFLICT is a plain 409, the rest are coded. */
	private record Problem(Designation on, String field, String code, String message) {
	}

	/** What a position looked like before the change, for the events. */
	private record Before(UUID reportsTo, Integer level, BigDecimal bandMin, BigDecimal bandMax, boolean approves,
			boolean active) {

		static Before of(Designation d) {
			return new Before(d.getReportsToId(), d.getLevel(), d.getBandMin(), d.getBandMax(), d.isApproves(),
					d.isActive());
		}
	}

	@PersistenceContext
	private EntityManager em;

	private final DesignationRepository repo;
	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final OrgSettings settings;
	private final PositionQueryService query;
	private final RecordCustomFields customFields;
	private final Audit audit;
	private final ApplicationEventPublisher events;

	public DesignationService(DesignationRepository repo, JdbcClient jdbc, CurrentUser currentUser, OrgSettings settings,
			PositionQueryService query, RecordCustomFields customFields, Audit audit,
			ApplicationEventPublisher events) {
		this.repo = repo;
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.settings = settings;
		this.query = query;
		this.customFields = customFields;
		this.audit = audit;
		this.events = events;
	}

	// ================================================================== create / update / top

	@Transactional
	@SuppressWarnings("unchecked")
	public PositionNode create(Map<String, Object> body) {
		deferFlush();
		Designation d = new Designation();
		d.setOrgId(currentUser.orgId());
		Input in = new Input(body);
		in.applyTo(d);
		List<String> warnings = new ArrayList<>();
		validate(d, in, true, warnings);
		throwIfInvalid(in);
		List<Designation> all = new ArrayList<>(repo.findByOrgId(d.getOrgId()));
		all.add(d);
		throwFirst(conflicts(all, List.of(d), Set.of()));
		repo.saveAndFlush(d);
		Object custom = body.get("custom");
		customFields.write("position", d.getId(), custom instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of(), true);
		PositionNode out = query.get(d.getId());
		out = out.withExtras(out.custom(), merge(out.warnings(), warnings));
		audit.created("org", ENTITY, d.getId(), out);
		events.publishEvent(new PositionCreatedEvent(d.getId()));
		return out;
	}

	@Transactional
	@SuppressWarnings("unchecked")
	public PositionNode update(UUID id, Map<String, Object> patch, String ifMatch) {
		deferFlush();
		Designation d = find(id);
		if (ifMatch != null && !ifMatch.isBlank()
				&& !ifMatch.replace("W/", "").replace("\"", "").trim().equals(String.valueOf(d.getVersion()))) {
			throw new StaleException("Someone else changed this position. Reload and try again.");
		}
		PositionNode before = query.get(id);
		Before old = Before.of(d);
		Input in = new Input(patch);
		in.applyTo(d);
		List<String> warnings = new ArrayList<>();
		validate(d, in, false, warnings);
		throwIfInvalid(in);
		Set<UUID> deactivated = old.active() && !d.isActive() ? Set.of(id) : Set.of();
		throwFirst(conflicts(repo.findByOrgId(d.getOrgId()), List.of(d), deactivated));
		if (!deactivated.isEmpty()) {
			long people = jdbc.sql("select count(*) from employee where designation_id = :id and status <> 'EXITED'")
					.param("id", id).query(Long.class).single();
			if (people > 0) {
				warnings.add(people + (people == 1 ? " person is" : " people are") + " still in " + d.getTitle());
			}
		}
		repo.saveAndFlush(d);
		Object custom = patch.get("custom");
		if (custom instanceof Map<?, ?> m) {
			customFields.write("position", id, (Map<String, Object>) m, false);
		}
		PositionNode after = query.get(id);
		after = after.withExtras(after.custom(), merge(after.warnings(), warnings));
		audit.updated("org", ENTITY, id, before, after);
		publishChanges(d, old, in.touched);
		return after;
	}

	/** Makes {@code id} the top of the ladder: the old top now reports to it, in one transaction. */
	@Transactional
	public PositionNode setTop(UUID id) {
		deferFlush();
		Designation next = find(id);
		List<Designation> tops = repo.findByOrgId(next.getOrgId()).stream()
				.filter(x -> x.isActive() && x.getReportsToId() == null).toList();
		if (tops.size() == 1 && tops.get(0).getId().equals(id)) {
			return query.get(id);
		}
		if (!next.isActive()) {
			throw new ConflictException("Activate " + next.getTitle() + " first");
		}
		PositionNode before = query.get(id);
		Before oldNext = Before.of(next);
		Map<Designation, Before> olds = new LinkedHashMap<>();
		for (Designation top : tops) {
			olds.put(top, Before.of(top));
			top.setReportsToId(id);
		}
		next.setReportsToId(null);
		List<Designation> changed = new ArrayList<>(olds.keySet());
		changed.add(next);
		throwFirst(conflicts(repo.findByOrgId(next.getOrgId()), changed, Set.of()));
		repo.flush();
		PositionNode after = query.get(id);
		audit.updated("org", ENTITY, id, before, after);
		olds.forEach((top, was) -> publishChanges(top, was, Set.of("reportsToId")));
		publishChanges(next, oldNext, Set.of("reportsToId"));
		return after;
	}

	/**
	 * The checks below query the database while the entity already holds the new values. Flushing at commit (not
	 * before each query) keeps a duplicate name or grade from hitting the database constraint before the friendly
	 * check can answer; the explicit save at the end still writes everything.
	 */
	private void deferFlush() {
		em.setFlushMode(FlushModeType.COMMIT);
	}

	private void publishChanges(Designation d, Before old, Set<String> touched) {
		events.publishEvent(new PositionUpdatedEvent(d.getId(), List.copyOf(touched)));
		if (!Objects.equals(old.reportsTo(), d.getReportsToId())) {
			events.publishEvent(new PositionReportsToChangedEvent(d.getId(), old.reportsTo(), d.getReportsToId()));
		}
		if (!Objects.equals(old.level(), d.getLevel())) {
			events.publishEvent(new PositionLevelChangedEvent(d.getId(), old.level(), d.getLevel()));
		}
		if (!same(old.bandMin(), d.getBandMin()) || !same(old.bandMax(), d.getBandMax())) {
			events.publishEvent(new PositionBandChangedEvent(d.getId(), old.bandMin(), old.bandMax(), d.getBandMin(),
					d.getBandMax()));
		}
		if (old.approves() != d.isApproves()) {
			events.publishEvent(new PositionApprovesChangedEvent(d.getId(), d.isApproves()));
		}
		if (old.active() != d.isActive()) {
			events.publishEvent(new PositionActivationChangedEvent(d.getId(), d.isActive()));
		}
	}

	private static boolean same(BigDecimal a, BigDecimal b) {
		return a == null ? b == null : b != null && a.compareTo(b) == 0;
	}

	private static List<String> merge(List<String> a, List<String> b) {
		List<String> out = new ArrayList<>(a == null ? List.of() : a);
		b.stream().filter(w -> !out.contains(w)).forEach(out::add);
		return out;
	}

	// ================================================================== the rules that depend on the other positions

	/**
	 * Duplicates, loops, seniority, the single top, the depth limit and deactivation, checked on {@code all} as it will
	 * be after the change. Only the {@code changed} positions are blamed.
	 */
	private List<Problem> conflicts(Iterable<Designation> all, Iterable<Designation> changed, Set<UUID> deactivated) {
		List<Designation> list = new ArrayList<>();
		all.forEach(list::add);
		PosTree tree = new PosTree(list);
		int maxDepth = settings.maxReportingDepth();
		List<Problem> out = new ArrayList<>();
		for (Designation e : changed) {
			String name = e.getTitle() == null ? "" : e.getTitle().toLowerCase(Locale.ROOT);
			if (list.stream().anyMatch(o -> o != e && !o.getId().equals(e.getId()) && o.getTitle() != null
					&& o.getTitle().toLowerCase(Locale.ROOT).equals(name))) {
				out.add(new Problem(e, "name", "CONFLICT", "A position with this name already exists"));
			}
			if (e.getCode() != null && list.stream().anyMatch(o -> o != e && !o.getId().equals(e.getId())
					&& e.getCode().equalsIgnoreCase(o.getCode()))) {
				out.add(new Problem(e, "grade", "CONFLICT", "A position with this grade already exists"));
			}
			UUID parentId = e.getReportsToId();
			Designation parent = parentId == null ? null : tree.get(parentId);
			if (parentId != null && parent == null) {
				out.add(new Problem(e, "reportsToId", "REPORTS_TO_INVALID", "Reports-to position not found"));
			}
			else if (parentId != null && parentId.equals(e.getId())) {
				out.add(new Problem(e, "reportsToId", "CONFLICT", "A position cannot report to itself"));
			}
			else if (parent != null && tree.inCycle(e.getId())) {
				out.add(new Problem(e, "reportsToId", "CONFLICT", "That would make the ladder loop"));
			}
			else if (parent != null) {
				if (e.isActive() && !parent.isActive()) {
					out.add(new Problem(e, "reportsToId", "REPORTS_TO_INACTIVE",
							parent.getTitle() + " is inactive. Activate it first, or pick another position"));
				}
				if (e.getLevel() != null && parent.getLevel() != null && parent.getLevel() < e.getLevel()) {
					out.add(new Problem(e, "reportsToId", "LEVEL_INVERSION", e.getTitle() + " (level " + e.getLevel()
							+ ") can't report to " + parent.getTitle() + " (level " + parent.getLevel()
							+ "). Pick a more senior position."));
				}
				int deepest = tree.steps(e.getId()) + tree.height(e.getId());
				if (deepest > maxDepth) {
					out.add(new Problem(e, "reportsToId", "CONFLICT", "The ladder would be " + deepest
							+ " steps deep; the limit is " + maxDepth + " (Settings → Organization)"));
				}
			}
			if (e.getLevel() != null && !tree.inCycle(e.getId())) {
				for (Designation child : tree.childrenOf(e.getId())) {
					if (child.getLevel() != null && child.getLevel() > e.getLevel()) {
						out.add(new Problem(e, "level", "LEVEL_INVERSION", child.getTitle() + " (level " + child.getLevel()
								+ ") reports to " + e.getTitle() + ". A position must be more senior than the ones reporting to it."));
						break;
					}
				}
			}
			if (e.isActive() && parentId == null) {
				List<Designation> tops = tree.activeTops();
				if (tops.size() > 1) {
					Designation other = tops.stream().filter(t -> !t.getId().equals(e.getId())).findFirst().orElse(null);
					out.add(new Problem(e, "reportsToId", "MULTIPLE_TOPS", (other == null ? "Another position" : other.getTitle())
							+ " is already the top position. Pick who this reports to."));
				}
			}
			if (deactivated.contains(e.getId())) {
				if (parentId == null) {
					out.add(new Problem(e, "active", "IN_USE",
							e.getTitle() + " is the top position and can't be deactivated. Make another position the top first."));
				}
				long activeKids = tree.childrenOf(e.getId()).stream().filter(Designation::isActive).count();
				if (activeKids > 0) {
					out.add(new Problem(e, "active", "IN_USE", activeKids + (activeKids == 1 ? " position reports" : " positions report")
							+ " to " + e.getTitle() + ". Change their Reports to first."));
				}
			}
		}
		return out;
	}

	private void throwFirst(List<Problem> problems) {
		if (!problems.isEmpty()) {
			throw toException(problems.get(0));
		}
	}

	private RuntimeException toException(Problem p) {
		return switch (p.code()) {
			case "IN_USE" -> new InUseException(p.message(), usageOf(p.on()));
			case "REPORTS_TO_INVALID" -> new UnprocessableException(List.of(ErrorItem.field(p.field(), p.code(), p.message())));
			case "CONFLICT" -> new ConflictException(p.message());
			default -> new CodedConflictException(p.code(), p.message());
		};
	}

	private Map<String, Object> usageOf(Designation d) {
		if (d == null || d.getId() == null || d.getVersion() == null) {
			return Map.of();
		}
		return usageMap(query.usage(d.getId()));
	}

	// ================================================================== delete

	/**
	 * Hard delete, only when nothing points at the position. With {@code reassignTo}, its people, titles and the
	 * positions reporting to it are moved there first (former employees still block: their history keeps pointing here).
	 */
	@Transactional
	public void delete(UUID id, UUID reassignTo) {
		Designation d = find(id);
		if (d.isActive() && d.getReportsToId() == null) {
			throw new InUseException(d.getTitle() + " is the top position and can't be deleted. Make another position the top first.",
					usageMap(query.usage(id)));
		}
		PositionNode before = query.get(id);
		if (reassignTo != null) {
			reassign(d, reassignTo);
		}
		PositionUsage usage = query.usage(id);
		if (usage.blocksDelete()) {
			String message = usage.message() != null ? usage.message()
					: "This position is still used by other data. Move it first, or mark it inactive.";
			throw new InUseException(message, usageMap(usage));
		}
		customFields.delete("position", id);
		repo.delete(d);
		repo.flush();
		audit.deleted("org", ENTITY, id, before);
		events.publishEvent(new PositionDeletedEvent(id, reassignTo));
	}

	private void reassign(Designation from, UUID targetId) {
		Designation target = find(targetId);
		if (targetId.equals(from.getId())) {
			throw new ConflictException("Choose a different position to move everything to");
		}
		if (!target.isActive()) {
			throw new ConflictException(target.getTitle() + " is inactive. Choose an active position");
		}
		long exited = jdbc.sql("select count(*) from employee where designation_id = :id and status = 'EXITED'")
				.param("id", from.getId()).query(Long.class).single();
		if (exited > 0) {
			throw new InUseException(exited + (exited == 1 ? " former employee has " : " former employees have ")
					+ from.getTitle() + " in their history. Mark the position inactive instead of deleting it.",
					Map.of("exitedEmployees", exited));
		}
		// positions that reported here now report to the target (the target itself takes this position's place)
		List<Designation> kids = repo.findByOrgIdAndReportsToId(from.getOrgId(), from.getId());
		Map<Designation, Before> olds = new LinkedHashMap<>();
		for (Designation kid : kids) {
			olds.put(kid, Before.of(kid));
			kid.setReportsToId(kid.getId().equals(targetId) ? from.getReportsToId() : targetId);
		}
		if (!kids.isEmpty()) {
			throwFirst(conflicts(repo.findByOrgId(from.getOrgId()), kids, Set.of()));
			repo.flush();
			olds.forEach((kid, was) -> publishChanges(kid, was, Set.of("reportsToId")));
		}
		List<UUID> people = jdbc.sql("select id from employee where designation_id = :id and status <> 'EXITED'")
				.param("id", from.getId()).query(UUID.class).list();
		jdbc.sql("update employee set designation_id = :t where designation_id = :f and status <> 'EXITED'")
				.param("t", targetId).param("f", from.getId()).update();
		people.forEach(e -> events.publishEvent(new EmployeePositionChangedEvent(e, from.getId(), targetId)));
		jdbc.sql("update job_title set designation_id = :t where designation_id = :f").param("t", targetId)
				.param("f", from.getId()).update();
		em.flush();
	}

	private static Map<String, Object> usageMap(PositionUsage u) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("employees", u.employees());
		m.put("exitedEmployees", u.exitedEmployees());
		m.put("titles", u.titles());
		m.put("directReports", u.directReports());
		m.put("openJobs", u.openJobs());
		m.put("pendingOffers", u.pendingOffers());
		m.put("onboardingDrafts", u.onboardingDrafts());
		m.put("approvalRules", u.approvalRules());
		m.put("customFieldValues", u.customFieldValues());
		return m;
	}

	// ================================================================== bulk save (Settings → Onboarding → Positions)

	/**
	 * Saves the whole positions list through the same rules as the API. Every row is applied first and the ladder is
	 * judged as it will be afterwards (swapping two reports-to, or a new top with its new child, is fine); every
	 * problem comes back with its row and column and nothing is kept if any row is wrong.
	 */
	@Transactional
	public void bulkUpsert(List<Map<String, Object>> rows) {
		deferFlush();
		UUID org = currentUser.orgId();
		Map<UUID, Designation> existing = new HashMap<>();
		repo.findByOrgId(org).forEach(d -> existing.put(d.getId(), d));
		Map<UUID, Integer> indexOf = new HashMap<>();
		Map<UUID, Before> olds = new HashMap<>();
		Map<UUID, Input> inputs = new HashMap<>();
		List<Designation> changed = new ArrayList<>();
		List<Designation> created = new ArrayList<>();
		Set<UUID> deactivated = new HashSet<>();
		List<ErrorItem> errors = new ArrayList<>();
		for (int i = 0; i < rows.size(); i++) {
			Map<String, Object> row = rows.get(i);
			UUID id = UUID.fromString(row.get("id").toString());
			boolean isNew = !existing.containsKey(id);
			Designation d = isNew ? new Designation() : existing.get(id);
			if (isNew) {
				d.setId(id);
				d.setOrgId(org);
			}
			else {
				olds.put(id, Before.of(d));
			}
			Input in = new Input(toBody(row));
			in.applyTo(d);
			validate(d, in, true, new ArrayList<>());
			final int index = i;
			in.errors.forEach(e -> errors.add(ErrorItem.cell("designations", index, column(e.fieldKey()), e.message())));
			indexOf.put(id, i);
			inputs.put(id, in);
			changed.add(d);
			if (isNew) {
				created.add(d);
			}
			else if (olds.get(id).active() && !d.isActive()) {
				deactivated.add(id);
			}
		}
		if (errors.isEmpty()) {
			List<Designation> all = new ArrayList<>(existing.values());
			all.addAll(created);
			for (Problem p : conflicts(all, changed, deactivated)) {
				errors.add(ErrorItem.cell("designations", indexOf.get(p.on().getId()), column(p.field()), p.message()));
			}
		}
		if (!errors.isEmpty()) {
			throw new UnprocessableException(errors);
		}
		// new positions go in parent-first so the reports-to foreign key always finds its row
		PosTree finalTree = new PosTree(java.util.stream.Stream.concat(existing.values().stream(), created.stream()).toList());
		created.stream().sorted(Comparator.comparingInt((Designation d) -> finalTree.steps(d.getId())))
				.forEach(d -> repo.saveAndFlush(d));
		repo.flush();
		for (Designation d : created) {
			customFields.write("position", d.getId(), Map.of(), true);
			events.publishEvent(new PositionCreatedEvent(d.getId()));
		}
		for (Designation d : changed) {
			if (olds.containsKey(d.getId())) {
				publishChanges(d, olds.get(d.getId()), inputs.get(d.getId()).touched);
			}
		}
	}

	/** Deletes the positions that are no longer in the list, each through the usage guard (the furthest from the top first). */
	@Transactional
	public void bulkDelete(Set<UUID> keep) {
		PosTree tree = new PosTree(repo.findByOrgId(currentUser.orgId()));
		tree.all().stream().filter(d -> !keep.contains(d.getId()))
				.sorted(Comparator.comparingInt((Designation d) -> tree.steps(d.getId())).reversed())
				.forEach(d -> delete(d.getId(), null));
	}

	private static String column(String field) {
		return COLUMNS.getOrDefault(field, field);
	}

	/** The settings list's row as a request body: lakhs become full amounts for INR, labels become enum names. */
	private Map<String, Object> toBody(Map<String, Object> row) {
		boolean lakhs = "INR".equalsIgnoreCase(jdbc.sql("select currency from org where id = :o").param("o", currentUser.orgId())
				.query(String.class).optional().orElse("INR"));
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("name", row.get("name"));
		body.put("grade", row.get("grade"));
		body.put("level", row.get("lvl"));
		body.put("track", row.get("track"));
		body.put("bandMin", lakhs ? scale(row.get("bmin")) : row.get("bmin"));
		body.put("bandMax", lakhs ? scale(row.get("bmax")) : row.get("bmax"));
		body.put("reportsToId", row.get("rep"));
		body.put("minExp", row.get("minExp"));
		body.put("approves", row.get("appr") != null && Boolean.parseBoolean(row.get("appr").toString()));
		body.put("active", row.get("active") == null || Boolean.parseBoolean(row.get("active").toString()));
		if (row.containsKey("jl")) {
			body.put("jobLevelId", row.get("jl"));
		}
		return body;
	}

	private static Object scale(Object lakhs) {
		if (lakhs == null || lakhs.toString().isBlank()) {
			return null;
		}
		try {
			return new BigDecimal(lakhs.toString().trim()).multiply(LAKH);
		}
		catch (NumberFormatException ex) {
			return lakhs;
		}
	}

	// ================================================================== input and validation

	/** A request body applied to a position: which keys were present, and what went wrong reading them. */
	private static final class Input {

		final Map<String, Object> body;
		final Set<String> touched = new LinkedHashSet<>();
		final List<ErrorItem> errors = new ArrayList<>();

		Input(Map<String, Object> body) {
			this.body = body;
		}

		void applyTo(Designation d) {
			for (Map.Entry<String, Object> e : body.entrySet()) {
				String k = e.getKey();
				Object v = e.getValue();
				boolean known = true;
				try {
					switch (k) {
						case "name", "title" -> {
							d.setTitle(text(v));
							k = "name";
						}
						case "grade", "code" -> {
							d.setCode(upper(v));
							k = "grade";
						}
						case "level" -> d.setLevel(v == null || v.toString().isBlank() ? null : whole(v));
						case "track" -> d.setTrack(track(v));
						case "bandMin" -> d.setBandMin(money(v));
						case "bandMax" -> d.setBandMax(money(v));
						case "reportsToId" -> d.setReportsToId(uuid(v));
						case "jobLevelId" -> d.setJobLevelId(uuid(v));
						case "minExp" -> d.setMinExp(years(v));
						case "approves" -> d.setApproves(v != null && bool(v));
						case "active" -> d.setActive(v == null || bool(v));
						case "description" -> d.setDescription(text(v));
						default -> known = false;
					}
				}
				catch (IllegalArgumentException ex) {
					errors.add(ErrorItem.field(k, "INVALID".equals(ex.getMessage()) ? "INVALID" : codeOf(ex), message(k, ex)));
				}
				if (known) {
					touched.add(k);
				}
			}
		}

		private static String codeOf(IllegalArgumentException ex) {
			return ex.getMessage() != null && ex.getMessage().startsWith("TRACK") ? "TRACK_INVALID" : "INVALID";
		}

		private static String message(String key, IllegalArgumentException ex) {
			if (ex.getMessage() != null && ex.getMessage().startsWith("TRACK")) {
				return "Track must be Executive, Management or Individual contributor";
			}
			return "'" + key + "' is not valid: " + ex.getMessage();
		}

		private static String text(Object v) {
			return v == null || v.toString().isBlank() ? null : v.toString().trim();
		}

		private static String upper(Object v) {
			String t = text(v);
			return t == null ? null : t.toUpperCase(Locale.ROOT);
		}

		private static UUID uuid(Object v) {
			String t = text(v);
			return t == null ? null : UUID.fromString(t);
		}

		private static Integer whole(Object v) {
			if (v instanceof Number n) {
				if (n.doubleValue() != Math.floor(n.doubleValue())) {
					throw new IllegalArgumentException("must be a whole number");
				}
				return n.intValue();
			}
			return Integer.parseInt(v.toString().trim());
		}

		private static BigDecimal decimal(Object v) {
			if (v == null || v.toString().isBlank()) {
				return null;
			}
			return v instanceof BigDecimal b ? b : new BigDecimal(v.toString().trim());
		}

		private static BigDecimal money(Object v) {
			BigDecimal b = decimal(v);
			return b == null ? null : b.setScale(2, RoundingMode.HALF_UP);
		}

		private static BigDecimal years(Object v) {
			BigDecimal b = decimal(v);
			return b == null ? null : b.setScale(1, RoundingMode.HALF_UP);
		}

		private static String track(Object v) {
			String t = text(v);
			if (t == null) {
				return PositionTrack.INDIVIDUAL_CONTRIBUTOR.name();
			}
			PositionTrack parsed = PositionTrack.parse(t);
			if (parsed == null) {
				throw new IllegalArgumentException("TRACK");
			}
			return parsed.name();
		}

		private static boolean bool(Object v) {
			if (v instanceof Boolean b) {
				return b;
			}
			String t = v.toString().trim().toLowerCase(Locale.ROOT);
			if (!t.equals("true") && !t.equals("false")) {
				throw new IllegalArgumentException("must be true or false");
			}
			return Boolean.parseBoolean(t);
		}
	}

	/** Field-by-field checks that need nothing but the position and the job levels: these are 422s. */
	private void validate(Designation d, Input in, boolean create, List<String> warnings) {
		List<ErrorItem> errors = in.errors;
		java.util.function.Predicate<String> any = key -> create || in.touched.contains(key);
		if (any.test("name")) {
			if (d.getTitle() == null) {
				errors.add(ErrorItem.field("name", "REQUIRED", "Every position needs a name"));
			}
			else if (d.getTitle().length() > 100) {
				errors.add(ErrorItem.field("name", "INVALID", "Name can be at most 100 characters"));
			}
		}
		if (d.getCode() != null && any.test("grade") && !GRADE.matcher(d.getCode()).matches()) {
			errors.add(ErrorItem.field("grade", "GRADE_FORMAT", "Grade must be 1–10 letters, digits or dashes, like G5"));
		}
		if (any.test("level")) {
			if (d.getLevel() == null) {
				errors.add(ErrorItem.field("level", "REQUIRED", "Level is required (1 is entry level)"));
			}
			else if (d.getLevel() < 1 || d.getLevel() > 20) {
				errors.add(ErrorItem.field("level", "LEVEL_RANGE", "Level must be between 1 and 20"));
			}
		}
		if (any.test("bandMin") || any.test("bandMax")) {
			BigDecimal lo = d.getBandMin();
			BigDecimal hi = d.getBandMax();
			if ((lo == null) != (hi == null)) {
				errors.add(ErrorItem.field(lo == null ? "bandMin" : "bandMax", "BAND_INCOMPLETE",
						"Set both ends of the band, or neither"));
			}
			else if (lo != null && (lo.signum() < 0 || hi.signum() < 0)) {
				errors.add(ErrorItem.field("bandMin", "BAND_ORDER", "A band can't be negative"));
			}
			else if (lo != null && lo.compareTo(hi) > 0) {
				errors.add(ErrorItem.field("bandMin", "BAND_ORDER", "Band min must not be above band max"));
			}
		}
		if (d.getMinExp() != null && any.test("minExp")
				&& (d.getMinExp().signum() < 0 || d.getMinExp().compareTo(BigDecimal.valueOf(50)) > 0)) {
			errors.add(ErrorItem.field("minExp", "MIN_EXP_RANGE", "Min experience must be between 0 and 50 years"));
		}
		if (d.getDescription() != null && d.getDescription().length() > 500) {
			errors.add(ErrorItem.field("description", "INVALID", "Description can be at most 500 characters"));
		}
		if (d.getJobLevelId() != null && any.test("jobLevelId")) {
			Boolean active = jdbc.sql("select active from job_level where id = :id and org_id = :org")
					.param("id", d.getJobLevelId()).param("org", d.getOrgId()).query(Boolean.class).optional().orElse(null);
			if (active == null) {
				errors.add(ErrorItem.field("jobLevelId", "INVALID", "That level does not exist"));
			}
			else if (!active) {
				errors.add(ErrorItem.field("jobLevelId", "JOB_LEVEL_INACTIVE", "That level is inactive"));
			}
		}
	}

	private void throwIfInvalid(Input in) {
		if (!in.errors.isEmpty()) {
			throw new UnprocessableException(in.errors);
		}
	}

	private Designation find(UUID id) {
		return repo.findByIdAndOrgId(id, currentUser.orgId())
				.orElseThrow(() -> new NotFoundException("Position " + id + " not found"));
	}
}
