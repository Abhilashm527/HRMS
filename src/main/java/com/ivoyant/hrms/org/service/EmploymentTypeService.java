package com.ivoyant.hrms.org.service;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.event.EmploymentTypeCreatedEvent;
import com.ivoyant.hrms.common.event.EmploymentTypeDeletedEvent;
import com.ivoyant.hrms.common.event.EmploymentTypeUpdatedEvent;
import com.ivoyant.hrms.common.event.LeaveEligibilityChangedEvent;
import com.ivoyant.hrms.common.event.PayrollEligibilityChangedEvent;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.security.ModuleAccess;
import com.ivoyant.hrms.common.web.ConflictException;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.InUseException;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.common.web.StaleException;
import com.ivoyant.hrms.common.web.UnprocessableException;
import com.ivoyant.hrms.org.EmploymentTypes;
import com.ivoyant.hrms.org.TypeView;
import com.ivoyant.hrms.org.domain.EmploymentType;
import com.ivoyant.hrms.org.dto.EmploymentTypeDtos;
import com.ivoyant.hrms.org.repo.EmploymentTypeRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.PersistenceContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Employment types (full-time, intern, contractor ...). A type sets probation and notice for new hires and resignations,
 * and whether its people are in leave policies and payroll. Editing a type never rewrites existing people's dates.
 */
@Service
public class EmploymentTypeService implements EmploymentTypes {

	private static final String ENTITY = "EmploymentType";
	private static final Pattern CODE = Pattern.compile("^[A-Z]{2,4}$");
	private static final Map<String, String> COLUMNS = Map.of("name", "name", "code", "code", "probationMonths", "prob",
			"noticeDays", "notice", "leaveEligible", "leave", "payrollEligible", "payroll", "active", "active");

	@PersistenceContext
	private EntityManager em;

	private final EmploymentTypeRepository repo;
	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final ModuleAccess access;
	private final Audit audit;
	private final ApplicationEventPublisher events;

	public EmploymentTypeService(EmploymentTypeRepository repo, JdbcClient jdbc, CurrentUser currentUser, ModuleAccess access,
			Audit audit, ApplicationEventPublisher events) {
		this.repo = repo;
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.access = access;
		this.audit = audit;
		this.events = events;
	}

	// ================================================================== reads

	private EmploymentTypeDtos.Node node(EmploymentType t, Map<UUID, Integer> counts) {
		return new EmploymentTypeDtos.Node(t.getId(), t.getName(), t.getCode(), t.getProbationMonths(), t.getNoticeDays(),
				t.isLeaveEligible(), t.isPayrollEligible(), t.isFixedTerm(), t.getSort(), t.isActive(),
				counts == null ? null : counts.getOrDefault(t.getId(), 0), t.getVersion(), List.of());
	}

	private Map<UUID, Integer> headcounts() {
		Map<UUID, Integer> out = new HashMap<>();
		jdbc.sql("select employment_type_id, count(*) from employee where org_id = :org and status <> 'EXITED' and employment_type_id is not null group by employment_type_id")
				.param("org", currentUser.orgId()).query((rs, i) -> {
					out.put(rs.getObject(1, UUID.class), rs.getInt(2));
					return 0;
				}).list();
		return out;
	}

	/** Admins get the full rows; everyone else the short picker form. */
	@Transactional(readOnly = true)
	public Object list(Boolean active, boolean stats) {
		List<EmploymentType> rows = repo.findByOrgId(currentUser.orgId()).stream().filter(t -> active == null || t.isActive() == active)
				.sorted(Comparator.comparingInt(EmploymentType::getSort).thenComparing(EmploymentType::getName, String.CASE_INSENSITIVE_ORDER)).toList();
		if (!access.has("org", "VIEW")) {
			return rows.stream().map(t -> new EmploymentTypeDtos.Option(t.getId(), t.getName(), t.getCode(), t.isFixedTerm(), t.getProbationMonths())).toList();
		}
		Map<UUID, Integer> counts = stats ? headcounts() : null;
		return rows.stream().map(t -> node(t, counts)).toList();
	}

	@Transactional(readOnly = true)
	public List<EmploymentTypeDtos.Option> options() {
		return repo.findByOrgId(currentUser.orgId()).stream().filter(EmploymentType::isActive)
				.sorted(Comparator.comparingInt(EmploymentType::getSort).thenComparing(EmploymentType::getName, String.CASE_INSENSITIVE_ORDER))
				.map(t -> new EmploymentTypeDtos.Option(t.getId(), t.getName(), t.getCode(), t.isFixedTerm(), t.getProbationMonths())).toList();
	}

	@Transactional(readOnly = true)
	public EmploymentTypeDtos.Node detail(UUID id) {
		return node(find(id), headcounts());
	}

	@Transactional(readOnly = true)
	public EmploymentTypeDtos.Usage usage(UUID id) {
		EmploymentType t = find(id);
		long employees = count("select count(*) from employee where employment_type_id = :id and status <> 'EXITED'", id);
		long exited = count("select count(*) from employee where employment_type_id = :id and status = 'EXITED'", id);
		long history = count("select count(*) from employee_type_history where from_type_id = :id or to_type_id = :id", id);
		String message = null;
		if (employees > 0) {
			message = employees + (employees == 1 ? " person has " : " people have ") + t.getName()
					+ " as their employment type. Move them first, or mark it inactive.";
		}
		else if (exited + history > 0) {
			message = t.getName() + " is in the history of current or former employees. Mark it inactive instead of deleting it.";
		}
		// account rules and invite templates live in lists that do not point at types by id yet
		return new EmploymentTypeDtos.Usage(employees, exited, history, 0, 0, message);
	}

	private long count(String sql, UUID id) {
		return jdbc.sql(sql).param("id", id).query(Long.class).single();
	}

	// ---- the facade ----

	private static TypeView view(EmploymentType t) {
		return new TypeView(t.getId(), t.getName(), t.getCode(), t.getProbationMonths(), t.getNoticeDays(), t.isLeaveEligible(),
				t.isPayrollEligible(), t.isFixedTerm(), t.isActive());
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<TypeView> get(UUID id) {
		return repo.findByIdAndOrgId(id, currentUser.orgId()).map(EmploymentTypeService::view);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<TypeView> find(String idNameOrCode) {
		if (idNameOrCode == null || idNameOrCode.isBlank()) {
			return Optional.empty();
		}
		String key = idNameOrCode.trim();
		String normalised = key.toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
		return repo.findByOrgId(currentUser.orgId()).stream().filter(t -> t.getId().toString().equalsIgnoreCase(key)
				|| t.getName().equalsIgnoreCase(key) || t.getCode().equalsIgnoreCase(key)
				|| t.getName().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_').equals(normalised))
				.findFirst().map(EmploymentTypeService::view);
	}

	@Override
	public boolean leaveEligible(UUID id) {
		return get(id).map(TypeView::leaveEligible).orElse(false);
	}

	@Override
	public boolean payrollEligible(UUID id) {
		return get(id).map(TypeView::payrollEligible).orElse(false);
	}

	@Override
	public int noticeDays(UUID id) {
		return get(id).map(TypeView::noticeDays).orElse(0);
	}

	@Override
	public int probationMonths(UUID id) {
		return get(id).map(TypeView::probationMonths).orElse(0);
	}

	// ================================================================== create / update

	@Transactional
	public EmploymentTypeDtos.Node create(Map<String, Object> body) {
		deferFlush();
		EmploymentType t = new EmploymentType();
		t.setOrgId(currentUser.orgId());
		Input in = new Input(body);
		in.applyTo(t);
		validate(t, in, true);
		throwIfInvalid(in);
		List<EmploymentType> all = new ArrayList<>(repo.findByOrgId(t.getOrgId()));
		all.add(t);
		throwFirst(duplicates(all, List.of(t)));
		repo.saveAndFlush(t);
		EmploymentTypeDtos.Node out = node(t, null);
		audit.created("org", ENTITY, t.getId(), out);
		events.publishEvent(new EmploymentTypeCreatedEvent(t.getId()));
		return out;
	}

	@Transactional
	public EmploymentTypeDtos.Node update(UUID id, Map<String, Object> patch, String ifMatch) {
		deferFlush();
		EmploymentType t = find(id);
		if (ifMatch != null && !ifMatch.isBlank()
				&& !ifMatch.replace("W/", "").replace("\"", "").trim().equals(String.valueOf(t.getVersion()))) {
			throw new StaleException("Someone else changed this employment type. Reload and try again.");
		}
		EmploymentTypeDtos.Node before = node(t, null);
		int oldProbation = t.getProbationMonths();
		int oldNotice = t.getNoticeDays();
		boolean oldLeave = t.isLeaveEligible();
		boolean oldPayroll = t.isPayrollEligible();
		boolean wasActive = t.isActive();
		Input in = new Input(patch);
		in.applyTo(t);
		validate(t, in, false);
		throwIfInvalid(in);
		throwFirst(duplicates(repo.findByOrgId(t.getOrgId()), List.of(t)));
		List<String> warnings = new ArrayList<>();
		if (oldProbation != t.getProbationMonths() || oldNotice != t.getNoticeDays()) {
			long onProbation = count("select count(*) from employee where employment_type_id = :id and status <> 'EXITED' and probation_end_date >= current_date", id);
			if (onProbation > 0) {
				warnings.add(onProbation + (onProbation == 1 ? " person on probation keeps" : " people on probation keep") + " their current end date");
			}
		}
		if (wasActive && !t.isActive()) {
			long people = count("select count(*) from employee where employment_type_id = :id and status <> 'EXITED'", id);
			if (people > 0) {
				warnings.add(people + (people == 1 ? " person still has " : " people still have ") + t.getName());
			}
		}
		repo.saveAndFlush(t);
		EmploymentTypeDtos.Node after = node(t, null).withWarnings(warnings);
		audit.updated("org", ENTITY, id, before, after);
		events.publishEvent(new EmploymentTypeUpdatedEvent(id, List.copyOf(in.touched)));
		if (oldLeave != t.isLeaveEligible()) {
			events.publishEvent(new LeaveEligibilityChangedEvent(id, t.isLeaveEligible()));
		}
		if (oldPayroll != t.isPayrollEligible()) {
			// no payroll runs exist yet, so there is no open run to refuse for
			events.publishEvent(new PayrollEligibilityChangedEvent(id, t.isPayrollEligible()));
		}
		return after;
	}

	private void deferFlush() {
		em.setFlushMode(FlushModeType.COMMIT);
	}

	private record Problem(EmploymentType on, String field, String message) {
	}

	private List<Problem> duplicates(List<EmploymentType> all, List<EmploymentType> changed) {
		List<Problem> out = new ArrayList<>();
		for (EmploymentType e : changed) {
			String name = e.getName() == null ? "" : e.getName().toLowerCase(Locale.ROOT);
			if (all.stream().anyMatch(o -> o != e && !o.getId().equals(e.getId()) && o.getName() != null && o.getName().toLowerCase(Locale.ROOT).equals(name))) {
				out.add(new Problem(e, "name", "An employment type with this name already exists"));
			}
			if (e.getCode() != null && all.stream().anyMatch(o -> o != e && !o.getId().equals(e.getId()) && e.getCode().equalsIgnoreCase(o.getCode()))) {
				out.add(new Problem(e, "code", "An employment type with this code already exists"));
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

	/** Hard delete, only when nothing points at the type; {@code reassignTo} first moves current employees (history keeps a REASSIGN row). */
	@Transactional
	public void delete(UUID id, UUID reassignTo) {
		EmploymentType t = find(id);
		EmploymentTypeDtos.Node before = node(t, null);
		if (reassignTo != null) {
			reassign(t, reassignTo);
		}
		EmploymentTypeDtos.Usage usage = usage(id);
		if (usage.blocksDelete()) {
			throw new InUseException(usage.message() != null ? usage.message() : "This employment type is still used.",
					Map.of("employees", usage.employees(), "exitedEmployees", usage.exitedEmployees(), "historyRows", usage.historyRows()));
		}
		repo.delete(t);
		repo.flush();
		audit.deleted("org", ENTITY, id, before);
		events.publishEvent(new EmploymentTypeDeletedEvent(id, reassignTo));
	}

	private void reassign(EmploymentType from, UUID targetId) {
		EmploymentType target = find(targetId);
		if (targetId.equals(from.getId())) {
			throw new ConflictException("Choose a different employment type to move everyone to");
		}
		if (!target.isActive()) {
			throw new ConflictException(target.getName() + " is inactive. Choose an active type");
		}
		long exited = count("select count(*) from employee where employment_type_id = :id and status = 'EXITED'", from.getId());
		if (exited > 0) {
			throw new InUseException(exited + (exited == 1 ? " former employee has " : " former employees have ") + from.getName()
					+ " in their history. Mark the type inactive instead of deleting it.", Map.of("exitedEmployees", exited));
		}
		jdbc.sql("""
				insert into employee_type_history (org_id, employee_id, from_type_id, to_type_id, effective_date, reason, created_by)
				select org_id, id, :from, :to, current_date, 'REASSIGN', :by from employee where employment_type_id = :from and status <> 'EXITED'
				""").param("from", from.getId()).param("to", targetId).param("by", currentUser.employeeId(), java.sql.Types.OTHER).update();
		jdbc.sql("update employee set employment_type_id = :to, employment_type = :name where employment_type_id = :from")
				.param("to", targetId).param("name", target.getName()).param("from", from.getId()).update();
		// history that mentioned the old type now mentions the target, so the old type can go
		jdbc.sql("update employee_type_history set from_type_id = null where from_type_id = :from").param("from", from.getId()).update();
		jdbc.sql("update employee_type_history set to_type_id = :to where to_type_id = :from").param("to", targetId).param("from", from.getId()).update();
	}

	// ================================================================== bulk save (Settings → Onboarding → Employment types)

	@Transactional
	public void bulkUpsert(List<Map<String, Object>> rows) {
		deferFlush();
		UUID org = currentUser.orgId();
		Map<UUID, EmploymentType> existing = new HashMap<>();
		repo.findByOrgId(org).forEach(t -> existing.put(t.getId(), t));
		Map<UUID, Integer> indexOf = new HashMap<>();
		Map<UUID, boolean[]> was = new HashMap<>();
		List<EmploymentType> changed = new ArrayList<>();
		List<EmploymentType> created = new ArrayList<>();
		List<ErrorItem> errors = new ArrayList<>();
		for (int i = 0; i < rows.size(); i++) {
			Map<String, Object> row = rows.get(i);
			UUID id = UUID.fromString(row.get("id").toString());
			boolean isNew = !existing.containsKey(id);
			EmploymentType t = isNew ? new EmploymentType() : existing.get(id);
			if (isNew) {
				t.setId(id);
				t.setOrgId(org);
			}
			else {
				was.put(id, new boolean[] {t.isLeaveEligible(), t.isPayrollEligible()});
			}
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("name", row.get("name"));
			body.put("code", row.get("code"));
			body.put("probationMonths", row.get("prob"));
			body.put("noticeDays", row.get("notice"));
			body.put("leaveEligible", row.get("leave") != null && Boolean.parseBoolean(row.get("leave").toString()));
			body.put("payrollEligible", row.get("payroll") != null && Boolean.parseBoolean(row.get("payroll").toString()));
			body.put("active", row.get("active") == null || Boolean.parseBoolean(row.get("active").toString()));
			Input in = new Input(body);
			in.applyTo(t);
			validate(t, in, true);
			final int index = i;
			in.errors.forEach(e -> errors.add(ErrorItem.cell("emptypes", index, COLUMNS.getOrDefault(e.fieldKey(), e.fieldKey()), e.message())));
			indexOf.put(id, i);
			changed.add(t);
			if (isNew) {
				created.add(t);
			}
		}
		if (errors.isEmpty()) {
			List<EmploymentType> all = new ArrayList<>(existing.values());
			all.addAll(created);
			for (Problem p : duplicates(all, changed)) {
				errors.add(ErrorItem.cell("emptypes", indexOf.get(p.on().getId()), COLUMNS.getOrDefault(p.field(), p.field()), p.message()));
			}
		}
		if (!errors.isEmpty()) {
			throw new UnprocessableException(errors);
		}
		created.forEach(repo::saveAndFlush);
		repo.flush();
		for (EmploymentType t : changed) {
			if (!existing.containsKey(t.getId())) {
				events.publishEvent(new EmploymentTypeCreatedEvent(t.getId()));
				continue;
			}
			events.publishEvent(new EmploymentTypeUpdatedEvent(t.getId(), List.of("list")));
			if (was.get(t.getId())[0] != t.isLeaveEligible()) {
				events.publishEvent(new LeaveEligibilityChangedEvent(t.getId(), t.isLeaveEligible()));
			}
			if (was.get(t.getId())[1] != t.isPayrollEligible()) {
				events.publishEvent(new PayrollEligibilityChangedEvent(t.getId(), t.isPayrollEligible()));
			}
		}
	}

	@Transactional
	public void bulkDelete(Set<UUID> keep) {
		repo.findByOrgId(currentUser.orgId()).stream().filter(t -> !keep.contains(t.getId())).forEach(t -> delete(t.getId(), null));
	}

	// ================================================================== input and validation

	private static final class Input {

		final Map<String, Object> body;
		final Set<String> touched = new LinkedHashSet<>();
		final List<ErrorItem> errors = new ArrayList<>();

		Input(Map<String, Object> body) {
			this.body = body;
		}

		void applyTo(EmploymentType t) {
			for (Map.Entry<String, Object> e : body.entrySet()) {
				String k = e.getKey();
				Object v = e.getValue();
				boolean known = true;
				try {
					switch (k) {
						case "name" -> t.setName(text(v) == null ? null : text(v).replaceAll("\\s+", " "));
						case "code" -> t.setCode(text(v) == null ? null : text(v).toUpperCase(Locale.ROOT));
						case "probationMonths" -> t.setProbationMonths(whole(v, "PROBATION"));
						case "noticeDays" -> t.setNoticeDays(whole(v, "NOTICE"));
						case "leaveEligible" -> t.setLeaveEligible(v != null && bool(v));
						case "payrollEligible" -> t.setPayrollEligible(v != null && bool(v));
						case "fixedTerm" -> t.setFixedTerm(v != null && bool(v));
						case "sort" -> t.setSort(v == null ? 0 : whole(v, "SORT"));
						case "active" -> t.setActive(v == null || bool(v));
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

		private static int whole(Object v, String what) {
			if (v == null || v.toString().isBlank()) {
				return 0;
			}
			if (v instanceof Number n) {
				return n.intValue();
			}
			return Integer.parseInt(v.toString().trim());
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

	private void validate(EmploymentType t, Input in, boolean create) {
		List<ErrorItem> errors = in.errors;
		java.util.function.Predicate<String> any = key -> create || in.touched.contains(key);
		if (any.test("name")) {
			if (t.getName() == null) {
				errors.add(ErrorItem.field("name", "REQUIRED", "Every employment type needs a name"));
			}
			else if (t.getName().length() < 2 || t.getName().length() > 50) {
				errors.add(ErrorItem.field("name", "NAME_LENGTH", "Name must be 2–50 characters"));
			}
		}
		if (any.test("code")) {
			if (t.getCode() == null) {
				errors.add(ErrorItem.field("code", "REQUIRED", "Every employment type needs a code"));
			}
			else if (!CODE.matcher(t.getCode()).matches()) {
				errors.add(ErrorItem.field("code", "CODE_FORMAT", "Code must be 2–4 letters"));
			}
		}
		if (t.getProbationMonths() < 0 || t.getProbationMonths() > 24) {
			errors.add(ErrorItem.field("probationMonths", "PROBATION_RANGE", "Probation must be 0–24 months"));
		}
		if (t.getNoticeDays() < 0 || t.getNoticeDays() > 180) {
			errors.add(ErrorItem.field("noticeDays", "NOTICE_RANGE", "Notice must be 0–180 days"));
		}
	}

	private void throwIfInvalid(Input in) {
		if (!in.errors.isEmpty()) {
			throw new UnprocessableException(in.errors);
		}
	}

	private EmploymentType find(UUID id) {
		return repo.findByIdAndOrgId(id, currentUser.orgId()).orElseThrow(() -> new NotFoundException("Employment type " + id + " not found"));
	}
}
