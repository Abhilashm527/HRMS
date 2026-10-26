package com.ivoyant.hrms.employee.service;

import com.ivoyant.hrms.common.authz.AuthzService;
import com.ivoyant.hrms.common.authz.FgaOutbox;
import com.ivoyant.hrms.common.authz.FgaTuples;
import com.ivoyant.hrms.common.event.EmployeeDepartmentChangedEvent;
import com.ivoyant.hrms.common.event.EmployeeExitedEvent;
import com.ivoyant.hrms.common.event.EmployeeLocationChangedEvent;
import com.ivoyant.hrms.common.event.EmployeePositionChangedEvent;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.BadRequestException;
import com.ivoyant.hrms.common.web.ConflictException;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.common.web.UnprocessableException;
import com.ivoyant.hrms.config.EmployeeLookup;
import com.ivoyant.hrms.config.RecordFieldStore;
import com.ivoyant.hrms.config.RecordPolicy;
import com.ivoyant.hrms.employee.domain.Employee;
import com.ivoyant.hrms.employee.domain.EmployeeStatus;
import com.ivoyant.hrms.employee.repo.EmployeeRepository;
import com.ivoyant.hrms.org.OrgDirectory;
import com.ivoyant.hrms.org.OrgSettings;
import com.ivoyant.hrms.org.TitleView;
import com.ivoyant.hrms.org.EmploymentTypes;
import com.ivoyant.hrms.org.Positions;
import com.ivoyant.hrms.org.TypeView;
import com.ivoyant.hrms.common.event.ManagerChangedEvent;
import com.ivoyant.hrms.common.event.EmployeeTypeChangedEvent;
import com.ivoyant.hrms.org.Titles;
import com.ivoyant.hrms.common.web.CodedConflictException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The employee module's hooks into Fields &amp; forms. Employee-profile values live on the employee row (real
 * columns plus the {@code custom} jsonb); this class reads and writes them, and enforces the business rules every
 * write must pass, whether it comes from a PATCH or from an approved change request.
 */
@Component
public class EmployeeRecords implements RecordFieldStore, RecordPolicy, EmployeeLookup {

	/** Areas whose record is an employee. */
	private static final Set<String> EMPLOYEE_AREAS = Set.of(EmployeeFields.AREA, "payroll", "performance");

	private static final Map<String, String> COLUMNS = Map.ofEntries(Map.entry("first_name", "first_name"),
			Map.entry("last_name", "last_name"), Map.entry("work_email", "work_email"),
			Map.entry("personal_email", "personal_email"), Map.entry("phone", "phone"),
			Map.entry("joining_date", "joining_date"), Map.entry("exit_date", "exit_date"),
			Map.entry("department", "department_id"), Map.entry("designation", "designation_id"), Map.entry("title", "title_id"),
			Map.entry("manager", "manager_id"), Map.entry("location", "location_id"),
			Map.entry("employment_type", "employment_type"), Map.entry("status", "status"));

	private final EmployeeRepository employees;
	private final OrgDirectory org;
	private final CurrentUser currentUser;
	private final FgaOutbox fga;
	private final AuthzService authz;
	private final JdbcClient jdbc;
	private final ApplicationEventPublisher events;
	private final OrgSettings settings;
	private final Titles titles;
	private final EmploymentTypes types;
	private final Positions positions;

	public EmployeeRecords(EmployeeRepository employees, OrgDirectory org, CurrentUser currentUser, FgaOutbox fga,
			AuthzService authz, JdbcClient jdbc, ApplicationEventPublisher events, OrgSettings settings,
			Titles titles, EmploymentTypes types, Positions positions) {
		this.settings = settings;
		this.titles = titles;
		this.types = types;
		this.positions = positions;
		this.employees = employees;
		this.org = org;
		this.currentUser = currentUser;
		this.fga = fga;
		this.authz = authz;
		this.jdbc = jdbc;
		this.events = events;
	}

	// ---- RecordFieldStore (employee_profile) ----

	@Override
	public String areaKey() {
		return EmployeeFields.AREA;
	}

	@Override
	@Transactional(readOnly = true)
	public Map<String, Object> read(UUID recordId) {
		Employee e = find(recordId);
		Map<String, Object> out = new HashMap<>(e.getCustom());
		for (String key : EmployeeFields.COLUMN_KEYS) {
			out.put(key, EmployeeFields.read(e, key));
		}
		return out;
	}

	/** Applies validated values, then the rules a change to an employee must always satisfy. */
	@Override
	@Transactional
	public void apply(UUID recordId, Map<String, Object> values) {
		apply(recordId, values, null, null);
	}

	/** {@link #apply(UUID, Map)} for a title change with its own reason and effective date (the title history records them). */
	@Transactional
	public void apply(UUID recordId, Map<String, Object> values, String titleReason, java.time.LocalDate effective) {
		Employee e = find(recordId);
		UUID oldManager = e.getManagerId();
		UUID oldDepartment = e.getDepartmentId();
		UUID oldPosition = e.getDesignationId();
		UUID oldTitle = e.getTitleId();
		UUID oldLocation = e.getLocationId();
		UUID oldType = e.getEmploymentTypeId();
		EmployeeStatus oldStatus = e.getStatus();
		for (Map.Entry<String, Object> entry : values.entrySet()) {
			EmployeeFields.apply(e, entry.getKey(), entry.getValue());
		}
		validateOrgRefs(e.getDepartmentId(), e.getDesignationId(), e.getLocationId());
		if (e.getLocationId() != null && !Objects.equals(oldLocation, e.getLocationId())) {
			requireActiveLocation(e.getLocationId());
		}
		if (e.getDepartmentId() != null && !Objects.equals(oldDepartment, e.getDepartmentId())) {
			requireActiveDepartment(e.getDepartmentId());
		}
		if (e.getDesignationId() != null && !Objects.equals(oldPosition, e.getDesignationId())) {
			requireActivePosition(e.getDesignationId());
		}
		applyTitleRules(e, values, oldTitle, oldPosition, oldDepartment);
		if (values.containsKey("designation") && e.getDesignationId() == null && settings.positionMandatory()) {
			throw new BadRequestException("Position is mandatory");
		}
		if (values.containsKey("employment_type")) {
			applyEmploymentType(e, oldType);
		}
		if (values.containsKey("manager") && !Objects.equals(oldManager, e.getManagerId())) {
			requireValidManager(e);
		}
		if (e.getManagerId() != null && !Objects.equals(oldManager, e.getManagerId())) {
			checkManager(e);
			requireReportingDepth(e.getManagerId(), e.getId());
		}
		if (oldStatus != EmployeeStatus.EXITED && e.getStatus() == EmployeeStatus.EXITED) {
			requireSuccessorForTop(e);
		}
		if (employees.existsByWorkEmailIgnoreCaseAndIdNot(e.getWorkEmail(), recordId)) {
			throw new ConflictException("Work email '" + e.getWorkEmail() + "' is already in use");
		}
		e.setDisplayName(displayName(e));
		employees.saveAndFlush(e);
		syncFga(e, oldManager, oldDepartment, oldLocation);
		if (!Objects.equals(oldManager, e.getManagerId())) {
			recordManagerChange(e, oldManager, "CHANGE", null);
		}
		if (!Objects.equals(oldType, e.getEmploymentTypeId())) {
			recordTypeChange(e, oldType, "CORRECTION", java.time.LocalDate.now());
		}
		if (!Objects.equals(oldTitle, e.getTitleId()) && values.containsKey("title")) {
			recordTitleChange(e, oldTitle, titleReason, effective);
		}
		if (!Objects.equals(oldPosition, e.getDesignationId())) {
			events.publishEvent(new EmployeePositionChangedEvent(e.getId(), oldPosition, e.getDesignationId()));
		}
		if (!Objects.equals(oldDepartment, e.getDepartmentId())) {
			events.publishEvent(new EmployeeDepartmentChangedEvent(e.getId(), oldDepartment, e.getDepartmentId()));
		}
		if (oldStatus != EmployeeStatus.EXITED && e.getStatus() == EmployeeStatus.EXITED) {
			events.publishEvent(new EmployeeExitedEvent(e.getId()));
		}
		if (!Objects.equals(oldLocation, e.getLocationId())) {
			events.publishEvent(new EmployeeLocationChangedEvent(e.getId(), oldLocation, e.getLocationId(),
					java.time.LocalDate.now()));
		}
	}

	@Override
	public long countValues(String fieldKey) {
		UUID orgId = currentUser.orgId();
		String column = COLUMNS.get(fieldKey);
		if (column != null) {
			return jdbc.sql("select count(*) from employee where org_id = :org and " + column + " is not null")
					.param("org", orgId).query(Long.class).single();
		}
		return jdbc.sql("select count(*) from employee where org_id = :org and jsonb_exists(custom, :key)")
				.param("org", orgId).param("key", fieldKey).query(Long.class).single();
	}

	// ---- RecordPolicy ----

	@Override
	public boolean supports(String areaKey) {
		return EMPLOYEE_AREAS.contains(areaKey);
	}

	@Override
	@Transactional(readOnly = true)
	public UUID owner(String areaKey, UUID recordId) {
		return employees.findByIdAndOrgId(recordId, currentUser.orgId()).map(Employee::getId).orElse(null);
	}

	@Override
	public boolean canOpen(String areaKey, UUID recordId) {
		return authz.can("can_view_basic", FgaTuples.employee(recordId));
	}

	@Override
	@Transactional(readOnly = true)
	public String label(String areaKey, UUID recordId) {
		return employees.findByIdAndOrgId(recordId, currentUser.orgId())
				.map(e -> e.getDisplayName() + " · " + e.getEmployeeCode()).orElse(recordId.toString());
	}

	// ---- EmployeeLookup ----

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, String> names(Collection<UUID> ids) {
		if (ids.isEmpty()) {
			return Map.of();
		}
		return employees.findAllById(ids).stream().filter(e -> e.getOrgId().equals(currentUser.orgId()))
				.collect(Collectors.toMap(Employee::getId, Employee::getDisplayName));
	}

	// ---- shared rules (also used by EmployeeService.create) ----

	Employee find(UUID id) {
		return employees.findByIdAndOrgId(id, currentUser.orgId())
				.orElseThrow(() -> new NotFoundException("Employee " + id + " not found"));
	}

	static String displayName(Employee e) {
		return (e.getFirstName() + " " + e.getLastName()).trim();
	}

	/** A new assignment may only use an active department. */
	void requireActiveDepartment(UUID departmentId) {
		if (!org.activeDepartmentNames(List.of(departmentId)).containsKey(departmentId)) {
			throw new BadRequestException("Department " + departmentId + " is not active");
		}
	}

	/**
	 * The title decides the position: choosing a title sets the position, and a position or department that no longer fits the
	 * title is refused (change the title instead). A title limited to a department fits that department and the ones below it.
	 */
	private void applyTitleRules(Employee e, Map<String, Object> values, UUID oldTitle, UUID oldPosition, UUID oldDepartment) {
		boolean titleChanged = !Objects.equals(oldTitle, e.getTitleId());
		if (titleChanged && e.getTitleId() != null) {
			TitleView t = titles.get(e.getTitleId())
					.orElseThrow(() -> new BadRequestException("Title " + e.getTitleId() + " does not exist"));
			if (!t.active()) {
				throw new UnprocessableException(List.of(ErrorItem.field("title", "TITLE_INACTIVE",
						t.name() + " is inactive. Choose an active title")));
			}
			if (!values.containsKey("designation")) {
				e.setDesignationId(t.positionId());
			}
		}
		if (e.getTitleId() == null) {
			return;
		}
		boolean positionChanged = !Objects.equals(oldPosition, e.getDesignationId());
		boolean departmentChanged = !Objects.equals(oldDepartment, e.getDepartmentId());
		if (!titleChanged && !positionChanged && !departmentChanged) {
			return;
		}
		TitleView t = titles.get(e.getTitleId()).orElseThrow(() -> new BadRequestException("Title does not exist"));
		if (!Objects.equals(e.getDesignationId(), t.positionId())) {
			String position = t.positionId() == null ? "no position" : org.designationNames(List.of(t.positionId())).get(t.positionId());
			throw new CodedConflictException("POSITION_TITLE_MISMATCH",
					t.name() + " is a " + position + " title. Change the title instead.");
		}
		if (!titles.validFor(t.id(), e.getDepartmentId())) {
			String scope = org.departmentNames(List.of(t.departmentId())).get(t.departmentId());
			if (titleChanged) {
				throw new UnprocessableException(List.of(ErrorItem.field("title", "TITLE_NOT_IN_DEPARTMENT",
						t.name() + " is only for " + scope + ". " + e.getDisplayName() + " is in " + departmentName(e.getDepartmentId()) + ".")));
			}
			List<String> suggestions = titles.optionsFor(e.getDepartmentId()).stream()
					.filter(o -> Objects.equals(o.positionId(), t.positionId())).map(TitleView::name).toList();
			throw new CodedConflictException("TITLE_NOT_IN_DEPARTMENT", t.name() + " is only for " + scope
					+ ". Pick a title that fits " + departmentName(e.getDepartmentId()) + " as part of this transfer.", suggestions);
		}
		if (departmentChanged) {
			List<String> misfits = jdbc.sql("select x.title_id from employee_extra_title x where x.employee_id = :e")
					.param("e", e.getId()).query(UUID.class).list().stream().filter(x -> !titles.validFor(x, e.getDepartmentId()))
					.map(x -> titles.names(List.of(x)).get(x)).toList();
			if (!misfits.isEmpty()) {
				throw new CodedConflictException("TITLE_NOT_IN_DEPARTMENT", "Extra titles that don't fit "
						+ departmentName(e.getDepartmentId()) + ": remove them as part of this transfer.", misfits);
			}
		}
	}

	private String departmentName(UUID id) {
		return id == null ? "no department" : org.departmentNames(List.of(id)).getOrDefault(id, "no department");
	}

	/** Keeps the title timeline: closes the open primary row and opens one for the new title. */
	private void recordTitleChange(Employee e, UUID oldTitle, String reason, java.time.LocalDate effective) {
		java.time.LocalDate today = effective == null ? java.time.LocalDate.now() : effective;
		String why = reason != null ? reason : oldTitle == null ? "HIRE" : "CORRECTION";
		jdbc.sql("update employee_title_history set to_date = :d where employee_id = :e and is_primary and to_date is null")
				.param("d", today).param("e", e.getId()).update();
		if (e.getTitleId() != null) {
			jdbc.sql("""
					insert into employee_title_history (org_id, employee_id, title_id, is_primary, from_date, reason, created_by)
					values (:org, :e, :t, true, :d, :reason, :by)""").param("org", e.getOrgId()).param("e", e.getId())
					.param("t", e.getTitleId()).param("d", today).param("reason", why)
					.param("by", currentUser.employeeId(), java.sql.Types.OTHER).update();
		}
		events.publishEvent(new com.ivoyant.hrms.common.event.EmployeeTitleChangedEvent(e.getId(), oldTitle, e.getTitleId(),
				List.of(), List.of(), why, today));
	}

	/** Called by the hire flow once the employee exists. */
	void recordHireTitle(Employee e) {
		if (e.getTitleId() != null) {
			recordTitleChange(e, null, "HIRE", null);
		}
	}

	/** A hire with a title: the title sets the position (a position given as well must match) and must fit the department. */
	com.ivoyant.hrms.employee.dto.CreateEmployeeRequest resolveTitle(com.ivoyant.hrms.employee.dto.CreateEmployeeRequest r) {
		if (r.titleId() == null) {
			return r;
		}
		TitleView t = titles.get(r.titleId()).orElseThrow(() -> new BadRequestException("Title " + r.titleId() + " does not exist"));
		if (!t.active()) {
			throw new UnprocessableException(List.of(ErrorItem.field("titleId", "TITLE_INACTIVE", t.name() + " is inactive. Choose an active title")));
		}
		if (r.designationId() != null && !r.designationId().equals(t.positionId())) {
			throw new CodedConflictException("POSITION_TITLE_MISMATCH", t.name() + " is a "
					+ org.designationNames(List.of(t.positionId())).get(t.positionId()) + " title. Change the title instead.");
		}
		if (!titles.validFor(t.id(), r.departmentId())) {
			throw new UnprocessableException(List.of(ErrorItem.field("titleId", "TITLE_NOT_IN_DEPARTMENT", t.name()
					+ " is only for " + org.departmentNames(List.of(t.departmentId())).get(t.departmentId()) + ".")));
		}
		return new com.ivoyant.hrms.employee.dto.CreateEmployeeRequest(r.employeeCode(), r.firstName(), r.lastName(),
				r.workEmail(), r.personalEmail(), r.phone(), r.departmentId(), t.positionId(), r.titleId(), r.locationId(),
				r.managerId(), r.employmentType(), r.joiningDate(), r.contractEndDate(), r.custom());
	}

	// ---- employment type, manager and reporting history ----

	/** The profile's employment type value is the type's name (older clients) or id; both name the same type. */
	private void applyEmploymentType(Employee e, UUID oldType) {
		TypeView t = types.find(e.getEmploymentType()).orElseThrow(() -> new UnprocessableException(List.of(
				ErrorItem.field("employment_type", "INVALID", "'" + e.getEmploymentType() + "' is not an employment type"))));
		if (!t.id().equals(oldType) && !t.active()) {
			throw new UnprocessableException(List.of(ErrorItem.field("employment_type", "TYPE_INACTIVE", t.name() + " is inactive. Choose an active type")));
		}
		e.setEmploymentTypeId(t.id());
		e.setEmploymentType(t.name());
	}

	/** Resolves the type named on a hire (id, name or code) and checks it can be given to someone. */
	TypeView requireHireType(String idNameOrCode) {
		TypeView t = types.find(idNameOrCode).orElseThrow(() -> new UnprocessableException(List.of(
				ErrorItem.field("employmentType", "INVALID", "'" + idNameOrCode + "' is not an employment type"))));
		if (!t.active()) {
			throw new UnprocessableException(List.of(ErrorItem.field("employmentType", "TYPE_INACTIVE", t.name() + " is inactive. Choose an active type")));
		}
		return t;
	}

	void recordTypeChange(Employee e, UUID from, String reason, java.time.LocalDate on) {
		jdbc.sql("""
				insert into employee_type_history (org_id, employee_id, from_type_id, to_type_id, effective_date, reason, created_by)
				values (:org, :e, :from, :to, :d, :r, :by)""").param("org", e.getOrgId()).param("e", e.getId())
				.param("from", from, java.sql.Types.OTHER).param("to", e.getEmploymentTypeId()).param("d", on).param("r", reason)
				.param("by", currentUser.employeeId(), java.sql.Types.OTHER).update();
		events.publishEvent(new EmployeeTypeChangedEvent(e.getId(), from, e.getEmploymentTypeId(), on));
	}

	/** A manager must still be here: active or serving notice (a leaver cannot be given reports). */
	void requireValidManager(Employee e) {
		if (e.getManagerId() == null) {
			if (!isTopHolder(e)) {
				throw new UnprocessableException(List.of(ErrorItem.field("manager", "MANAGER_REQUIRED",
						"Everyone except the top position needs a manager.")));
			}
			return;
		}
		Employee manager = find(e.getManagerId());
		if (manager.getStatus() == EmployeeStatus.EXITED) {
			throw new UnprocessableException(List.of(ErrorItem.field("manager", "MANAGER_INACTIVE", manager.getDisplayName() + " has left")));
		}
	}

	/** Only a holder of the organization's top position may have no manager (when a top position is defined). */
	boolean isTopHolder(Employee e) {
		java.util.Optional<UUID> top = positions.top();
		return top.isEmpty() || (e.getDesignationId() != null && top.get().equals(e.getDesignationId()));
	}

	/** The last holder of the top position cannot leave without a successor. */
	void requireSuccessorForTop(Employee e) {
		java.util.Optional<UUID> top = positions.top();
		if (top.isPresent() && top.get().equals(e.getDesignationId())) {
			long others = jdbc.sql("select count(*) from employee where designation_id = :p and status <> 'EXITED' and id <> :e")
					.param("p", top.get()).param("e", e.getId()).query(Long.class).single();
			if (others == 0) {
				throw new ConflictException(e.getDisplayName() + " is the only person in the top position. Name a successor first.");
			}
		}
	}

	/** Keeps the manager timeline and tells the rest of the system. {@code on} defaults to today. */
	void recordManagerChange(Employee e, UUID oldManager, String reason, java.time.LocalDate on) {
		java.time.LocalDate day = on == null ? java.time.LocalDate.now() : on;
		jdbc.sql("update reporting_line_history set to_date = greatest(from_date, :d - 1) where employee_id = :e and to_date is null")
				.param("d", day).param("e", e.getId()).update();
		jdbc.sql("""
				insert into reporting_line_history (org_id, employee_id, manager_id, from_date, reason, created_by)
				values (:org, :e, :m, :d, :r, :by)""").param("org", e.getOrgId()).param("e", e.getId())
				.param("m", e.getManagerId(), java.sql.Types.OTHER).param("d", day).param("r", reason)
				.param("by", currentUser.employeeId(), java.sql.Types.OTHER).update();
		events.publishEvent(new ManagerChangedEvent(e.getId(), oldManager, e.getManagerId(), day, reason));
	}

	/**
	 * A manager change as an HR action: checks, effect, history and events in one step. A future {@code effective} date is not
	 * handled here (see {@link ManagerChangeService}).
	 */
	@Transactional
	public void changeManager(UUID employeeId, UUID managerId, String reason, java.time.LocalDate effective) {
		Employee e = find(employeeId);
		if (e.getStatus() == EmployeeStatus.EXITED) {
			throw new ConflictException(e.getDisplayName() + " has left");
		}
		UUID old = e.getManagerId();
		if (Objects.equals(old, managerId)) {
			return;
		}
		e.setManagerId(managerId);
		requireValidManager(e);
		if (managerId != null) {
			checkManager(e);
			requireReportingDepth(managerId, e.getId());
		}
		employees.saveAndFlush(e);
		syncFga(e, old, e.getDepartmentId(), e.getLocationId());
		recordManagerChange(e, old, reason, effective);
	}

	/**
	 * Sets a manager with no loop or depth check: for a bulk save that was already judged as a whole (swapping two managers is
	 * only valid as a pair). The caller must have validated the final graph.
	 */
	@Transactional
	public void setManagerUnchecked(UUID employeeId, UUID managerId, String reason, java.time.LocalDate effective) {
		Employee e = find(employeeId);
		UUID old = e.getManagerId();
		if (Objects.equals(old, managerId)) {
			return;
		}
		e.setManagerId(managerId);
		employees.saveAndFlush(e);
		syncFga(e, old, e.getDepartmentId(), e.getLocationId());
		recordManagerChange(e, old, reason, effective);
	}

	/** A scheduled change whose date has come: the history row already exists, so only the employee row and the tuples change. */
	@Transactional
	public void applyScheduledManager(UUID employeeId, UUID managerId, java.time.LocalDate effective) {
		Employee e = find(employeeId);
		UUID old = e.getManagerId();
		if (Objects.equals(old, managerId) || e.getStatus() == EmployeeStatus.EXITED) {
			return;
		}
		e.setManagerId(managerId);
		requireValidManager(e);
		if (managerId != null) {
			checkManager(e);
			requireReportingDepth(managerId, e.getId());
		}
		employees.saveAndFlush(e);
		syncFga(e, old, e.getDepartmentId(), e.getLocationId());
		events.publishEvent(new ManagerChangedEvent(e.getId(), old, managerId, effective, "SCHEDULED"));
	}

	/** A new assignment may only use an active position. */
	void requireActivePosition(UUID positionId) {
		if (!org.activeDesignationNames(List.of(positionId)).containsKey(positionId)) {
			throw new UnprocessableException(List.of(ErrorItem.field("designation", "POSITION_INACTIVE",
					"That position is inactive. Choose an active position")));
		}
	}

	/** A new assignment may only use an active location. */
	void requireActiveLocation(UUID locationId) {
		if (!org.activeLocationNames(List.of(locationId)).containsKey(locationId)) {
			throw new BadRequestException("Location " + locationId + " is not active");
		}
	}

	void validateOrgRefs(UUID departmentId, UUID designationId, UUID locationId) {
		if (departmentId != null && !org.departmentNames(List.of(departmentId)).containsKey(departmentId)) {
			throw new BadRequestException("Department " + departmentId + " does not exist");
		}
		if (designationId != null && !org.designationNames(List.of(designationId)).containsKey(designationId)) {
			throw new BadRequestException("Position " + designationId + " does not exist");
		}
		if (locationId != null && !org.locationNames(List.of(locationId)).containsKey(locationId)) {
			throw new BadRequestException("Location " + locationId + " does not exist");
		}
	}

	/**
	 * Reporting chains may not be longer than the organization allows ("Max reporting depth"). The employee's new depth is
	 * the number of managers above them; anyone below them moves down with them.
	 */
	void requireReportingDepth(UUID managerId, UUID employeeId) {
		long above = jdbc.sql("""
				with recursive up as (
				    select id, manager_id, 0 as d from employee where id = :m
				    union all
				    select e.id, e.manager_id, up.d + 1 from employee e join up on e.id = up.manager_id)
				select max(d) from up""").param("m", managerId).query(Long.class).single();
		long below = employeeId == null ? 0 : jdbc.sql("""
				with recursive down as (
				    select id, 0 as h from employee where id = :e
				    union all
				    select c.id, down.h + 1 from employee c join down on c.manager_id = down.id)
				select max(h) from down""").param("e", employeeId).query(Long.class).single();
		long deepest = above + 1 + below;
		if (deepest > settings.maxReportingDepth()) {
			throw new ConflictException("That would make a reporting chain " + deepest + " levels long; the limit is "
					+ settings.maxReportingDepth() + " (Settings → Organization)");
		}
	}

	/** The manager must exist, not be the employee, and not be somewhere below the employee. */
	void checkManager(Employee e) {
		if (e.getManagerId().equals(e.getId())) {
			throw new ConflictException("An employee cannot be their own manager");
		}
		UUID cursor = e.getManagerId();
		for (int depth = 0; cursor != null && depth < 200; depth++) {
			if (cursor.equals(e.getId())) {
				throw new ConflictException("That manager reports (directly or indirectly) to this employee");
			}
			cursor = find(cursor).getManagerId();
		}
	}

	void syncFga(Employee e, UUID oldManager, UUID oldDepartment, UUID oldLocation) {
		UUID id = e.getId();
		if (!Objects.equals(oldManager, e.getManagerId())) {
			if (oldManager != null) {
				FgaTuples.manager(id, oldManager).forEach(fga::delete);
			}
			if (e.getManagerId() != null) {
				FgaTuples.manager(id, e.getManagerId()).forEach(fga::write);
			}
		}
		if (!Objects.equals(oldDepartment, e.getDepartmentId())) {
			if (oldDepartment != null) {
				FgaTuples.department(id, oldDepartment).forEach(fga::delete);
			}
			if (e.getDepartmentId() != null) {
				FgaTuples.department(id, e.getDepartmentId()).forEach(fga::write);
			}
		}
		if (!Objects.equals(oldLocation, e.getLocationId())) {
			if (oldLocation != null) {
				FgaTuples.location(id, oldLocation).forEach(fga::delete);
			}
			if (e.getLocationId() != null) {
				FgaTuples.location(id, e.getLocationId()).forEach(fga::write);
			}
		}
	}
}
