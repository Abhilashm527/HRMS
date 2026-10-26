package com.ivoyant.hrms.org.service;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.event.ProjectArchivedEvent;
import com.ivoyant.hrms.common.event.ProjectCreatedEvent;
import com.ivoyant.hrms.common.event.ProjectDeletedEvent;
import com.ivoyant.hrms.common.event.ProjectManagerChangedEvent;
import com.ivoyant.hrms.common.event.ProjectMembershipChangedEvent;
import com.ivoyant.hrms.common.event.ProjectStatusChangedEvent;
import com.ivoyant.hrms.common.event.ProjectUpdatedEvent;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.security.ModuleAccess;
import com.ivoyant.hrms.common.web.CodedConflictException;
import com.ivoyant.hrms.common.web.ConflictException;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.ForbiddenException;
import com.ivoyant.hrms.common.web.InUseException;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.common.web.StaleException;
import com.ivoyant.hrms.common.web.StatusException;
import com.ivoyant.hrms.common.web.UnprocessableException;
import com.ivoyant.hrms.org.domain.Project;
import com.ivoyant.hrms.org.domain.ProjectStatus;
import com.ivoyant.hrms.org.dto.ProjectDtos;
import com.ivoyant.hrms.org.repo.DepartmentRepository;
import com.ivoyant.hrms.org.repo.ProjectRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
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
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creating, changing, archiving and deleting projects, and who is on them. A project moves through a small set of
 * statuses; a person's total allocation on planned, active and on-hold projects can never exceed 100%.
 */
@Service
public class ProjectService {

	private static final String ENTITY = "Project";
	private static final Pattern CODE = Pattern.compile("^[A-Z0-9][A-Z0-9-]{1,9}$");
	private static final Set<String> ROLES = Set.of("LEAD", "CONTRIBUTOR", "REVIEWER", "ADVISOR");
	private static final Map<String, String> COLUMNS = Map.of("name", "name", "code", "code", "client", "client",
			"ownerEmployeeId", "owner", "status", "status", "active", "active");

	@PersistenceContext
	private EntityManager em;

	private final ProjectRepository repo;
	private final DepartmentRepository deptRepo;
	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final ModuleAccess access;
	private final People people;
	private final ProjectQueryService query;
	private final Audit audit;
	private final ApplicationEventPublisher events;

	public ProjectService(ProjectRepository repo, DepartmentRepository deptRepo, JdbcClient jdbc, CurrentUser currentUser,
			ModuleAccess access, People people, ProjectQueryService query, Audit audit, ApplicationEventPublisher events) {
		this.repo = repo;
		this.deptRepo = deptRepo;
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.access = access;
		this.people = people;
		this.query = query;
		this.audit = audit;
		this.events = events;
	}

	// ================================================================== create / update / status

	@Transactional
	public ProjectDtos.Node create(Map<String, Object> body) {
		deferFlush();
		Project p = new Project();
		p.setOrgId(currentUser.orgId());
		Input in = new Input(body);
		in.applyTo(p);
		if (!in.touched.contains("client") || p.getClient() == null) {
			p.setClient("Internal");
		}
		if (!in.touched.contains("billable")) {
			p.setBillable(!"Internal".equalsIgnoreCase(p.getClient()));
		}
		List<String> warnings = new ArrayList<>();
		validate(p, in, true, warnings);
		throwIfInvalid(in);
		List<Project> all = new ArrayList<>(repo.findByOrgId(p.getOrgId()));
		all.add(p);
		throwFirst(duplicates(all, List.of(p)));
		if (ProjectStatus.COMPLETED.name().equals(p.getStatus()) && p.getEndDate() == null) {
			p.setEndDate(LocalDate.now());
		}
		repo.saveAndFlush(p);
		ProjectDtos.Node out = query.get(p.getId()).withWarnings(warnings);
		audit.created("org", ENTITY, p.getId(), out);
		events.publishEvent(new ProjectCreatedEvent(p.getId()));
		return out;
	}

	@Transactional
	public ProjectDtos.Node update(UUID id, Map<String, Object> patch, String ifMatch) {
		deferFlush();
		Project p = find(id);
		if (ifMatch != null && !ifMatch.isBlank()
				&& !ifMatch.replace("W/", "").replace("\"", "").trim().equals(String.valueOf(p.getVersion()))) {
			throw new StaleException("Someone else changed this project. Reload and try again.");
		}
		ProjectDtos.Node before = query.get(id);
		UUID oldManager = p.getOwnerEmployeeId();
		ProjectStatus oldStatus = ProjectStatus.parse(p.getStatus());
		boolean wasActive = p.isActive();
		Input in = new Input(patch);
		in.applyTo(p);
		List<String> warnings = new ArrayList<>();
		validate(p, in, false, warnings);
		throwIfInvalid(in);
		throwFirst(duplicates(repo.findByOrgId(p.getOrgId()), List.of(p)));
		List<UUID> released = List.of();
		ProjectStatus now = ProjectStatus.parse(p.getStatus());
		if (now != oldStatus) {
			released = moveStatus(p, oldStatus, now, warnings);
		}
		if (wasActive && !p.isActive() && now != ProjectStatus.COMPLETED && now != ProjectStatus.PLANNED) {
			throw new ConflictException("Complete or cancel " + p.getName() + " first");
		}
		repo.saveAndFlush(p);
		ProjectDtos.Node after = query.get(id).withWarnings(warnings);
		audit.updated("org", ENTITY, id, before, after);
		events.publishEvent(new ProjectUpdatedEvent(id, List.copyOf(in.touched)));
		if (now != oldStatus) {
			events.publishEvent(new ProjectStatusChangedEvent(id, oldStatus.name(), now.name(), released));
		}
		if (!Objects.equals(oldManager, p.getOwnerEmployeeId())) {
			events.publishEvent(new ProjectManagerChangedEvent(id, oldManager, p.getOwnerEmployeeId()));
		}
		if (wasActive != p.isActive()) {
			events.publishEvent(new ProjectArchivedEvent(id, !p.isActive()));
		}
		return after;
	}

	/** The explicit status button: {@code {status, endDate?, releaseMembers?}}. */
	@Transactional
	public ProjectDtos.Node changeStatus(UUID id, Map<String, Object> body) {
		Map<String, Object> patch = new LinkedHashMap<>();
		patch.put("status", body.get("status"));
		if (body.get("endDate") != null) {
			patch.put("endDate", body.get("endDate"));
		}
		return update(id, patch, null);
	}

	/**
	 * Planned → Active or Completed · Active → On hold or Completed · On hold → Active or Completed · Completed → Active.
	 * Completing sets the end date if blank and ends every membership, freeing the allocation.
	 */
	private List<UUID> moveStatus(Project p, ProjectStatus from, ProjectStatus to, List<String> warnings) {
		if (from == null || !from.next().contains(to)) {
			throw new CodedConflictException("STATUS_TRANSITION", "A project can't go from "
					+ (from == null ? p.getStatus() : from.label()) + " to " + to.label());
		}
		if (to == ProjectStatus.ACTIVE && p.getOwnerEmployeeId() == null) {
			throw invalid("ownerEmployeeId", "MANAGER_INVALID", "A project needs a manager before it can be active");
		}
		if (to != ProjectStatus.COMPLETED) {
			return List.of();
		}
		if (p.getEndDate() == null) {
			p.setEndDate(LocalDate.now());
		}
		List<UUID> released = jdbc.sql("select employee_id from project_member where project_id = :p and until is null")
				.param("p", p.getId()).query(UUID.class).list();
		jdbc.sql("update project_member set until = greatest(since, :d) where project_id = :p and until is null")
				.param("d", p.getEndDate()).param("p", p.getId()).update();
		released.forEach(e -> events.publishEvent(new ProjectMembershipChangedEvent(p.getId(), e, null, 0, 0, false)));
		if (!released.isEmpty()) {
			warnings.add(released.size() + (released.size() == 1 ? " person was" : " people were") + " released from " + p.getName());
		}
		return released;
	}

	private void deferFlush() {
		em.setFlushMode(FlushModeType.COMMIT);
	}

	// ================================================================== rules that depend on the other projects

	private record Problem(Project on, String field, String message) {
	}

	/** Name and code are unique ignoring case. */
	private List<Problem> duplicates(List<Project> all, List<Project> changed) {
		List<Problem> out = new ArrayList<>();
		for (Project e : changed) {
			String name = e.getName() == null ? "" : e.getName().toLowerCase(Locale.ROOT);
			if (all.stream().anyMatch(o -> o != e && !o.getId().equals(e.getId()) && o.getName() != null
					&& o.getName().toLowerCase(Locale.ROOT).equals(name))) {
				out.add(new Problem(e, "name", "A project with this name already exists"));
			}
			if (e.getCode() != null && all.stream().anyMatch(o -> o != e && !o.getId().equals(e.getId()) && e.getCode().equalsIgnoreCase(o.getCode()))) {
				out.add(new Problem(e, "code", "A project with this code already exists"));
			}
		}
		return out;
	}

	private void throwFirst(List<Problem> problems) {
		if (!problems.isEmpty()) {
			throw new ConflictException(problems.get(0).message());
		}
	}

	// ================================================================== delete

	/** Hard delete, only for a project that never had a member (a typo row); everything else is archived instead. */
	@Transactional
	public void delete(UUID id) {
		Project p = find(id);
		ProjectDtos.Node before = query.get(id);
		ProjectDtos.Usage usage = query.usage(id);
		if (usage.blocksDelete()) {
			throw new InUseException(usage.message() != null ? usage.message() : "This project is still used. Archive it instead.",
					Map.of("currentMembers", usage.currentMembers(), "pastMembers", usage.pastMembers()));
		}
		repo.delete(p);
		repo.flush();
		audit.deleted("org", ENTITY, id, before);
		events.publishEvent(new ProjectDeletedEvent(id));
	}

	// ================================================================== membership and allocation

	private void requireMemberAccess(Project p) {
		boolean manager = currentUser.employeeId() != null && currentUser.employeeId().equals(p.getOwnerEmployeeId());
		if (!manager && !access.has("org", "EDIT")) {
			throw new StatusException(HttpStatus.FORBIDDEN, "NOT_PROJECT_MANAGER", "Only the project manager or HR can change members");
		}
	}

	/**
	 * {@code upsert}: {@code [{employeeId, role, allocation}]}, {@code remove}: employee ids, {@code effectiveDate}. Each person's
	 * total after the change must stay within 100%.
	 */
	@Transactional
	@SuppressWarnings("unchecked")
	public List<ProjectDtos.Member> setMembers(UUID projectId, Map<String, Object> body) {
		return setMembers(projectId, body, null);
	}

	@Transactional
	@SuppressWarnings("unchecked")
	public List<ProjectDtos.Member> setMembers(UUID projectId, Map<String, Object> body, String ifMatch) {
		Project p = find(projectId);
		requireMemberAccess(p);
		if (ifMatch != null && !ifMatch.isBlank() && !ifMatch.replace("W/", "").replace("\"", "").trim().equals(String.valueOf(p.getVersion()))) {
			throw new CodedConflictException("VERSION_CONFLICT", p.getName() + " was changed by someone else. Reload it and try again.");
		}
		LocalDate effective = date(body.get("effectiveDate"));
		if (body.get("remove") instanceof List<?> remove) {
			for (Object o : remove) {
				endMember(p, uuid(o), effective);
			}
		}
		if (body.get("upsert") instanceof List<?> upsert) {
			for (Object o : upsert) {
				Map<String, Object> row = (Map<String, Object>) o;
				UUID employee = uuid(row.get("employeeId"));
				Map<String, Object> current = jdbc.sql("select role, allocation from project_member where project_id = :p and employee_id = :e and until is null")
						.param("p", p.getId()).param("e", employee).query((rs, i) -> Map.<String, Object>of("role", rs.getString(1), "allocation", rs.getInt(2)))
						.optional().orElse(null);
				String role = row.get("role") != null ? row.get("role").toString().trim().toUpperCase(Locale.ROOT)
						: current != null ? (String) current.get("role") : "CONTRIBUTOR";
				int allocation = row.get("allocation") != null ? intOf(row.get("allocation"))
						: current != null ? (Integer) current.get("allocation") : defaultAllocation(employee, p);
				upsertMember(p, employee, role, allocation, row.get("note") == null ? null : row.get("note").toString(), effective);
			}
		}
		return query.members(projectId, false);
	}

	private void upsertMember(Project p, UUID employee, String role, int allocation, String note, LocalDate since) {
		ProjectStatus status = ProjectStatus.parse(p.getStatus());
		if (status == ProjectStatus.COMPLETED) {
			throw invalid(null, "PROJECT_CLOSED", p.getName() + " is completed. Reopen it to change people");
		}
		if (!p.isActive()) {
			throw invalid(null, "PROJECT_CLOSED", p.getName() + " is archived");
		}
		if (!ROLES.contains(role)) {
			throw invalid("role", "INVALID", "Role must be one of Lead, Contributor, Reviewer, Advisor");
		}
		if (allocation < 0 || allocation > 100) {
			throw invalid("allocation", "ALLOCATION_RANGE", "Allocation must be between 0 and 100");
		}
		People.Emp e = people.find(employee);
		if (e != null && e.exited()) {
			throw invalid("employeeId", "EMPLOYEE_EXITED", e.name() + " has left");
		}
		if (e == null || !e.live()) {
			throw invalid("employeeId", "INVALID", "Only current employees can be added to a project");
		}
		Integer oldAllocation = jdbc.sql("select allocation from project_member where project_id = :p and employee_id = :e and until is null")
				.param("p", p.getId()).param("e", employee).query(Integer.class).optional().orElse(null);
		if ("LEAD".equals(role)) {
			String lead = jdbc.sql("select e.display_name from project_member m join employee e on e.id = m.employee_id "
					+ "where m.project_id = :p and m.role = 'LEAD' and m.until is null and m.employee_id <> :e").param("p", p.getId())
					.param("e", employee).query(String.class).optional().orElse(null);
			if (lead != null) {
				throw new ConflictException(p.getName() + " already has a lead: " + lead);
			}
		}
		if (oldAllocation == null) {
			jdbc.sql("insert into project_member (org_id, project_id, employee_id, role, allocation, since, note, created_by) "
					+ "values (:org, :p, :e, :r, :a, :d, :n, :by)").param("org", p.getOrgId()).param("p", p.getId()).param("e", employee)
					.param("r", role).param("a", allocation).param("d", since).param("n", note, java.sql.Types.VARCHAR)
					.param("by", currentUser.employeeId(), java.sql.Types.OTHER).update();
		}
		else {
			jdbc.sql("update project_member set role = :r, allocation = :a, note = coalesce(:n, note) where project_id = :p and employee_id = :e and until is null")
					.param("r", role).param("a", allocation).param("n", note, java.sql.Types.VARCHAR).param("p", p.getId())
					.param("e", employee).update();
		}
		events.publishEvent(new ProjectMembershipChangedEvent(p.getId(), employee, role, oldAllocation == null ? 0 : oldAllocation,
				allocation, oldAllocation == null));
	}

	/** 100 minus what the person already gives to live projects, or 20 when nothing is left. */
	private int defaultAllocation(UUID employee, Project p) {
		Integer total = jdbc.sql("select coalesce(sum(m.allocation), 0) from project_member m join project pr on pr.id = m.project_id where m.employee_id = :e "
				+ "and m.until is null and pr.status in " + ProjectQueryService.LIVE + " and pr.id <> :p").param("e", employee).param("p", p.getId()).query(Integer.class).single();
		return total < 100 ? 100 - total : 20;
	}

	/** (No longer used when saving: going over 100% is a warning shown on the member, not an error.) A person's other live allocation plus this one must not pass 100%; the message lists where the time goes. */
	private void requireWithinCap(People.Emp e, Project p, int allocation) {
		if (!ProjectStatus.parse(p.getStatus()).counts()) {
			return;
		}
		List<Object[]> others = jdbc.sql("select pr.name, m.allocation from project_member m join project pr on pr.id = m.project_id "
				+ "where m.employee_id = :e and m.until is null and pr.status in " + ProjectQueryService.LIVE + " and pr.id <> :p order by pr.name")
				.param("e", e.id()).param("p", p.getId())
				.query((rs, i) -> new Object[] {rs.getString(1), rs.getInt(2)}).list();
		int total = allocation + others.stream().mapToInt(o -> (Integer) o[1]).sum();
		if (total > 100) {
			StringBuilder parts = new StringBuilder();
			others.forEach(o -> parts.append(o[0]).append(' ').append(o[1]).append("%, "));
			throw new CodedConflictException("OVER_ALLOCATED", e.name() + " would be at " + total + "% (" + parts + "+ "
					+ allocation + "% on " + p.getName() + ").");
		}
	}

	private void endMember(Project p, UUID employee, LocalDate until) {
		Integer allocation = jdbc.sql("select allocation from project_member where project_id = :p and employee_id = :e and until is null")
				.param("p", p.getId()).param("e", employee).query(Integer.class).optional().orElse(null);
		if (allocation == null) {
			throw new NotFoundException("That person is not on " + p.getName());
		}
		jdbc.sql("update project_member set until = greatest(since, :d) where project_id = :p and employee_id = :e and until is null")
				.param("d", until).param("p", p.getId()).param("e", employee).update();
		events.publishEvent(new ProjectMembershipChangedEvent(p.getId(), employee, null, allocation, 0, false));
	}

	/** Used when a person leaves: every project membership ends on the given date. */
	void releaseEmployee(UUID employee, LocalDate until) {
		for (UUID projectId : jdbc.sql("select project_id from project_member where employee_id = :e and until is null")
				.param("e", employee).query(UUID.class).list()) {
			repo.findById(projectId).ifPresent(p -> endMember(p, employee, until));
		}
	}

	/** Replaces a person's current projects with {@code desired} (each {@code {projectId, role, allocation}}); the whole set is checked first. */
	@Transactional
	public void applyEmployeeProjects(UUID employee, List<Map<String, Object>> desired, LocalDate effective) {
		People.Emp e = people.find(employee);
		if (e == null || !e.live()) {
			throw invalid("employeeId", "INVALID", "Only current employees can be on projects");
		}
		Map<UUID, Project> projects = new LinkedHashMap<>();
		Map<UUID, Map<String, Object>> rows = new LinkedHashMap<>();
		int total = 0;
		for (Map<String, Object> row : desired) {
			UUID id = uuid(row.get("projectId"));
			Project p = find(id);
			if (projects.put(id, p) != null) {
				throw invalid("projects", "INVALID", "A project is listed twice");
			}
			rows.put(id, row);
			int allocation = row.get("allocation") == null ? 0 : intOf(row.get("allocation"));
			if (allocation < 0 || allocation > 100) {
				throw invalid("allocation", "ALLOCATION_RANGE", "Allocation must be between 0 and 100");
			}
			if (ProjectStatus.parse(p.getStatus()).counts()) {
				total += allocation;
			}
		}
		if (total > 100) {
			throw new CodedConflictException("OVER_ALLOCATED", e.name() + " would be at " + total + "% across these projects");
		}
		Set<UUID> current = new LinkedHashSet<>(jdbc.sql("select project_id from project_member where employee_id = :e and until is null")
				.param("e", employee).query(UUID.class).list());
		for (UUID id : current) {
			if (!projects.containsKey(id)) {
				endMember(find(id), employee, effective);
			}
		}
		for (Map.Entry<UUID, Project> en : projects.entrySet()) {
			Map<String, Object> row = rows.get(en.getKey());
			String role = row.get("role") == null ? "CONTRIBUTOR" : row.get("role").toString().trim().toUpperCase(Locale.ROOT);
			int allocation = row.get("allocation") == null ? 0 : intOf(row.get("allocation"));
			upsertMemberInSet(en.getValue(), employee, role, allocation, effective, current.contains(en.getKey()));
		}
	}

	/** Like {@link #upsertMember} but the cap was already checked for the whole set. */
	private void upsertMemberInSet(Project p, UUID employee, String role, int allocation, LocalDate since, boolean existing) {
		if (!existing && ProjectStatus.parse(p.getStatus()) == ProjectStatus.COMPLETED) {
			throw new CodedConflictException("PROJECT_COMPLETED", p.getName() + " is completed. Reopen it to add people");
		}
		if (!existing && !p.isActive()) {
			throw new ConflictException(p.getName() + " is archived");
		}
		if (!ROLES.contains(role)) {
			throw invalid("role", "INVALID", "Role must be one of Lead, Contributor, Reviewer, Advisor");
		}
		if ("LEAD".equals(role)) {
			String lead = jdbc.sql("select e.display_name from project_member m join employee e on e.id = m.employee_id "
					+ "where m.project_id = :p and m.role = 'LEAD' and m.until is null and m.employee_id <> :e").param("p", p.getId())
					.param("e", employee).query(String.class).optional().orElse(null);
			if (lead != null) {
				throw new ConflictException(p.getName() + " already has a lead: " + lead);
			}
		}
		if (existing) {
			jdbc.sql("update project_member set role = :r, allocation = :a where project_id = :p and employee_id = :e and until is null")
					.param("r", role).param("a", allocation).param("p", p.getId()).param("e", employee).update();
		}
		else {
			jdbc.sql("insert into project_member (org_id, project_id, employee_id, role, allocation, since, created_by) values (:org, :p, :e, :r, :a, :d, :by)")
					.param("org", p.getOrgId()).param("p", p.getId()).param("e", employee).param("r", role).param("a", allocation)
					.param("d", since).param("by", currentUser.employeeId(), java.sql.Types.OTHER).update();
		}
		events.publishEvent(new ProjectMembershipChangedEvent(p.getId(), employee, role, 0, allocation, !existing));
	}

	// ================================================================== bulk save (Settings → Onboarding → Projects)

	/** Saves the whole projects list through the same rules; status labels ("On hold") are accepted. */
	@Transactional
	public void bulkUpsert(List<Map<String, Object>> rows) {
		deferFlush();
		UUID org = currentUser.orgId();
		Map<UUID, Project> existing = new HashMap<>();
		repo.findByOrgId(org).forEach(p -> existing.put(p.getId(), p));
		Map<UUID, Integer> indexOf = new HashMap<>();
		Map<UUID, ProjectStatus> oldStatus = new HashMap<>();
		Map<UUID, UUID> oldManager = new HashMap<>();
		Map<UUID, Boolean> wasActive = new HashMap<>();
		List<Project> changed = new ArrayList<>();
		List<Project> created = new ArrayList<>();
		List<ErrorItem> errors = new ArrayList<>();
		for (int i = 0; i < rows.size(); i++) {
			Map<String, Object> row = rows.get(i);
			UUID id = UUID.fromString(row.get("id").toString());
			boolean isNew = !existing.containsKey(id);
			Project p = isNew ? new Project() : existing.get(id);
			if (isNew) {
				p.setId(id);
				p.setOrgId(org);
			}
			else {
				oldStatus.put(id, ProjectStatus.parse(p.getStatus()));
				oldManager.put(id, p.getOwnerEmployeeId());
				wasActive.put(id, p.isActive());
			}
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("name", row.get("name"));
			body.put("code", row.get("code"));
			body.put("client", row.get("client"));
			body.put("ownerEmployeeId", row.get("owner"));
			body.put("status", row.get("status") == null ? "ACTIVE" : row.get("status"));
			body.put("active", row.get("active") == null || Boolean.parseBoolean(row.get("active").toString()));
			Input in = new Input(body);
			in.applyTo(p);
			if (p.getClient() == null) {
				p.setClient("Internal");
			}
			if (isNew) {
				p.setBillable(!"Internal".equalsIgnoreCase(p.getClient()));
			}
			validate(p, in, true, new ArrayList<>());
			final int index = i;
			in.errors.forEach(e -> errors.add(ErrorItem.cell("projects", index, COLUMNS.getOrDefault(e.fieldKey(), e.fieldKey()), e.message())));
			indexOf.put(id, i);
			changed.add(p);
			if (isNew) {
				created.add(p);
			}
		}
		Map<UUID, List<UUID>> released = new HashMap<>();
		if (errors.isEmpty()) {
			List<Project> all = new ArrayList<>(existing.values());
			all.addAll(created);
			for (Problem pr : duplicates(all, changed)) {
				errors.add(ErrorItem.cell("projects", indexOf.get(pr.on().getId()), COLUMNS.getOrDefault(pr.field(), pr.field()), pr.message()));
			}
			for (Project p : changed) {
				if (!existing.containsKey(p.getId())) {
					continue;
				}
				ProjectStatus now = ProjectStatus.parse(p.getStatus());
				try {
					if (now != oldStatus.get(p.getId())) {
						released.put(p.getId(), moveStatus(p, oldStatus.get(p.getId()), now, new ArrayList<>()));
					}
					if (wasActive.get(p.getId()) && !p.isActive() && now != ProjectStatus.COMPLETED && now != ProjectStatus.PLANNED) {
						throw new ConflictException("Complete or cancel " + p.getName() + " first");
					}
				}
				catch (ConflictException | UnprocessableException ex) {
					errors.add(ErrorItem.cell("projects", indexOf.get(p.getId()), ex instanceof UnprocessableException ? "owner" : "status", ex.getMessage()));
				}
			}
		}
		if (!errors.isEmpty()) {
			throw new UnprocessableException(errors);
		}
		created.forEach(repo::saveAndFlush);
		repo.flush();
		for (Project p : changed) {
			if (!existing.containsKey(p.getId())) {
				events.publishEvent(new ProjectCreatedEvent(p.getId()));
				continue;
			}
			events.publishEvent(new ProjectUpdatedEvent(p.getId(), List.of("list")));
			ProjectStatus now = ProjectStatus.parse(p.getStatus());
			if (now != oldStatus.get(p.getId())) {
				events.publishEvent(new ProjectStatusChangedEvent(p.getId(), oldStatus.get(p.getId()).name(), now.name(),
						released.getOrDefault(p.getId(), List.of())));
			}
			if (!Objects.equals(oldManager.get(p.getId()), p.getOwnerEmployeeId())) {
				events.publishEvent(new ProjectManagerChangedEvent(p.getId(), oldManager.get(p.getId()), p.getOwnerEmployeeId()));
			}
			if (wasActive.get(p.getId()) != p.isActive()) {
				events.publishEvent(new ProjectArchivedEvent(p.getId(), !p.isActive()));
			}
		}
	}

	/** Deletes the projects that are no longer in the list, each through the usage guard. */
	@Transactional
	public void bulkDelete(Set<UUID> keep) {
		repo.findByOrgId(currentUser.orgId()).stream().filter(p -> !keep.contains(p.getId())).forEach(p -> delete(p.getId()));
	}

	// ================================================================== input and validation

	private static final class Input {

		final Map<String, Object> body;
		final Set<String> touched = new LinkedHashSet<>();
		final List<ErrorItem> errors = new ArrayList<>();

		Input(Map<String, Object> body) {
			this.body = body;
		}

		void applyTo(Project p) {
			for (Map.Entry<String, Object> e : body.entrySet()) {
				String k = e.getKey();
				Object v = e.getValue();
				boolean known = true;
				try {
					switch (k) {
						case "name" -> p.setName(text(v) == null ? null : text(v).replaceAll("\\s+", " "));
						case "code" -> p.setCode(text(v) == null ? null : text(v).toUpperCase(Locale.ROOT));
						case "client" -> p.setClient(text(v));
						case "ownerEmployeeId" -> p.setOwnerEmployeeId(uuid(v));
						case "status" -> {
							ProjectStatus s = ProjectStatus.parse(text(v));
							if (s == null) {
								errors.add(ErrorItem.field("status", "STATUS_INVALID", "Status must be Planned, Active, On hold or Completed"));
							}
							else {
								p.setStatus(s.name());
							}
						}
						case "startDate" -> p.setStartDate(text(v) == null ? null : LocalDate.parse(text(v)));
						case "endDate" -> p.setEndDate(text(v) == null ? null : LocalDate.parse(text(v)));
						case "departmentId" -> p.setDepartmentId(uuid(v));
						case "billable" -> p.setBillable(v != null && bool(v));
						case "description" -> p.setDescription(text(v));
						case "active" -> p.setActive(v == null || bool(v));
						default -> known = false;
					}
				}
				catch (IllegalArgumentException | java.time.format.DateTimeParseException ex) {
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

	private void validate(Project p, Input in, boolean create, List<String> warnings) {
		List<ErrorItem> errors = in.errors;
		java.util.function.Predicate<String> any = key -> create || in.touched.contains(key);
		if (any.test("name")) {
			if (p.getName() == null) {
				errors.add(ErrorItem.field("name", "REQUIRED", "Every project needs a name"));
			}
			else if (p.getName().length() < 2 || p.getName().length() > 100) {
				errors.add(ErrorItem.field("name", "NAME_LENGTH", "Name must be 2–100 characters"));
			}
		}
		if (any.test("code")) {
			if (p.getCode() == null) {
				errors.add(ErrorItem.field("code", "REQUIRED", "Every project needs a code"));
			}
			else if (!CODE.matcher(p.getCode()).matches()) {
				errors.add(ErrorItem.field("code", "CODE_FORMAT", "Code must be 2–10 letters, digits or dashes"));
			}
		}
		if (p.getClient() != null && p.getClient().length() > 100) {
			errors.add(ErrorItem.field("client", "INVALID", "Client can be at most 100 characters"));
		}
		if (p.getStartDate() != null && p.getEndDate() != null && p.getEndDate().isBefore(p.getStartDate())) {
			errors.add(ErrorItem.field("endDate", "DATES_ORDER", "The end date must not be before the start date"));
		}
		ProjectStatus status = ProjectStatus.parse(p.getStatus());
		if (p.getOwnerEmployeeId() != null && (any.test("ownerEmployeeId"))) {
			People.Emp owner = people.find(p.getOwnerEmployeeId());
			if (owner == null || !owner.live()) {
				errors.add(ErrorItem.field("ownerEmployeeId", "MANAGER_INVALID", "The project manager must be an active employee"));
			}
			else if ("ON_NOTICE".equals(owner.status())) {
				warnings.add(owner.name() + " is serving notice");
			}
		}
		if (p.getOwnerEmployeeId() == null && status != null && status != ProjectStatus.PLANNED && any.test("ownerEmployeeId") && create
				&& errors.stream().noneMatch(e -> "status".equals(e.fieldKey()))) {
			errors.add(ErrorItem.field("ownerEmployeeId", "MANAGER_INVALID", "A project needs a manager unless it is only planned"));
		}
		if (p.getDepartmentId() != null && any.test("departmentId") && deptRepo.findByIdAndOrgId(p.getDepartmentId(), p.getOrgId()).isEmpty()) {
			errors.add(ErrorItem.field("departmentId", "INVALID", "That department does not exist"));
		}
	}

	private void throwIfInvalid(Input in) {
		if (!in.errors.isEmpty()) {
			throw new UnprocessableException(in.errors);
		}
	}

	private static UnprocessableException invalid(String field, String code, String message) {
		return new UnprocessableException(List.of(ErrorItem.field(field, code, message)));
	}

	private static LocalDate date(Object v) {
		return v == null || v.toString().isBlank() ? LocalDate.now() : LocalDate.parse(v.toString());
	}

	private static int intOf(Object v) {
		try {
			return v instanceof Number n ? n.intValue() : Integer.parseInt(v.toString().trim());
		}
		catch (NumberFormatException ex) {
			throw invalid("allocation", "ALLOCATION_RANGE", "Allocation must be a whole number between 0 and 100");
		}
	}

	private static UUID uuid(Object v) {
		try {
			return UUID.fromString(String.valueOf(v));
		}
		catch (IllegalArgumentException ex) {
			throw invalid("id", "INVALID", "'" + v + "' is not a valid id");
		}
	}

	private Project find(UUID id) {
		return repo.findByIdAndOrgId(id, currentUser.orgId()).orElseThrow(() -> new NotFoundException("Project " + id + " not found"));
	}
}
