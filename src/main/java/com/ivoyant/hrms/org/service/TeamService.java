package com.ivoyant.hrms.org.service;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.event.TeamActivationChangedEvent;
import com.ivoyant.hrms.common.event.TeamCreatedEvent;
import com.ivoyant.hrms.common.event.TeamDeletedEvent;
import com.ivoyant.hrms.common.event.TeamLeadChangedEvent;
import com.ivoyant.hrms.common.event.TeamMembershipChangedEvent;
import com.ivoyant.hrms.common.event.TeamUpdatedEvent;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.ConfirmRequiredException;
import com.ivoyant.hrms.common.web.ConflictException;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.InUseException;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.common.web.StaleException;
import com.ivoyant.hrms.common.web.UnprocessableException;
import com.ivoyant.hrms.org.domain.Department;
import com.ivoyant.hrms.org.domain.Team;
import com.ivoyant.hrms.org.dto.TeamDtos;
import com.ivoyant.hrms.org.repo.DepartmentRepository;
import com.ivoyant.hrms.org.repo.TeamRepository;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Teams and who is in them. A team is functional and lives in one department; people can be in up to five teams inside
 * their own department tree, with one primary. The lead is always a member and is the default manager suggested for new
 * hires. Membership is dated, so history stays.
 */
@Service
public class TeamService {

	private static final String ENTITY = "Team";
	private static final int MAX_TEAMS = 5;
	private static final Map<String, String> COLUMNS = Map.of("name", "name", "departmentId", "dept", "leadEmployeeId", "lead",
			"active", "active");

	@PersistenceContext
	private EntityManager em;

	private final TeamRepository repo;
	private final DepartmentRepository deptRepo;
	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final People people;
	private final TeamQueryService query;
	private final Audit audit;
	private final ApplicationEventPublisher events;

	public TeamService(TeamRepository repo, DepartmentRepository deptRepo, JdbcClient jdbc, CurrentUser currentUser,
			People people, TeamQueryService query, Audit audit, ApplicationEventPublisher events) {
		this.repo = repo;
		this.deptRepo = deptRepo;
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.people = people;
		this.query = query;
		this.audit = audit;
		this.events = events;
	}

	// ================================================================== create / update

	@Transactional
	public TeamDtos.Node create(Map<String, Object> body) {
		deferFlush();
		Team t = new Team();
		t.setOrgId(currentUser.orgId());
		Input in = new Input(body);
		in.applyTo(t);
		List<String> warnings = new ArrayList<>(in.warnings);
		DeptTree depts = tree();
		validate(t, in, true, depts, warnings);
		throwIfInvalid(in);
		List<Team> all = new ArrayList<>(repo.findByOrgId(t.getOrgId()));
		all.add(t);
		throwFirst(duplicates(all, List.of(t), depts));
		repo.saveAndFlush(t);
		if (t.getLeadEmployeeId() != null) {
			ensureMember(t, t.getLeadEmployeeId(), LocalDate.now(), depts);
		}
		TeamDtos.Node out = query.get(t.getId()).withWarnings(warnings);
		audit.created("org", ENTITY, t.getId(), out);
		events.publishEvent(new TeamCreatedEvent(t.getId()));
		if (t.getLeadEmployeeId() != null) {
			events.publishEvent(new TeamLeadChangedEvent(t.getId(), null, t.getLeadEmployeeId()));
		}
		return out;
	}

	@Transactional
	public TeamDtos.Node update(UUID id, Map<String, Object> patch, String ifMatch) {
		deferFlush();
		boolean confirm = Confirmations.requested(patch);
		patch = new LinkedHashMap<>(patch);
		patch.remove("confirm");
		Team t = find(id);
		if (ifMatch != null && !ifMatch.isBlank()
				&& !ifMatch.replace("W/", "").replace("\"", "").trim().equals(String.valueOf(t.getVersion()))) {
			throw new StaleException("Someone else changed this team. Reload and try again.");
		}
		TeamDtos.Node before = query.get(id);
		UUID oldLead = t.getLeadEmployeeId();
		UUID oldDept = t.getDepartmentId();
		boolean wasActive = t.isActive();
		Input in = new Input(patch);
		in.applyTo(t);
		List<String> warnings = new ArrayList<>(in.warnings);
		DeptTree depts = tree();
		validate(t, in, false, depts, warnings);
		throwIfInvalid(in);
		throwFirst(duplicates(repo.findByOrgId(t.getOrgId()), List.of(t), depts));
		if (!Objects.equals(oldDept, t.getDepartmentId()) && t.getDepartmentId() != null) {
			List<String> outside = membersOutside(id, depts.descendants(t.getDepartmentId()));
			if (!outside.isEmpty()) {
				throw new InUseException(outside.size() + (outside.size() == 1 ? " member is outside " : " members are outside ")
						+ depts.get(t.getDepartmentId()).getName() + ". Move them first.", Map.of("members", outside));
			}
		}
		if (!wasActive && t.isActive()) {
			requireDepartmentActive(t, depts);
		}
		long members = currentMembers(id);
		if (wasActive && !t.isActive() && members > 0) {
			if (!confirm) {
				throw new ConfirmRequiredException(members + (members == 1 ? " person is" : " people are") + " in " + t.getName()
						+ ". Deactivating ends their membership today.", names(id));
			}
			warnings.add(members + (members == 1 ? " membership ended" : " memberships ended"));
		}
		repo.saveAndFlush(t);
		if (wasActive && !t.isActive()) {
			endAll(id, LocalDate.now());
		}
		if (t.isActive() && t.getLeadEmployeeId() != null && !Objects.equals(oldLead, t.getLeadEmployeeId())) {
			ensureMember(t, t.getLeadEmployeeId(), LocalDate.now(), depts);
		}
		TeamDtos.Node after = query.get(id).withWarnings(warnings);
		audit.updated("org", ENTITY, id, before, after);
		events.publishEvent(new TeamUpdatedEvent(id, List.copyOf(in.touched)));
		if (!Objects.equals(oldLead, t.getLeadEmployeeId())) {
			events.publishEvent(new TeamLeadChangedEvent(id, oldLead, t.getLeadEmployeeId()));
		}
		if (wasActive != t.isActive()) {
			events.publishEvent(new TeamActivationChangedEvent(id, t.isActive()));
		}
		return after;
	}

	private void deferFlush() {
		em.setFlushMode(FlushModeType.COMMIT);
	}

	private DeptTree tree() {
		return new DeptTree(deptRepo.findByOrgId(currentUser.orgId()));
	}

	private void requireDepartmentActive(Team t, DeptTree depts) {
		Department d = t.getDepartmentId() == null ? null : depts.get(t.getDepartmentId());
		if (d != null && !d.isActive()) {
			throw new ConflictException("Activate " + d.getName() + " first");
		}
	}

	private long currentMembers(UUID teamId) {
		return jdbc.sql("select count(*) from team_member where team_id = :t and until is null").param("t", teamId)
				.query(Long.class).single();
	}

	private List<String> names(UUID teamId) {
		return jdbc.sql("select e.display_name from team_member m join employee e on e.id = m.employee_id "
				+ "where m.team_id = :t and m.until is null order by e.display_name limit 10").param("t", teamId)
				.query(String.class).list();
	}

	private List<String> membersOutside(UUID teamId, Set<UUID> within) {
		return jdbc.sql("select e.display_name, e.department_id from team_member m join employee e on e.id = m.employee_id "
				+ "where m.team_id = :t and m.until is null").param("t", teamId)
				.query((rs, i) -> new Object[] {rs.getString(1), rs.getObject(2, UUID.class)}).list().stream()
				.filter(r -> r[1] == null || !within.contains((UUID) r[1])).map(r -> (String) r[0]).toList();
	}

	// ================================================================== rules that depend on the other teams

	private record Problem(Team on, String field, String message) {
	}

	/** A team name is unique inside its department, ignoring case. */
	private List<Problem> duplicates(List<Team> all, List<Team> changed, DeptTree depts) {
		List<Problem> out = new ArrayList<>();
		for (Team e : changed) {
			String name = e.getName() == null ? "" : e.getName().toLowerCase(Locale.ROOT);
			if (all.stream().anyMatch(o -> o != e && !o.getId().equals(e.getId()) && Objects.equals(o.getDepartmentId(), e.getDepartmentId())
					&& o.getName() != null && o.getName().toLowerCase(Locale.ROOT).equals(name))) {
				Department d = e.getDepartmentId() == null ? null : depts.get(e.getDepartmentId());
				out.add(new Problem(e, "name", "A team with this name already exists" + (d == null ? "" : " in " + d.getName())));
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

	/** Hard delete, only for a team that never had members; {@code reassignTo} first moves the current members to that team. */
	@Transactional
	public void delete(UUID id, UUID reassignTo) {
		Team t = find(id);
		TeamDtos.Node before = query.get(id);
		if (reassignTo != null) {
			reassign(t, reassignTo);
		}
		TeamDtos.Usage usage = query.usage(id);
		if (usage.blocksDelete()) {
			throw new InUseException(usage.message() != null ? usage.message() : "This team is still used. Mark it inactive instead.",
					Map.of("currentMembers", usage.currentMembers(), "pastMembers", usage.pastMembers()));
		}
		repo.delete(t);
		repo.flush();
		audit.deleted("org", ENTITY, id, before);
		events.publishEvent(new TeamDeletedEvent(id, reassignTo));
	}

	private void reassign(Team from, UUID targetId) {
		Team target = find(targetId);
		if (targetId.equals(from.getId())) {
			throw new ConflictException("Choose a different team to move everyone to");
		}
		if (!target.isActive()) {
			throw new ConflictException(target.getName() + " is inactive. Choose an active team");
		}
		DeptTree depts = tree();
		Set<UUID> within = target.getDepartmentId() == null ? null : depts.descendants(target.getDepartmentId());
		List<String> misfits = jdbc.sql("select e.display_name, e.department_id from team_member m join employee e on e.id = m.employee_id "
				+ "where m.team_id = :t and m.until is null").param("t", from.getId())
				.query((rs, i) -> new Object[] {rs.getString(1), rs.getObject(2, UUID.class)}).list().stream()
				.filter(r -> within != null && (r[1] == null || !within.contains((UUID) r[1]))).map(r -> (String) r[0]).toList();
		if (!misfits.isEmpty()) {
			throw new ConflictException(target.getName() + " isn't valid for the department of " + misfits.size()
					+ (misfits.size() == 1 ? " member" : " members"), misfits);
		}
		List<UUID> members = jdbc.sql("select employee_id from team_member where team_id = :t and until is null")
				.param("t", from.getId()).query(UUID.class).list();
		for (UUID employee : members) {
			endMember(from.getId(), employee, LocalDate.now(), false);
			boolean already = jdbc.sql("select count(*) from team_member where team_id = :t and employee_id = :e and until is null")
					.param("t", targetId).param("e", employee).query(Long.class).single() > 0;
			if (!already) {
				addMember(target, employee, false, LocalDate.now(), depts);
			}
		}
		jdbc.sql("update team set lead_employee_id = null where id = :t").param("t", from.getId()).update();
	}

	// ================================================================== membership

	/** Adds and removes members in one call; {@code add} entries are {@code {employeeId, primary}}. */
	@Transactional
	@SuppressWarnings("unchecked")
	public List<TeamDtos.Member> setMembers(UUID teamId, Map<String, Object> body) {
		Team t = find(teamId);
		LocalDate effective = date(body.get("effectiveDate"));
		DeptTree depts = tree();
		if (body.get("remove") instanceof List<?> remove) {
			for (Object o : remove) {
				UUID employee = uuid(o);
				if (employee.equals(t.getLeadEmployeeId())) {
					throw new ConflictException(people.names(List.of(employee)).get(employee) + " is the lead of " + t.getName()
							+ ". Change the team lead first.");
				}
				endMember(teamId, employee, effective, true);
			}
		}
		if (body.get("add") instanceof List<?> add) {
			for (Object o : add) {
				Map<String, Object> row = (Map<String, Object>) o;
				addMember(t, uuid(row.get("employeeId")), Boolean.parseBoolean(String.valueOf(row.get("primary"))), effective, depts);
			}
		}
		return query.members(teamId, false);
	}

	/** Makes sure the person is a current member (used for leads); returns without error if they already are. */
	private void ensureMember(Team t, UUID employee, LocalDate since, DeptTree depts) {
		boolean already = jdbc.sql("select count(*) from team_member where team_id = :t and employee_id = :e and until is null")
				.param("t", t.getId()).param("e", employee).query(Long.class).single() > 0;
		if (!already) {
			addMember(t, employee, false, since, depts);
		}
	}

	void addMember(Team t, UUID employee, boolean primary, LocalDate since, DeptTree depts) {
		People.Emp e = people.find(employee);
		if (e == null || e.exited()) {
			throw invalid("employeeId", "INVALID", "Only current employees can join a team");
		}
		if (!t.isActive()) {
			throw new ConflictException(t.getName() + " is inactive");
		}
		Department d = t.getDepartmentId() == null ? null : depts.get(t.getDepartmentId());
		if (d != null && (e.departmentId() == null || !depts.descendants(d.getId()).contains(e.departmentId()))) {
			Department own = e.departmentId() == null ? null : depts.get(e.departmentId());
			throw invalid("employeeId", "TEAM_OUTSIDE_DEPARTMENT", t.getName() + " is a " + d.getName() + " team. " + e.name()
					+ " is in " + (own == null ? "no department" : own.getName()) + ".");
		}
		Long existing = jdbc.sql("select count(*) from team_member where team_id = :t and employee_id = :e and until is null")
				.param("t", t.getId()).param("e", employee).query(Long.class).single();
		if (existing > 0) {
			if (primary) {
				setPrimary(employee, t.getId());
			}
			return;
		}
		long current = jdbc.sql("select count(*) from team_member where employee_id = :e and until is null").param("e", employee)
				.query(Long.class).single();
		if (current >= MAX_TEAMS) {
			throw invalid("teams", "TOO_MANY_TEAMS", e.name() + " can be in at most " + MAX_TEAMS + " teams");
		}
		boolean hasPrimary = jdbc.sql("select count(*) from team_member where employee_id = :e and is_primary and until is null")
				.param("e", employee).query(Long.class).single() > 0;
		boolean makePrimary = primary || !hasPrimary;
		if (makePrimary) {
			jdbc.sql("update team_member set is_primary = false where employee_id = :e and until is null").param("e", employee).update();
		}
		jdbc.sql("insert into team_member (org_id, team_id, employee_id, is_primary, since, created_by) values (:org, :t, :e, :p, :d, :by)")
				.param("org", t.getOrgId()).param("t", t.getId()).param("e", employee).param("p", makePrimary).param("d", since)
				.param("by", currentUser.employeeId(), java.sql.Types.OTHER).update();
		events.publishEvent(new TeamMembershipChangedEvent(t.getId(), employee, true, makePrimary));
	}

	/** Ends a membership (it stays in the history). If it was the primary one, the oldest remaining team becomes primary. */
	void endMember(UUID teamId, UUID employee, LocalDate until, boolean required) {
		Boolean primary = jdbc.sql("select is_primary from team_member where team_id = :t and employee_id = :e and until is null")
				.param("t", teamId).param("e", employee).query(Boolean.class).optional().orElse(null);
		if (primary == null) {
			if (required) {
				throw new NotFoundException("That person is not a current member of the team");
			}
			return;
		}
		jdbc.sql("update team_member set until = greatest(since, :d) where team_id = :t and employee_id = :e and until is null")
				.param("d", until).param("t", teamId).param("e", employee).update();
		if (primary) {
			promoteOldest(employee);
		}
		events.publishEvent(new TeamMembershipChangedEvent(teamId, employee, false, false));
	}

	private void promoteOldest(UUID employee) {
		jdbc.sql("""
				update team_member set is_primary = true where id = (
				    select id from team_member where employee_id = :e and until is null order by since, created_at limit 1)
				""").param("e", employee).update();
	}

	private void setPrimary(UUID employee, UUID teamId) {
		jdbc.sql("update team_member set is_primary = false where employee_id = :e and until is null and team_id <> :t")
				.param("e", employee).param("t", teamId).update();
		jdbc.sql("update team_member set is_primary = true where employee_id = :e and until is null and team_id = :t")
				.param("e", employee).param("t", teamId).update();
	}

	private void endAll(UUID teamId, LocalDate until) {
		jdbc.sql("select employee_id from team_member where team_id = :t and until is null").param("t", teamId).query(UUID.class)
				.list().forEach(e -> endMember(teamId, e, until, false));
	}

	/** Used when a person leaves: every membership ends and the leads they were clear. */
	void releaseEmployee(UUID employee, LocalDate until) {
		jdbc.sql("select team_id from team_member where employee_id = :e and until is null").param("e", employee).query(UUID.class)
				.list().forEach(t -> endMember(t, employee, until, false));
		for (UUID teamId : jdbc.sql("select id from team where lead_employee_id = :e").param("e", employee).query(UUID.class).list()) {
			jdbc.sql("update team set lead_employee_id = null where id = :t").param("t", teamId).update();
			audit.publish("org", ENTITY, teamId, "LEAD_CLEARED", Map.of("lead", employee.toString()), null);
			events.publishEvent(new TeamLeadChangedEvent(teamId, employee, null));
		}
	}

	/** A person moved to another department: memberships outside the new tree end; a single active team there takes them in. */
	void onTransfer(UUID employee, UUID newDepartment, LocalDate date) {
		DeptTree depts = tree();
		Set<UUID> within = newDepartment == null ? Set.of() : depts.descendants(newDepartment);
		for (UUID teamId : jdbc.sql("select m.team_id from team_member m join team t on t.id = m.team_id where m.employee_id = :e and m.until is null "
				+ "and (t.department_id is not null)").param("e", employee).query(UUID.class).list()) {
			Team t = repo.findById(teamId).orElse(null);
			if (t != null && t.getDepartmentId() != null && !within.contains(t.getDepartmentId())) {
				endMember(teamId, employee, date.minusDays(1), false);
			}
		}
		List<Team> teams = newDepartment == null ? List.of() : repo.findByOrgId(currentUser.orgId()).stream()
				.filter(t -> t.isActive() && newDepartment.equals(t.getDepartmentId())).toList();
		boolean hasAny = jdbc.sql("select count(*) from team_member where employee_id = :e and until is null").param("e", employee)
				.query(Long.class).single() > 0;
		if (teams.size() == 1 && !hasAny) {
			addMember(teams.get(0), employee, true, date, depts);
		}
	}

	/** Replaces a person's current teams with {@code desired} (each {@code {teamId, primary}}); the whole set is checked first. */
	@Transactional
	@SuppressWarnings("unchecked")
	public void applyEmployeeTeams(UUID employee, List<Map<String, Object>> desired, LocalDate effective) {
		DeptTree depts = tree();
		if (desired.size() > MAX_TEAMS) {
			throw invalid("teams", "TOO_MANY_TEAMS", "A person can be in at most " + MAX_TEAMS + " teams");
		}
		Map<UUID, Team> teams = new LinkedHashMap<>();
		UUID primary = null;
		for (Map<String, Object> row : desired) {
			UUID id = uuid(row.get("teamId"));
			Team t = find(id);
			if (teams.put(id, t) != null) {
				throw invalid("teams", "INVALID", "A team is listed twice");
			}
			if (Boolean.parseBoolean(String.valueOf(row.get("primary")))) {
				if (primary != null) {
					throw invalid("teams", "INVALID", "Only one team can be primary");
				}
				primary = id;
			}
		}
		Set<UUID> current = new LinkedHashSet<>(jdbc.sql("select team_id from team_member where employee_id = :e and until is null")
				.param("e", employee).query(UUID.class).list());
		for (UUID teamId : current) {
			if (!teams.containsKey(teamId)) {
				Team t = find(teamId);
				if (employee.equals(t.getLeadEmployeeId())) {
					throw new ConflictException(people.names(List.of(employee)).get(employee) + " is the lead of " + t.getName()
							+ ". Change the team lead first.");
				}
				endMember(teamId, employee, effective, false);
			}
		}
		for (Team t : teams.values()) {
			if (!current.contains(t.getId())) {
				addMember(t, employee, false, effective, depts);
			}
		}
		if (primary != null) {
			setPrimary(employee, primary);
		}
		else if (!teams.isEmpty() && jdbc.sql("select count(*) from team_member where employee_id = :e and is_primary and until is null")
				.param("e", employee).query(Long.class).single() == 0) {
			promoteOldest(employee);
		}
	}

	/** The people's current teams: {@code {id, name, primary}}. */
	List<Object[]> teamsOf(UUID employee) {
		return jdbc.sql("select t.id, t.name, m.is_primary from team_member m join team t on t.id = m.team_id "
				+ "where m.employee_id = :e and m.until is null order by m.is_primary desc, t.name").param("e", employee)
				.query((rs, i) -> new Object[] {rs.getObject(1, UUID.class), rs.getString(2), rs.getBoolean(3)}).list();
	}

	// ================================================================== bulk save (Settings → Onboarding → Teams)

	/** Saves the whole teams list through the same rules; every problem comes back with its row and column. */
	@Transactional
	public void bulkUpsert(List<Map<String, Object>> rows) {
		deferFlush();
		UUID org = currentUser.orgId();
		boolean confirm = Confirmations.requested(null);
		DeptTree depts = tree();
		Map<UUID, Team> existing = new HashMap<>();
		repo.findByOrgId(org).forEach(t -> existing.put(t.getId(), t));
		Map<UUID, Integer> indexOf = new HashMap<>();
		Map<UUID, UUID> oldLead = new HashMap<>();
		Map<UUID, UUID> oldDept = new HashMap<>();
		Map<UUID, Boolean> wasActive = new HashMap<>();
		List<Team> changed = new ArrayList<>();
		List<Team> created = new ArrayList<>();
		List<ErrorItem> errors = new ArrayList<>();
		for (int i = 0; i < rows.size(); i++) {
			Map<String, Object> row = rows.get(i);
			UUID id = UUID.fromString(row.get("id").toString());
			boolean isNew = !existing.containsKey(id);
			Team t = isNew ? new Team() : existing.get(id);
			if (isNew) {
				t.setId(id);
				t.setOrgId(org);
			}
			else {
				oldLead.put(id, t.getLeadEmployeeId());
				oldDept.put(id, t.getDepartmentId());
				wasActive.put(id, t.isActive());
			}
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("name", row.get("name"));
			body.put("departmentId", row.get("dept"));
			body.put("leadEmployeeId", row.get("lead"));
			body.put("active", row.get("active") == null || Boolean.parseBoolean(row.get("active").toString()));
			Input in = new Input(body);
			in.applyTo(t);
			validate(t, in, true, depts, new ArrayList<>());
			final int index = i;
			in.errors.forEach(e -> errors.add(ErrorItem.cell("teams", index, COLUMNS.getOrDefault(e.fieldKey(), e.fieldKey()), e.message())));
			indexOf.put(id, i);
			changed.add(t);
			if (isNew) {
				created.add(t);
			}
		}
		if (errors.isEmpty()) {
			List<Team> all = new ArrayList<>(existing.values());
			all.addAll(created);
			for (Problem p : duplicates(all, changed, depts)) {
				errors.add(ErrorItem.cell("teams", indexOf.get(p.on().getId()), COLUMNS.getOrDefault(p.field(), p.field()), p.message()));
			}
			for (Team t : changed) {
				if (!existing.containsKey(t.getId())) {
					continue;
				}
				if (!Objects.equals(oldDept.get(t.getId()), t.getDepartmentId()) && t.getDepartmentId() != null) {
					List<String> outside = membersOutside(t.getId(), depts.descendants(t.getDepartmentId()));
					if (!outside.isEmpty()) {
						errors.add(ErrorItem.cell("teams", indexOf.get(t.getId()), "dept", outside.size() + " members are outside that department"));
					}
				}
				if (!wasActive.get(t.getId()) && t.isActive()) {
					Department d = t.getDepartmentId() == null ? null : depts.get(t.getDepartmentId());
					if (d != null && !d.isActive()) {
						errors.add(ErrorItem.cell("teams", indexOf.get(t.getId()), "active", "Activate " + d.getName() + " first"));
					}
				}
			}
		}
		if (!errors.isEmpty()) {
			throw new UnprocessableException(errors);
		}
		List<String> lines = new ArrayList<>();
		for (Team t : changed) {
			if (existing.containsKey(t.getId()) && wasActive.get(t.getId()) && !t.isActive()) {
				long n = currentMembers(t.getId());
				if (n > 0) {
					lines.add(t.getName() + " (" + n + (n == 1 ? " person)" : " people)"));
				}
			}
		}
		if (!lines.isEmpty() && !confirm) {
			throw new ConfirmRequiredException("Deactivating these teams ends their members' membership today", lines);
		}
		created.forEach(repo::saveAndFlush);
		repo.flush();
		for (Team t : changed) {
			boolean isNew = !existing.containsKey(t.getId());
			if (!isNew && wasActive.get(t.getId()) && !t.isActive()) {
				endAll(t.getId(), LocalDate.now());
			}
			if (t.isActive() && t.getLeadEmployeeId() != null && (isNew || !Objects.equals(oldLead.get(t.getId()), t.getLeadEmployeeId()))) {
				ensureMember(t, t.getLeadEmployeeId(), LocalDate.now(), depts);
			}
			if (isNew) {
				events.publishEvent(new TeamCreatedEvent(t.getId()));
			}
			else {
				events.publishEvent(new TeamUpdatedEvent(t.getId(), List.of("list")));
				if (!Objects.equals(oldLead.get(t.getId()), t.getLeadEmployeeId())) {
					events.publishEvent(new TeamLeadChangedEvent(t.getId(), oldLead.get(t.getId()), t.getLeadEmployeeId()));
				}
				if (wasActive.get(t.getId()) != t.isActive()) {
					events.publishEvent(new TeamActivationChangedEvent(t.getId(), t.isActive()));
				}
			}
		}
	}

	/** Deletes the teams that are no longer in the list, each through the usage guard. */
	@Transactional
	public void bulkDelete(Set<UUID> keep) {
		repo.findByOrgId(currentUser.orgId()).stream().filter(t -> !keep.contains(t.getId())).forEach(t -> delete(t.getId(), null));
	}

	// ================================================================== input and validation

	private static final class Input {

		final Map<String, Object> body;
		final Set<String> touched = new LinkedHashSet<>();
		final List<ErrorItem> errors = new ArrayList<>();
		final List<String> warnings = new ArrayList<>();

		Input(Map<String, Object> body) {
			this.body = body;
		}

		void applyTo(Team t) {
			for (Map.Entry<String, Object> e : body.entrySet()) {
				String k = e.getKey();
				Object v = e.getValue();
				boolean known = true;
				try {
					switch (k) {
						case "name" -> t.setName(text(v) == null ? null : text(v).replaceAll("\\s+", " "));
						case "departmentId" -> t.setDepartmentId(uuid(v));
						case "leadEmployeeId" -> t.setLeadEmployeeId(uuid(v));
						case "description" -> t.setDescription(text(v));
						case "active" -> t.setActive(v == null || bool(v));
						case "msChannelId" -> {
							// no Microsoft Teams integration yet: the channel is ignored
							if (text(v) != null) {
								warnings.add("The Microsoft Teams channel was ignored (the integration is not connected)");
							}
						}
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

	private void validate(Team t, Input in, boolean create, DeptTree depts, List<String> warnings) {
		List<ErrorItem> errors = in.errors;
		java.util.function.Predicate<String> any = key -> create || in.touched.contains(key);
		if (any.test("name")) {
			if (t.getName() == null) {
				errors.add(ErrorItem.field("name", "REQUIRED", "Every team needs a name"));
			}
			else if (t.getName().length() < 2 || t.getName().length() > 80) {
				errors.add(ErrorItem.field("name", "NAME_LENGTH", "Name must be 2–80 characters"));
			}
		}
		if (t.getDescription() != null && t.getDescription().length() > 500) {
			errors.add(ErrorItem.field("description", "INVALID", "Description can be at most 500 characters"));
		}
		Department d = null;
		if (t.getDepartmentId() == null) {
			if (t.isActive()) {
				errors.add(ErrorItem.field("departmentId", "REQUIRED", "Pick the department this team belongs to"));
			}
		}
		else {
			d = depts.get(t.getDepartmentId());
			if (d == null) {
				errors.add(ErrorItem.field("departmentId", "INVALID", "That department does not exist"));
			}
			else if (!d.isActive() && t.isActive() && (any.test("departmentId") || in.touched.contains("active"))) {
				errors.add(ErrorItem.field("departmentId", "DEPARTMENT_INACTIVE", "That department is inactive"));
			}
		}
		if (t.getLeadEmployeeId() != null && (any.test("leadEmployeeId") || in.touched.contains("departmentId"))) {
			People.Emp lead = people.find(t.getLeadEmployeeId());
			if (lead == null || !lead.live()) {
				errors.add(ErrorItem.field("leadEmployeeId", "LEAD_INVALID", "The team lead must be an active employee"));
			}
			else {
				if ("ON_NOTICE".equals(lead.status())) {
					warnings.add(lead.name() + " is serving notice");
				}
				if (d != null && (lead.departmentId() == null || !depts.descendants(d.getId()).contains(lead.departmentId()))) {
					Department own = lead.departmentId() == null ? null : depts.get(lead.departmentId());
					errors.add(ErrorItem.field("leadEmployeeId", "LEAD_OUTSIDE_DEPARTMENT", lead.name() + " is in "
							+ (own == null ? "no department" : own.getName()) + ". A team lead must be in " + d.getName() + "."));
				}
			}
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

	private static UUID uuid(Object v) {
		try {
			return UUID.fromString(String.valueOf(v));
		}
		catch (IllegalArgumentException ex) {
			throw invalid("id", "INVALID", "'" + v + "' is not a valid id");
		}
	}

	private Team find(UUID id) {
		return repo.findByIdAndOrgId(id, currentUser.orgId()).orElseThrow(() -> new NotFoundException("Team " + id + " not found"));
	}
}
