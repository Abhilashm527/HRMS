package com.ivoyant.hrms.org.service;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.event.EmployeePositionChangedEvent;
import com.ivoyant.hrms.common.event.EmployeeTitleChangedEvent;
import com.ivoyant.hrms.common.event.TitleActivationChangedEvent;
import com.ivoyant.hrms.common.event.TitleCreatedEvent;
import com.ivoyant.hrms.common.event.TitleDeletedEvent;
import com.ivoyant.hrms.common.event.TitleDepartmentChangedEvent;
import com.ivoyant.hrms.common.event.TitlePositionChangedEvent;
import com.ivoyant.hrms.common.event.TitleUpdatedEvent;
import com.ivoyant.hrms.common.forms.RecordCustomFields;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.ConfirmRequiredException;
import com.ivoyant.hrms.common.web.ConflictException;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.InUseException;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.common.web.StaleException;
import com.ivoyant.hrms.common.web.UnprocessableException;
import com.ivoyant.hrms.org.domain.Department;
import com.ivoyant.hrms.org.domain.Designation;
import com.ivoyant.hrms.org.domain.JobTitle;
import com.ivoyant.hrms.org.dto.TitleNode;
import com.ivoyant.hrms.org.dto.TitleUsage;
import com.ivoyant.hrms.org.repo.DepartmentRepository;
import com.ivoyant.hrms.org.repo.DesignationRepository;
import com.ivoyant.hrms.org.repo.JobTitleRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
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
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Creating, changing, merging and deleting job titles. A title belongs to one position (which sets the holder's position)
 * and may be limited to a department and the departments below it. Name and code are unique ignoring case; duplicates
 * inside a bulk save count.
 */
@Service
public class TitleService {

	private static final String ENTITY = "Title";
	private static final Pattern CODE = Pattern.compile("^[A-Z0-9][A-Z0-9-]{1,11}$");
	/** Request key to the settings list's column, so a bulk save can point at the cell that is wrong. */
	private static final Map<String, String> COLUMNS = Map.of("name", "name", "code", "code", "positionId", "pos",
			"departmentId", "dept", "description", "desc", "active", "active");

	/** A holder of a title: enough to name them in a message and to check their department. */
	private record Holder(UUID id, String name, UUID departmentId) {
	}

	/** One thing wrong after the change; {@code code} CONFLICT is a plain 409. */
	private record Problem(JobTitle on, String field, String code, String message) {
	}

	@PersistenceContext
	private EntityManager em;

	private final JobTitleRepository repo;
	private final DesignationRepository positionRepo;
	private final DepartmentRepository deptRepo;
	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final TitleQueryService query;
	private final RecordCustomFields customFields;
	private final Audit audit;
	private final ApplicationEventPublisher events;

	public TitleService(JobTitleRepository repo, DesignationRepository positionRepo, DepartmentRepository deptRepo,
			JdbcClient jdbc, CurrentUser currentUser, TitleQueryService query, RecordCustomFields customFields,
			Audit audit, ApplicationEventPublisher events) {
		this.repo = repo;
		this.positionRepo = positionRepo;
		this.deptRepo = deptRepo;
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.query = query;
		this.customFields = customFields;
		this.audit = audit;
		this.events = events;
	}

	// ================================================================== create / update

	@Transactional
	@SuppressWarnings("unchecked")
	public TitleNode create(Map<String, Object> body) {
		deferFlush();
		JobTitle t = new JobTitle();
		t.setOrgId(currentUser.orgId());
		Input in = new Input(body);
		in.applyTo(t);
		validate(t, in, true);
		throwIfInvalid(in);
		List<JobTitle> all = new ArrayList<>(repo.findByOrgId(t.getOrgId()));
		if (t.getCode() == null) {
			t.setCode(generateCode(t.getName(), codes(all)));
		}
		all.add(t);
		throwFirst(conflicts(all, List.of(t)));
		repo.saveAndFlush(t);
		Object custom = body.get("custom");
		customFields.write("title", t.getId(), custom instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of(), true);
		TitleNode out = query.get(t.getId());
		audit.created("org", ENTITY, t.getId(), out);
		events.publishEvent(new TitleCreatedEvent(t.getId()));
		return out;
	}

	@Transactional
	@SuppressWarnings("unchecked")
	public TitleNode update(UUID id, Map<String, Object> patch, String ifMatch) {
		deferFlush();
		boolean confirm = confirmed(patch);
		patch = new LinkedHashMap<>(patch);
		patch.remove("confirm");
		JobTitle t = find(id);
		if (ifMatch != null && !ifMatch.isBlank()
				&& !ifMatch.replace("W/", "").replace("\"", "").trim().equals(String.valueOf(t.getVersion()))) {
			throw new StaleException("Someone else changed this title. Reload and try again.");
		}
		TitleNode before = query.get(id);
		UUID oldPosition = t.getDesignationId();
		UUID oldDepartment = t.getDepartmentId();
		boolean wasActive = t.isActive();
		Input in = new Input(patch);
		in.applyTo(t);
		if (!wasActive && t.isActive()) {
			requireActivatable(t);
		}
		validate(t, in, false);
		throwIfInvalid(in);
		List<JobTitle> all = new ArrayList<>(repo.findByOrgId(t.getOrgId()));
		if (in.touched.contains("code") && t.getCode() == null) {
			t.setCode(generateCode(t.getName(), codes(all.stream().filter(x -> !x.getId().equals(id)).toList())));
		}
		throwFirst(conflicts(all, List.of(t)));
		List<String> warnings = new ArrayList<>();
		List<Holder> holders = holders(id);
		boolean positionChanged = !Objects.equals(oldPosition, t.getDesignationId());
		if (!Objects.equals(oldDepartment, t.getDepartmentId()) && t.getDepartmentId() != null) {
			List<Holder> outside = outside(t.getDepartmentId(), holders);
			if (!outside.isEmpty()) {
				Department d = deptRepo.findById(t.getDepartmentId()).orElse(null);
				throw new InUseException(outside.size() + (outside.size() == 1 ? " person with this title is outside "
						: " people with this title are outside ") + (d == null ? "that department" : d.getName()),
						Map.of("employees", outside.stream().map(Holder::name).toList()));
			}
		}
		if (positionChanged && !holders.isEmpty()) {
			if (!confirm) {
				throw new ConfirmRequiredException(holders.size() + (holders.size() == 1 ? " person moves" : " people move")
						+ " from " + positionName(oldPosition) + " to " + positionName(t.getDesignationId()),
						holders.stream().limit(10).map(Holder::name).toList());
			}
			warnings.add(holders.size() + (holders.size() == 1 ? " person moves" : " people move") + " from "
					+ positionName(oldPosition) + " to " + positionName(t.getDesignationId()));
		}
		if (wasActive && !t.isActive() && !holders.isEmpty()) {
			warnings.add(holders.size() + (holders.size() == 1 ? " person still holds " : " people still hold ") + t.getName());
		}
		repo.saveAndFlush(t);
		if (positionChanged) {
			movePosition(holders, oldPosition, t.getDesignationId(), id);
		}
		Object custom = patch.get("custom");
		if (custom instanceof Map<?, ?> m) {
			customFields.write("title", id, (Map<String, Object>) m, false);
		}
		TitleNode after = query.get(id);
		after = after.withExtras(after.custom(), java.util.stream.Stream.concat(
				after.warnings() == null ? java.util.stream.Stream.<String>empty() : after.warnings().stream(),
				warnings.stream()).distinct().toList());
		audit.updated("org", ENTITY, id, before, after);
		publishChanges(t, oldPosition, oldDepartment, wasActive, in.touched, holders.size());
		return after;
	}

	private void publishChanges(JobTitle t, UUID oldPosition, UUID oldDepartment, boolean wasActive, Set<String> touched,
			int moved) {
		events.publishEvent(new TitleUpdatedEvent(t.getId(), List.copyOf(touched)));
		if (!Objects.equals(oldPosition, t.getDesignationId())) {
			events.publishEvent(new TitlePositionChangedEvent(t.getId(), oldPosition, t.getDesignationId(), moved));
		}
		if (!Objects.equals(oldDepartment, t.getDepartmentId())) {
			events.publishEvent(new TitleDepartmentChangedEvent(t.getId(), oldDepartment, t.getDepartmentId()));
		}
		if (wasActive != t.isActive()) {
			events.publishEvent(new TitleActivationChangedEvent(t.getId(), t.isActive()));
		}
	}

	/** The title decides the holders' position: everyone who holds it as their primary title moves with it. */
	private void movePosition(List<Holder> holders, UUID from, UUID to, UUID titleId) {
		if (holders.isEmpty()) {
			return;
		}
		jdbc.sql("update employee set designation_id = :to where title_id = :t and status <> 'EXITED'")
				.param("to", to).param("t", titleId).update();
		holders.forEach(h -> events.publishEvent(new EmployeePositionChangedEvent(h.id(), from, to)));
	}

	private void requireActivatable(JobTitle t) {
		if (t.getDesignationId() != null) {
			Designation p = positionRepo.findByIdAndOrgId(t.getDesignationId(), t.getOrgId()).orElse(null);
			if (p != null && !p.isActive()) {
				throw new ConflictException("Activate " + p.getTitle() + " first");
			}
		}
		if (t.getDepartmentId() != null) {
			Department d = deptRepo.findByIdAndOrgId(t.getDepartmentId(), t.getOrgId()).orElse(null);
			if (d != null && !d.isActive()) {
				throw new ConflictException("Activate " + d.getName() + " first");
			}
		}
	}

	private void deferFlush() {
		em.setFlushMode(FlushModeType.COMMIT);
	}

	/** Position moves that affect people need a yes: {@code confirm: true} in the body or {@code ?force=true}/{@code ?confirm=true}. */
	private static boolean confirmed(Map<String, Object> body) {
		if (body != null && "true".equalsIgnoreCase(String.valueOf(body.get("confirm")))) {
			return true;
		}
		if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a) {
			return "true".equalsIgnoreCase(a.getRequest().getParameter("force"))
					|| "true".equalsIgnoreCase(a.getRequest().getParameter("confirm"));
		}
		return false;
	}

	// ================================================================== the rules that depend on the other titles

	/** Name and code are unique ignoring case, over the titles as they will be after the change. */
	private List<Problem> conflicts(List<JobTitle> all, List<JobTitle> changed) {
		List<Problem> out = new ArrayList<>();
		for (JobTitle e : changed) {
			String name = e.getName() == null ? "" : e.getName().toLowerCase(Locale.ROOT);
			if (all.stream().anyMatch(o -> o != e && !o.getId().equals(e.getId()) && o.getName() != null
					&& o.getName().toLowerCase(Locale.ROOT).equals(name))) {
				out.add(new Problem(e, "name", "CONFLICT", "A title with this name already exists"));
			}
			if (e.getCode() != null && all.stream().anyMatch(o -> o != e && !o.getId().equals(e.getId())
					&& e.getCode().equalsIgnoreCase(o.getCode()))) {
				out.add(new Problem(e, "code", "CONFLICT", "A title with this code already exists"));
			}
		}
		return out;
	}

	private void throwFirst(List<Problem> problems) {
		if (!problems.isEmpty()) {
			throw new ConflictException(problems.get(0).message());
		}
	}

	private static Set<String> codes(List<JobTitle> all) {
		return all.stream().map(JobTitle::getCode).filter(Objects::nonNull).map(c -> c.toUpperCase(Locale.ROOT))
				.collect(Collectors.toCollection(HashSet::new));
	}

	/**
	 * The initials of the words (up to 6 letters): "Senior Software Engineer" is SSE. If that is taken, the first four
	 * letters of the longest word ("ENGI"), then that with -2, -3 and so on. A code the user typed is never replaced.
	 */
	static String generateCode(String name, Set<String> taken) {
		List<String> words = java.util.Arrays.stream(name.toUpperCase(Locale.ROOT).split("[^A-Z0-9]+"))
				.filter(w -> !w.isEmpty()).toList();
		String initials = words.stream().map(w -> w.substring(0, 1)).collect(Collectors.joining());
		if (initials.length() > 6) {
			initials = initials.substring(0, 6);
		}
		String longest = words.stream().max(java.util.Comparator.comparingInt(String::length)).orElse("TITLE");
		String word = longest.length() > 4 ? longest.substring(0, 4) : longest;
		if (word.length() < 2) {
			word = (word + "X").substring(0, 2);
		}
		String base = initials.length() >= 2 ? initials : word;
		if (!taken.contains(base)) {
			return base;
		}
		if (!taken.contains(word)) {
			return word;
		}
		for (int n = 2; n < 1000; n++) {
			String candidate = word + "-" + n;
			if (!taken.contains(candidate)) {
				return candidate;
			}
		}
		return word + "-" + UUID.randomUUID().toString().substring(0, 4).toUpperCase(Locale.ROOT);
	}

	private List<Holder> holders(UUID titleId) {
		return jdbc.sql("select id, display_name, department_id from employee where title_id = :t and status <> 'EXITED' order by display_name")
				.param("t", titleId).query((rs, i) -> new Holder(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class)))
				.list();
	}

	private List<Holder> outside(UUID departmentId, List<Holder> holders) {
		Set<UUID> within = new DeptTree(deptRepo.findByOrgId(currentUser.orgId())).descendants(departmentId);
		return holders.stream().filter(h -> h.departmentId() == null || !within.contains(h.departmentId())).toList();
	}

	private String positionName(UUID id) {
		return id == null ? "no position" : positionRepo.findById(id).map(Designation::getTitle).orElse("no position");
	}

	// ================================================================== delete / merge

	/** Hard delete. Nothing may point at the title; with {@code reassignTo} it is merged into that title first. */
	@Transactional
	public void delete(UUID id, UUID reassignTo) {
		if (reassignTo != null) {
			merge(id, reassignTo);
			return;
		}
		JobTitle t = find(id);
		TitleNode before = query.get(id);
		TitleUsage usage = query.usage(id);
		if (usage.blocksDelete()) {
			String message = usage.message() != null ? usage.message()
					: "This title is still used by other data. Merge it into another title, or mark it inactive.";
			throw new InUseException(message, usageMap(usage));
		}
		customFields.delete("title", id);
		repo.delete(t);
		repo.flush();
		audit.deleted("org", ENTITY, id, before);
		events.publishEvent(new TitleDeletedEvent(id, null));
	}

	/**
	 * Moves everything on {@code id} to {@code intoId} (people, extra titles, history) and deletes {@code id}. The target must
	 * be active and fit every holder's department. History is re-pointed (reason MERGE), so a title that was ever held can be merged.
	 */
	@Transactional
	public void merge(UUID id, UUID intoId) {
		JobTitle from = find(id);
		JobTitle into = find(intoId);
		if (id.equals(intoId)) {
			throw new ConflictException("Choose a different title to merge into");
		}
		if (!into.isActive()) {
			throw new ConflictException(into.getName() + " is inactive. Choose an active title");
		}
		TitleNode before = query.get(id);
		List<Holder> primary = holders(id);
		List<Holder> extra = jdbc.sql("select e.id, e.display_name, e.department_id from employee_extra_title x join employee e on e.id = x.employee_id where x.title_id = :t")
				.param("t", id).query((rs, i) -> new Holder(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class))).list();
		DeptTree tree = new DeptTree(deptRepo.findByOrgId(currentUser.orgId()));
		List<String> misfits = java.util.stream.Stream.concat(primary.stream(), extra.stream())
				.filter(h -> into.getDepartmentId() != null
						&& (h.departmentId() == null || !tree.descendants(into.getDepartmentId()).contains(h.departmentId())))
				.map(Holder::name).distinct().toList();
		if (!misfits.isEmpty()) {
			throw new ConflictException(into.getName() + " isn't valid for " + misfits.size()
					+ (misfits.size() == 1 ? " person's department" : " people's departments"), misfits);
		}
		jdbc.sql("update employee set title_id = :to where title_id = :f and status = 'EXITED'").param("to", intoId)
				.param("f", id).update();
		jdbc.sql("update employee set title_id = :to, designation_id = :pos where title_id = :f and status <> 'EXITED'")
				.param("to", intoId).param("pos", into.getDesignationId()).param("f", id).update();
		jdbc.sql("""
				insert into employee_extra_title (employee_id, title_id, org_id, since)
				select x.employee_id, :to, x.org_id, x.since from employee_extra_title x
				where x.title_id = :f and x.employee_id not in (select e.id from employee e where e.title_id = :to)
				on conflict do nothing""").param("to", intoId).param("f", id).update();
		jdbc.sql("delete from employee_extra_title where title_id = :f").param("f", id).update();
		jdbc.sql("update employee_title_history set title_id = :to, reason = 'MERGE' where title_id = :f")
				.param("to", intoId).param("f", id).update();
		for (Holder h : primary) {
			if (!Objects.equals(from.getDesignationId(), into.getDesignationId())) {
				events.publishEvent(new EmployeePositionChangedEvent(h.id(), from.getDesignationId(), into.getDesignationId()));
			}
			events.publishEvent(new EmployeeTitleChangedEvent(h.id(), id, intoId, List.of(), List.of(), "MERGE", LocalDate.now()));
		}
		em.flush();
		customFields.delete("title", id);
		repo.delete(from);
		repo.flush();
		audit.deleted("org", ENTITY, id, before);
		events.publishEvent(new TitleDeletedEvent(id, intoId));
	}

	private static Map<String, Object> usageMap(TitleUsage u) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("employees", u.employees());
		m.put("extraHolders", u.extraHolders());
		m.put("historyRows", u.historyRows());
		m.put("openJobs", u.openJobs());
		m.put("offers", u.offers());
		m.put("onboardingDrafts", u.onboardingDrafts());
		m.put("customFieldValues", u.customFieldValues());
		return m;
	}

	// ================================================================== bulk save (Settings → Onboarding → Titles)

	/**
	 * Saves the whole titles list through the same rules as the API: every row is checked against the list as it will be
	 * afterwards (a duplicate inside the save counts), problems come back with row and column, and nothing is kept if any
	 * row is wrong. A position change that moves people needs a yes ({@code ?force=true}); the first answer lists the counts.
	 */
	@Transactional
	public void bulkUpsert(List<Map<String, Object>> rows) {
		deferFlush();
		UUID org = currentUser.orgId();
		Map<UUID, JobTitle> existing = new HashMap<>();
		repo.findByOrgId(org).forEach(t -> existing.put(t.getId(), t));
		Map<UUID, Integer> indexOf = new HashMap<>();
		Map<UUID, UUID> oldPosition = new HashMap<>();
		Map<UUID, UUID> oldDepartment = new HashMap<>();
		Map<UUID, Boolean> wasActive = new HashMap<>();
		Map<UUID, Input> inputs = new HashMap<>();
		List<JobTitle> changed = new ArrayList<>();
		List<JobTitle> created = new ArrayList<>();
		List<ErrorItem> errors = new ArrayList<>();
		for (int i = 0; i < rows.size(); i++) {
			Map<String, Object> row = rows.get(i);
			UUID id = UUID.fromString(row.get("id").toString());
			boolean isNew = !existing.containsKey(id);
			JobTitle t = isNew ? new JobTitle() : existing.get(id);
			if (isNew) {
				t.setId(id);
				t.setOrgId(org);
			}
			else {
				oldPosition.put(id, t.getDesignationId());
				oldDepartment.put(id, t.getDepartmentId());
				wasActive.put(id, t.isActive());
			}
			Input in = new Input(toBody(row));
			in.applyTo(t);
			validate(t, in, true);
			final int index = i;
			in.errors.forEach(e -> errors.add(ErrorItem.cell("titles", index, column(e.fieldKey()), e.message())));
			indexOf.put(id, i);
			inputs.put(id, in);
			changed.add(t);
			if (isNew) {
				created.add(t);
			}
		}
		List<JobTitle> all = new ArrayList<>(existing.values());
		all.addAll(created);
		if (errors.isEmpty()) {
			Set<String> taken = codes(all);
			for (JobTitle t : changed) {
				if (t.getCode() == null) {
					String code = generateCode(t.getName(), taken);
					t.setCode(code);
					taken.add(code);
				}
			}
			for (Problem p : conflicts(all, changed)) {
				errors.add(ErrorItem.cell("titles", indexOf.get(p.on().getId()), column(p.field()), p.message()));
			}
		}
		Map<UUID, List<Holder>> moved = new LinkedHashMap<>();
		if (errors.isEmpty()) {
			for (JobTitle t : changed) {
				if (!existing.containsKey(t.getId())) {
					continue;
				}
				List<Holder> holders = holders(t.getId());
				if (!Objects.equals(oldDepartment.get(t.getId()), t.getDepartmentId()) && t.getDepartmentId() != null) {
					List<Holder> outside = outside(t.getDepartmentId(), holders);
					if (!outside.isEmpty()) {
						errors.add(ErrorItem.cell("titles", indexOf.get(t.getId()), "dept", outside.size()
								+ (outside.size() == 1 ? " person with this title is" : " people with this title are")
								+ " outside that department"));
					}
				}
				if (!Objects.equals(oldPosition.get(t.getId()), t.getDesignationId()) && !holders.isEmpty()) {
					moved.put(t.getId(), holders);
				}
				if (Boolean.FALSE.equals(wasActive.get(t.getId())) && t.isActive()) {
					try {
						requireActivatable(t);
					}
					catch (ConflictException ex) {
						errors.add(ErrorItem.cell("titles", indexOf.get(t.getId()), "active", ex.getMessage()));
					}
				}
			}
		}
		if (!errors.isEmpty()) {
			throw new UnprocessableException(errors);
		}
		if (!moved.isEmpty() && !confirmed(null)) {
			List<String> lines = new ArrayList<>();
			int total = 0;
			for (Map.Entry<UUID, List<Holder>> e : moved.entrySet()) {
				JobTitle t = existing.get(e.getKey());
				lines.add(t.getName() + ": " + positionName(oldPosition.get(e.getKey())) + " → " + positionName(t.getDesignationId())
						+ " (" + e.getValue().size() + (e.getValue().size() == 1 ? " person)" : " people)"));
				total += e.getValue().size();
			}
			throw new ConfirmRequiredException(total + (total == 1 ? " person moves" : " people move") + " to a different position",
					lines);
		}
		created.forEach(repo::saveAndFlush);
		repo.flush();
		for (JobTitle t : created) {
			customFields.write("title", t.getId(), Map.of(), true);
			events.publishEvent(new TitleCreatedEvent(t.getId()));
		}
		for (JobTitle t : changed) {
			if (!existing.containsKey(t.getId())) {
				continue;
			}
			List<Holder> holders = moved.getOrDefault(t.getId(), List.of());
			movePosition(holders, oldPosition.get(t.getId()), t.getDesignationId(), t.getId());
			publishChanges(t, oldPosition.get(t.getId()), oldDepartment.get(t.getId()), wasActive.get(t.getId()),
					inputs.get(t.getId()).touched, holders.size());
		}
	}

	/** Deletes the titles that are no longer in the list, each through the usage guard. */
	@Transactional
	public void bulkDelete(Set<UUID> keep) {
		repo.findByOrgId(currentUser.orgId()).stream().filter(t -> !keep.contains(t.getId()))
				.forEach(t -> delete(t.getId(), null));
	}

	private static String column(String field) {
		return COLUMNS.getOrDefault(field, field);
	}

	private static Map<String, Object> toBody(Map<String, Object> row) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("name", row.get("name"));
		body.put("code", row.get("code"));
		body.put("positionId", row.get("pos"));
		body.put("departmentId", row.get("dept"));
		body.put("description", row.get("desc"));
		body.put("active", row.get("active") == null || Boolean.parseBoolean(row.get("active").toString()));
		return body;
	}

	// ================================================================== input and validation

	/** A request body applied to a title: which keys were present, and what went wrong reading them. */
	private static final class Input {

		final Map<String, Object> body;
		final Set<String> touched = new LinkedHashSet<>();
		final List<ErrorItem> errors = new ArrayList<>();

		Input(Map<String, Object> body) {
			this.body = body;
		}

		void applyTo(JobTitle t) {
			for (Map.Entry<String, Object> e : body.entrySet()) {
				String k = e.getKey();
				Object v = e.getValue();
				boolean known = true;
				try {
					switch (k) {
						case "name" -> t.setName(name(v));
						case "code" -> t.setCode(upper(v));
						case "positionId" -> t.setDesignationId(uuid(v));
						case "departmentId" -> t.setDepartmentId(uuid(v));
						case "description" -> t.setDescription(text(v));
						case "active" -> t.setActive(v == null || bool(v));
						case "sort" -> t.setSort(v == null ? 0 : v instanceof Number n ? n.intValue() : Integer.parseInt(v.toString().trim()));
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

		/** Trimmed, with runs of spaces collapsed. */
		private static String name(Object v) {
			String t = text(v);
			return t == null ? null : t.replaceAll("\\s+", " ");
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

	/** Field-by-field checks that need nothing but the title, the positions and the departments: these are 422s. */
	private void validate(JobTitle t, Input in, boolean create) {
		List<ErrorItem> errors = in.errors;
		java.util.function.Predicate<String> any = key -> create || in.touched.contains(key);
		if (any.test("name")) {
			if (t.getName() == null) {
				errors.add(ErrorItem.field("name", "REQUIRED", "Every title needs a name"));
			}
			else if (t.getName().length() < 2 || t.getName().length() > 100) {
				errors.add(ErrorItem.field("name", "NAME_LENGTH", "Name must be 2–100 characters"));
			}
		}
		if (t.getCode() != null && any.test("code") && !CODE.matcher(t.getCode()).matches()) {
			errors.add(ErrorItem.field("code", "CODE_FORMAT", "Code must be 2–12 letters, digits or dashes"));
		}
		if (t.getDescription() != null && t.getDescription().length() > 2000) {
			errors.add(ErrorItem.field("description", "INVALID", "Responsibilities can be at most 2000 characters"));
		}
		if (t.isActive() && t.getDesignationId() == null) {
			errors.add(ErrorItem.field("positionId", "POSITION_REQUIRED", "Pick the position this title belongs to"));
		}
		if (t.getDesignationId() != null && (any.test("positionId") || in.touched.contains("active"))) {
			Designation p = positionRepo.findByIdAndOrgId(t.getDesignationId(), t.getOrgId()).orElse(null);
			if (p == null) {
				errors.add(ErrorItem.field("positionId", "INVALID", "That position does not exist"));
			}
			else if (!p.isActive() && t.isActive() && in.touched.contains("positionId")) {
				errors.add(ErrorItem.field("positionId", "POSITION_INACTIVE", "That position is inactive"));
			}
		}
		if (t.getDepartmentId() != null && any.test("departmentId")) {
			Department d = deptRepo.findByIdAndOrgId(t.getDepartmentId(), t.getOrgId()).orElse(null);
			if (d == null) {
				errors.add(ErrorItem.field("departmentId", "INVALID", "That department does not exist"));
			}
			else if (!d.isActive() && t.isActive()) {
				errors.add(ErrorItem.field("departmentId", "DEPARTMENT_INACTIVE", "That department is inactive"));
			}
		}
	}

	private void throwIfInvalid(Input in) {
		if (!in.errors.isEmpty()) {
			throw new UnprocessableException(in.errors);
		}
	}

	private JobTitle find(UUID id) {
		return repo.findByIdAndOrgId(id, currentUser.orgId())
				.orElseThrow(() -> new NotFoundException("Title " + id + " not found"));
	}
}
