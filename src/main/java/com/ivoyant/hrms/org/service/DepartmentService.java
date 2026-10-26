package com.ivoyant.hrms.org.service;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.authz.FgaOutbox;
import com.ivoyant.hrms.common.authz.FgaTuples;
import com.ivoyant.hrms.common.event.DepartmentActivationChangedEvent;
import com.ivoyant.hrms.common.event.DepartmentCreatedEvent;
import com.ivoyant.hrms.common.event.DepartmentDeletedEvent;
import com.ivoyant.hrms.common.event.DepartmentHeadChangedEvent;
import com.ivoyant.hrms.common.event.DepartmentMovedEvent;
import com.ivoyant.hrms.common.event.DepartmentUpdatedEvent;
import com.ivoyant.hrms.common.event.EmployeeDepartmentChangedEvent;
import com.ivoyant.hrms.common.forms.RecordCustomFields;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.BadRequestException;
import com.ivoyant.hrms.common.web.ConflictException;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.InUseException;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.common.web.StaleException;
import com.ivoyant.hrms.common.web.UnprocessableException;
import com.ivoyant.hrms.org.domain.Department;
import com.ivoyant.hrms.org.domain.Location;
import com.ivoyant.hrms.org.dto.DepartmentNode;
import com.ivoyant.hrms.org.dto.DepartmentUsage;
import com.ivoyant.hrms.org.repo.DepartmentRepository;
import com.ivoyant.hrms.org.repo.LocationRepository;
import java.util.ArrayList;
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
import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.PersistenceContext;
import java.util.regex.Pattern;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creating, changing, moving and deleting departments, with every rule the settings and the data impose. */
@Service
public class DepartmentService {

	private static final String ENTITY = "Department";
	private static final Pattern CODE = Pattern.compile("^[A-Z0-9][A-Z0-9-]{1,19}$");
	private static final Pattern COST_CENTRE = Pattern.compile("^[A-Z0-9-]{2,20}$");
	/** Request key to the settings list's column, so a bulk save can point at the cell that is wrong. */
	private static final Map<String, String> COLUMNS = Map.of("name", "name", "code", "code", "headEmployeeId", "head",
			"parentId", "parent", "costCentre", "cc", "defaultLocationId", "loc", "active", "active");

	@PersistenceContext
	private EntityManager em;

	private final DepartmentRepository repo;
	private final LocationRepository locationRepo;
	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final DepartmentRules rules;
	private final DepartmentViews views;
	private final DepartmentQueryService query;
	private final RecordCustomFields customFields;
	private final FgaOutbox fga;
	private final Audit audit;
	private final ApplicationEventPublisher events;

	public DepartmentService(DepartmentRepository repo, LocationRepository locationRepo, JdbcClient jdbc,
			CurrentUser currentUser, DepartmentRules rules, DepartmentViews views, DepartmentQueryService query,
			RecordCustomFields customFields, FgaOutbox fga, Audit audit, ApplicationEventPublisher events) {
		this.repo = repo;
		this.locationRepo = locationRepo;
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.rules = rules;
		this.views = views;
		this.query = query;
		this.customFields = customFields;
		this.fga = fga;
		this.audit = audit;
		this.events = events;
	}

	// ================================================================== create / update / move

	@Transactional
	@SuppressWarnings("unchecked")
	public DepartmentNode create(Map<String, Object> body) {
		Department d = new Department();
		d.setOrgId(currentUser.orgId());
		Input in = new Input(body);
		in.applyTo(d);
		List<String> warnings = new ArrayList<>();
		validate(d, in, true, warnings);
		throwIfInvalid(in);
		requireConflictFree(d, null, in, true);
		if (!in.touched.contains("defaultLocationId") || d.getDefaultLocationId() == null) {
			// inherit the parent's default location, stored explicitly so later edits to the parent do not ripple down
			d.setDefaultLocationId(d.getParentId() == null ? null
					: repo.findByIdAndOrgId(d.getParentId(), d.getOrgId()).map(Department::getDefaultLocationId).orElse(null));
		}
		repo.saveAndFlush(d);
		Object custom = body.get("custom");
		customFields.write("department", d.getId(), custom instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of(), true);
		fga.write(FgaTuples.departmentOrg(d.getOrgId(), d.getId()));
		if (d.getParentId() != null) {
			fga.write(FgaTuples.departmentParent(d.getId(), d.getParentId()));
		}
		if (d.getHeadEmployeeId() != null) {
			fga.write(FgaTuples.departmentHead(d.getId(), d.getHeadEmployeeId()));
		}
		DepartmentNode out = query.get(d.getId()).withExtras(customFields.read("department", d.getId()), warnings);
		audit.created("org", ENTITY, d.getId(), out);
		events.publishEvent(new DepartmentCreatedEvent(d.getId(), d.getParentId()));
		return out;
	}

	@Transactional
	@SuppressWarnings("unchecked")
	public DepartmentNode update(UUID id, Map<String, Object> patch, String ifMatch) {
		deferFlush();
		Department d = find(id);
		if (ifMatch != null && !ifMatch.isBlank()
				&& !ifMatch.replace("W/", "").replace("\"", "").trim().equals(String.valueOf(d.getVersion()))) {
			throw new StaleException("Someone else changed this department. Reload and try again.");
		}
		DepartmentNode before = query.get(id);
		UUID oldParent = d.getParentId();
		UUID oldHead = d.getHeadEmployeeId();
		boolean wasActive = d.isActive();
		Input in = new Input(patch);
		in.applyTo(d);
		List<String> warnings = new ArrayList<>();
		validate(d, in, false, warnings);
		throwIfInvalid(in);
		requireConflictFree(d, id, in, false);
		if (wasActive && !d.isActive()) {
			deactivationChecks(d, warnings);
		}
		if (!wasActive && d.isActive()) {
			activationChecks(d);
		}
		repo.saveAndFlush(d);
		Object custom = patch.get("custom");
		if (custom instanceof Map<?, ?> m) {
			customFields.write("department", id, (Map<String, Object>) m, false);
		}
		syncTuples(d, oldParent, oldHead);
		DepartmentNode after = query.get(id).withExtras(customFields.read("department", id), warnings);
		audit.updated("org", ENTITY, id, before, after);
		List<String> changed = List.copyOf(in.touched);
		events.publishEvent(new DepartmentUpdatedEvent(id, changed));
		if (!Objects.equals(oldParent, d.getParentId())) {
			events.publishEvent(new DepartmentMovedEvent(id, oldParent, d.getParentId()));
		}
		if (!Objects.equals(oldHead, d.getHeadEmployeeId())) {
			events.publishEvent(new DepartmentHeadChangedEvent(id, oldHead, d.getHeadEmployeeId()));
		}
		if (wasActive != d.isActive()) {
			events.publishEvent(new DepartmentActivationChangedEvent(id, d.isActive()));
		}
		return after;
	}

	/** Drag-and-drop: a new parent (null makes it a root) and/or a new place among its siblings. */
	@Transactional
	public DepartmentNode move(UUID id, UUID parentId, Integer sort) {
		Map<String, Object> patch = new LinkedHashMap<>();
		patch.put("parentId", parentId == null ? null : parentId.toString());
		if (sort != null) {
			patch.put("sort", sort);
		}
		return update(id, patch, null);
	}

	/**
	 * The checks below query the database while the entity already holds the new values. Flushing at commit (not
	 * before each query) keeps a duplicate name, code or parent from hitting the database constraint before the
	 * friendly check can answer; the explicit save at the end still writes everything.
	 */
	private void deferFlush() {
		em.setFlushMode(FlushModeType.COMMIT);
	}

	// ---- the rules that depend on the other departments ----

	private void deactivationChecks(Department d, List<String> warnings) {
		List<String> activeChildren = repo.findByOrgIdAndParentId(d.getOrgId(), d.getId()).stream()
				.filter(Department::isActive).map(Department::getName).toList();
		if (!activeChildren.isEmpty()) {
			throw new InUseException("Deactivate or move its sub-departments first.", Map.of("children", activeChildren));
		}
		long people = jdbc.sql("select count(*) from employee where department_id = :id and status <> 'EXITED'")
				.param("id", d.getId()).query(Long.class).single();
		if (people > 0) {
			warnings.add(people + (people == 1 ? " person is" : " people are") + " still in " + d.getName());
		}
	}

	private void activationChecks(Department d) {
		if (d.getParentId() != null) {
			Department parent = repo.findById(d.getParentId()).orElse(null);
			if (parent != null && !parent.isActive()) {
				throw new ConflictException("Activate " + parent.getName() + " first");
			}
		}
	}

	private void syncTuples(Department d, UUID oldParent, UUID oldHead) {
		if (!Objects.equals(oldParent, d.getParentId())) {
			if (oldParent != null) {
				fga.delete(FgaTuples.departmentParent(d.getId(), oldParent));
			}
			if (d.getParentId() != null) {
				fga.write(FgaTuples.departmentParent(d.getId(), d.getParentId()));
			}
		}
		if (!Objects.equals(oldHead, d.getHeadEmployeeId())) {
			if (oldHead != null) {
				fga.delete(FgaTuples.departmentHead(d.getId(), oldHead));
			}
			if (d.getHeadEmployeeId() != null) {
				fga.write(FgaTuples.departmentHead(d.getId(), d.getHeadEmployeeId()));
			}
		}
	}

	// ================================================================== delete

	/**
	 * Hard delete, only when nothing points at the department. With {@code reassignTo}, its people, titles, teams and
	 * sub-departments are moved there first (former employees still block: their history keeps pointing here).
	 */
	@Transactional
	public void delete(UUID id, UUID reassignTo) {
		Department d = find(id);
		DepartmentNode before = query.get(id);
		if (reassignTo != null) {
			reassign(d, reassignTo);
		}
		DepartmentUsage usage = query.usage(id);
		if (usage.blocksDelete()) {
			String message = usage.message() != null ? usage.message()
					: "This department is still used by other data. Move it first, or mark the department inactive.";
			throw new InUseException(message, usageMap(usage));
		}
		fga.delete(FgaTuples.departmentOrg(d.getOrgId(), id));
		if (d.getParentId() != null) {
			fga.delete(FgaTuples.departmentParent(id, d.getParentId()));
		}
		if (d.getHeadEmployeeId() != null) {
			fga.delete(FgaTuples.departmentHead(id, d.getHeadEmployeeId()));
		}
		customFields.delete("department", id);
		repo.delete(d);
		repo.flush();
		audit.deleted("org", ENTITY, id, before);
		events.publishEvent(new DepartmentDeletedEvent(id, reassignTo));
	}

	private void reassign(Department from, UUID targetId) {
		Department target = find(targetId);
		DeptTree tree = new DeptTree(repo.findByOrgId(from.getOrgId()));
		if (targetId.equals(from.getId()) || tree.descendants(from.getId()).contains(targetId)) {
			throw new ConflictException("Choose a department outside " + from.getName() + " to move everything to");
		}
		if (!target.isActive()) {
			throw new ConflictException(target.getName() + " is inactive. Choose an active department");
		}
		long exited = jdbc.sql("select count(*) from employee where department_id = :id and status = 'EXITED'")
				.param("id", from.getId()).query(Long.class).single();
		if (exited > 0) {
			throw new InUseException(exited + (exited == 1 ? " former employee has " : " former employees have ")
					+ from.getName() + " in their history. Mark the department inactive instead of deleting it.",
					Map.of("exitedEmployees", exited));
		}
		// sub-departments move up to the target, each checked against the depth limit
		for (Department child : repo.findByOrgIdAndParentId(from.getOrgId(), from.getId())) {
			rules.checkParent(targetId, child.getId());
			fga.delete(FgaTuples.departmentParent(child.getId(), from.getId()));
			child.setParentId(targetId);
			repo.saveAndFlush(child);
			fga.write(FgaTuples.departmentParent(child.getId(), targetId));
			events.publishEvent(new DepartmentMovedEvent(child.getId(), from.getId(), targetId));
		}
		List<UUID> people = jdbc.sql("select id from employee where department_id = :id and status <> 'EXITED'")
				.param("id", from.getId()).query(UUID.class).list();
		for (UUID employee : people) {
			FgaTuples.department(employee, from.getId()).forEach(fga::delete);
			FgaTuples.department(employee, targetId).forEach(fga::write);
			events.publishEvent(new EmployeeDepartmentChangedEvent(employee, from.getId(), targetId));
		}
		jdbc.sql("update employee set department_id = :t where department_id = :f and status <> 'EXITED'")
				.param("t", targetId).param("f", from.getId()).update();
		jdbc.sql("update job_title set department_id = :t where department_id = :f").param("t", targetId)
				.param("f", from.getId()).update();
		jdbc.sql("update team set department_id = :t where department_id = :f").param("t", targetId)
				.param("f", from.getId()).update();
	}

	private static Map<String, Object> usageMap(DepartmentUsage u) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("employees", u.employees());
		m.put("employeesTotal", u.employeesTotal());
		m.put("exitedEmployees", u.exitedEmployees());
		m.put("children", u.children());
		m.put("titles", u.titles());
		m.put("teams", u.teams());
		m.put("openJobs", u.openJobs());
		m.put("pendingHires", u.pendingHires());
		m.put("roleScopes", u.roleScopes());
		m.put("leaveCampaigns", u.leaveCampaigns());
		m.put("positions", u.positions());
		m.put("fieldValues", u.fieldValues());
		return m;
	}

	// ================================================================== bulk save (Settings → Onboarding → Departments)

	/**
	 * Saves the whole departments list through the same rules as the API. Parents are saved before their children,
	 * every problem is collected with its row and column, and nothing is kept if any row is wrong.
	 */
	@Transactional
	public void bulkUpsert(List<Map<String, Object>> rows) {
		Map<UUID, Department> existing = new HashMap<>();
		repo.findByOrgId(currentUser.orgId()).forEach(d -> existing.put(d.getId(), d));
		Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
		rows.forEach(r -> byId.put(r.get("id").toString(), r));
		List<Map<String, Object>> ordered = new ArrayList<>();
		Set<String> visited = new HashSet<>();
		for (Map<String, Object> row : rows) {
			visit(row, byId, visited, ordered);
		}
		List<ErrorItem> errors = new ArrayList<>();
		for (Map<String, Object> row : ordered) {
			int index = rows.indexOf(row);
			UUID id = UUID.fromString(row.get("id").toString());
			Map<String, Object> body = toBody(row);
			try {
				if (existing.containsKey(id)) {
					applyRow(existing.get(id), body);
				}
				else {
					createWithId(id, body);
				}
			}
			catch (UnprocessableException ex) {
				ex.getErrors().forEach(e -> errors.add(ErrorItem.cell("departments", index,
						COLUMNS.getOrDefault(e.fieldKey(), e.fieldKey()), e.message())));
			}
			catch (ConflictException | BadRequestException | NotFoundException ex) {
				errors.add(ErrorItem.cell("departments", index, conflictColumn(ex.getMessage()), ex.getMessage()));
			}
		}
		if (!errors.isEmpty()) {
			throw new UnprocessableException(errors);
		}
	}

	/** Deletes the departments that are no longer in the list, each through the usage guard. */
	@Transactional
	public void bulkDelete(Set<UUID> keep) {
		DeptTree tree = new DeptTree(repo.findByOrgId(currentUser.orgId()));
		List<Department> doomed = tree.roots().stream().flatMap(r -> tree.descendants(r.getId()).stream())
				.map(tree::get).filter(d -> !keep.contains(d.getId())).toList();
		// deepest first, so a department and its sub-departments can go in one save
		doomed.stream().sorted((a, b) -> Integer.compare(tree.level(b.getId()), tree.level(a.getId())))
				.forEach(d -> delete(d.getId(), null));
	}

	private void visit(Map<String, Object> row, Map<String, Map<String, Object>> byId, Set<String> visited,
			List<Map<String, Object>> out) {
		String id = row.get("id").toString();
		if (!visited.add(id)) {
			return;
		}
		Object parent = row.get("parent");
		if (parent != null && byId.containsKey(parent.toString())) {
			visit(byId.get(parent.toString()), byId, visited, out);
		}
		out.add(row);
	}

	private static Map<String, Object> toBody(Map<String, Object> row) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("name", row.get("name"));
		body.put("code", row.get("code"));
		body.put("headEmployeeId", row.get("head"));
		body.put("parentId", row.get("parent"));
		body.put("costCentre", row.get("cc"));
		body.put("defaultLocationId", row.get("loc"));
		body.put("active", row.get("active") == null || Boolean.parseBoolean(row.get("active").toString()));
		return body;
	}

	private static String conflictColumn(String message) {
		String m = message.toLowerCase(Locale.ROOT);
		return m.contains("code") ? "code" : m.contains("parent") || m.contains("nest") || m.contains("cycle") ? "parent" : "name";
	}

	private void applyRow(Department d, Map<String, Object> body) {
		deferFlush();
		UUID oldParent = d.getParentId();
		UUID oldHead = d.getHeadEmployeeId();
		boolean wasActive = d.isActive();
		Input in = new Input(body);
		in.applyTo(d);
		List<String> warnings = new ArrayList<>();
		validate(d, in, false, warnings);
		throwIfInvalid(in);
		requireConflictFree(d, d.getId(), in, false);
		if (wasActive && !d.isActive()) {
			deactivationChecks(d, warnings);
		}
		if (!wasActive && d.isActive()) {
			activationChecks(d);
		}
		repo.saveAndFlush(d);
		syncTuples(d, oldParent, oldHead);
		events.publishEvent(new DepartmentUpdatedEvent(d.getId(), List.copyOf(in.touched)));
		if (!Objects.equals(oldParent, d.getParentId())) {
			events.publishEvent(new DepartmentMovedEvent(d.getId(), oldParent, d.getParentId()));
		}
		if (!Objects.equals(oldHead, d.getHeadEmployeeId())) {
			events.publishEvent(new DepartmentHeadChangedEvent(d.getId(), oldHead, d.getHeadEmployeeId()));
		}
		if (wasActive != d.isActive()) {
			events.publishEvent(new DepartmentActivationChangedEvent(d.getId(), d.isActive()));
		}
	}

	private void createWithId(UUID id, Map<String, Object> body) {
		Department d = new Department();
		d.setId(id);
		d.setOrgId(currentUser.orgId());
		Input in = new Input(body);
		in.applyTo(d);
		List<String> warnings = new ArrayList<>();
		validate(d, in, true, warnings);
		throwIfInvalid(in);
		requireConflictFree(d, null, in, true);
		repo.saveAndFlush(d);
		customFields.write("department", d.getId(), Map.of(), true);
		fga.write(FgaTuples.departmentOrg(d.getOrgId(), d.getId()));
		if (d.getParentId() != null) {
			fga.write(FgaTuples.departmentParent(d.getId(), d.getParentId()));
		}
		if (d.getHeadEmployeeId() != null) {
			fga.write(FgaTuples.departmentHead(d.getId(), d.getHeadEmployeeId()));
		}
		events.publishEvent(new DepartmentCreatedEvent(d.getId(), d.getParentId()));
	}

	// ================================================================== input and validation

	/** A request body applied to a department: which keys were present, and what went wrong reading them. */
	private static final class Input {

		final Map<String, Object> body;
		final Set<String> touched = new LinkedHashSet<>();
		final List<ErrorItem> errors = new ArrayList<>();

		Input(Map<String, Object> body) {
			this.body = body;
		}

		void applyTo(Department d) {
			for (Map.Entry<String, Object> e : body.entrySet()) {
				String k = e.getKey();
				Object v = e.getValue();
				boolean known = true;
				try {
					switch (k) {
						case "name" -> d.setName(text(v));
						case "code" -> d.setCode(upper(v));
						case "parentId" -> d.setParentId(uuid(v));
						case "headEmployeeId" -> d.setHeadEmployeeId(uuid(v));
						case "costCentre" -> d.setCostCentre(upper(v));
						case "defaultLocationId" -> d.setDefaultLocationId(uuid(v));
						case "active" -> d.setActive(v == null || bool(v));
						case "description" -> d.setDescription(text(v));
						case "sort" -> d.setSort(v == null ? 0 : v instanceof Number n ? n.intValue() : Integer.parseInt(v.toString().trim()));
						default -> known = false;
					}
				}
				catch (IllegalArgumentException ex) {
					errors.add(ErrorItem.field(k, "INVALID", "'" + k + "' is not valid: " + ex.getMessage()));
				}
				if (known) {
					touched.add(k);
				}
			}
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

	private void validate(Department d, Input in, boolean create, List<String> warnings) {
		List<ErrorItem> errors = in.errors;
		java.util.function.Predicate<String> any = key -> create || in.touched.contains(key);
		if (any.test("name")) {
			if (d.getName() == null) {
				errors.add(ErrorItem.field("name", "REQUIRED", "Every department needs a name"));
			}
			else if (d.getName().length() > 100) {
				errors.add(ErrorItem.field("name", "INVALID", "Name can be at most 100 characters"));
			}
		}
		if (any.test("code")) {
			if (d.getCode() == null) {
				if (rules.codeRequired()) {
					errors.add(ErrorItem.field("code", "REQUIRED", "Department code is required (Settings → Organization)"));
				}
			}
			else if (!CODE.matcher(d.getCode()).matches()) {
				errors.add(ErrorItem.field("code", "CODE_FORMAT", "Code must be 2–20 letters, digits or dashes"));
			}
		}
		if (d.getCostCentre() != null && any.test("costCentre") && !COST_CENTRE.matcher(d.getCostCentre()).matches()) {
			errors.add(ErrorItem.field("costCentre", "COST_CENTRE_FORMAT", "Cost centre must be 2–20 letters, digits or dashes"));
		}
		if (d.getDescription() != null && d.getDescription().length() > 500) {
			errors.add(ErrorItem.field("description", "INVALID", "Description can be at most 500 characters"));
		}
		if (d.getHeadEmployeeId() != null && any.test("headEmployeeId")) {
			String status = jdbc.sql("select status from employee where id = :id and org_id = :org")
					.param("id", d.getHeadEmployeeId()).param("org", d.getOrgId()).query(String.class).optional().orElse(null);
			if (status == null || "EXITED".equals(status)) {
				errors.add(ErrorItem.field("headEmployeeId", "HEAD_INVALID", "The head must be an active employee"));
			}
			else if ("ON_NOTICE".equals(status)) {
				warnings.add("Head is serving notice");
			}
		}
		if (d.getDefaultLocationId() != null && any.test("defaultLocationId")) {
			Location l = locationRepo.findByIdAndOrgId(d.getDefaultLocationId(), d.getOrgId()).orElse(null);
			if (l == null) {
				errors.add(ErrorItem.field("defaultLocationId", "INVALID", "That location does not exist"));
			}
			else if (!l.isActive()) {
				errors.add(ErrorItem.field("defaultLocationId", "LOCATION_INACTIVE", "That location is inactive"));
			}
		}
		if (d.getParentId() != null && (any.test("parentId") || in.touched.contains("active"))) {
			Department parent = repo.findByIdAndOrgId(d.getParentId(), d.getOrgId()).orElse(null);
			if (parent == null) {
				throw new NotFoundException("Parent department not found");
			}
			if (d.isActive() && !parent.isActive() && in.touched.contains("parentId")) {
				errors.add(ErrorItem.field("parentId", "PARENT_INACTIVE", "The parent department is inactive"));
			}
		}
		if (create && d.getHeadEmployeeId() == null) {
			warnings.add("No head set");
		}
	}

	private void throwIfInvalid(Input in) {
		if (!in.errors.isEmpty()) {
			throw new UnprocessableException(in.errors);
		}
	}

	/** Duplicates, loops and the nesting settings: these are conflicts with other data, not bad input. */
	private void requireConflictFree(Department d, UUID selfId, Input in, boolean create) {
		UUID org = d.getOrgId();
		if (create || in.touched.contains("name")) {
			boolean taken = selfId == null ? repo.existsByOrgIdAndNameIgnoreCase(org, d.getName())
					: repo.existsByOrgIdAndNameIgnoreCaseAndIdNot(org, d.getName(), selfId);
			if (taken) {
				throw new ConflictException("A department with this name already exists");
			}
		}
		if (d.getCode() != null && (create || in.touched.contains("code"))) {
			boolean taken = selfId == null ? repo.existsByOrgIdAndCodeIgnoreCase(org, d.getCode())
					: repo.existsByOrgIdAndCodeIgnoreCaseAndIdNot(org, d.getCode(), selfId);
			if (taken) {
				throw new ConflictException("A department with this code already exists");
			}
		}
		if (d.getParentId() != null && (create || in.touched.contains("parentId"))) {
			UUID self = selfId != null ? selfId : d.getId();
			if (self.equals(d.getParentId())) {
				throw new ConflictException("A department cannot be its own parent");
			}
			if (selfId != null && new DeptTree(repo.findByOrgId(org)).wouldCycle(selfId, d.getParentId())) {
				throw new ConflictException("Parent would create a cycle");
			}
			rules.checkParent(d.getParentId(), selfId);
		}
	}

	private Department find(UUID id) {
		return repo.findByIdAndOrgId(id, currentUser.orgId())
				.orElseThrow(() -> new NotFoundException("Department " + id + " not found"));
	}
}
