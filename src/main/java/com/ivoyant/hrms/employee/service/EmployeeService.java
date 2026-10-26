package com.ivoyant.hrms.employee.service;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.authz.FgaOutbox;
import com.ivoyant.hrms.common.authz.FgaTuples;
import com.ivoyant.hrms.common.event.EmployeeCreatedEvent;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.BadRequestException;
import com.ivoyant.hrms.common.web.ConflictException;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.UnprocessableException;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.common.web.PageResponse;
import com.ivoyant.hrms.config.FieldAccess;
import com.ivoyant.hrms.config.FieldConfig;
import com.ivoyant.hrms.config.FieldDefinition;
import com.ivoyant.hrms.config.FieldType;
import com.ivoyant.hrms.config.FieldValues;
import com.ivoyant.hrms.config.WriteResult;
import com.ivoyant.hrms.employee.domain.Employee;
import com.ivoyant.hrms.employee.domain.EmployeeStatus;
import com.ivoyant.hrms.employee.dto.CreateEmployeeRequest;
import com.ivoyant.hrms.employee.dto.EmployeeMeta;
import com.ivoyant.hrms.employee.dto.EmployeeResponse;
import com.ivoyant.hrms.employee.dto.EmployeeSummary;
import com.ivoyant.hrms.employee.dto.FieldMeta;
import com.ivoyant.hrms.employee.dto.UpdateEmployeeRequest;
import com.ivoyant.hrms.employee.mapper.EmployeeMapper;
import com.ivoyant.hrms.employee.repo.EmployeeRepository;
import com.ivoyant.hrms.org.EmployeeCodes;
import com.ivoyant.hrms.org.OrgDirectory;
import com.ivoyant.hrms.org.OrgProfile;
import com.ivoyant.hrms.org.OrgSettings;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmployeeService {

	private static final String ENTITY = "Employee";
	private static final Set<String> SORTABLE = Set.of("displayName", "employeeCode", "joiningDate", "status",
			"workEmail");

	private final EmployeeRepository employees;
	private final EmployeeMapper mapper;
	private final FieldConfig fieldConfig;
	private final EmployeeRecords records;
	private final OrgDirectory org;
	private final CurrentUser currentUser;
	private final FgaOutbox fga;
	private final Audit audit;
	private final ApplicationEventPublisher events;
	private final OrgSettings orgSettings;
	private final OrgProfile orgProfile;
	private final EmployeeCodes codes;
	private final com.ivoyant.hrms.org.Titles titles;
	private final com.ivoyant.hrms.org.ReportingLines reportingLines;

	public EmployeeService(EmployeeRepository employees, EmployeeMapper mapper, FieldConfig fieldConfig,
			EmployeeRecords records, OrgDirectory org, CurrentUser currentUser, FgaOutbox fga, Audit audit,
			ApplicationEventPublisher events, OrgSettings orgSettings, OrgProfile orgProfile, EmployeeCodes codes,
			com.ivoyant.hrms.org.Titles titles, com.ivoyant.hrms.org.ReportingLines reportingLines) {
		this.titles = titles;
		this.reportingLines = reportingLines;
		this.orgSettings = orgSettings;
		this.orgProfile = orgProfile;
		this.codes = codes;
		this.employees = employees;
		this.mapper = mapper;
		this.fieldConfig = fieldConfig;
		this.records = records;
		this.org = org;
		this.currentUser = currentUser;
		this.fga = fga;
		this.audit = audit;
		this.events = events;
	}

	// ---- reads ----

	@Transactional(readOnly = true)
	public PageResponse<EmployeeSummary> search(UUID departmentId, UUID locationId, EmployeeStatus status,
			UUID managerId, String q, int page, int size, String sort, boolean descending) {
		UUID orgId = currentUser.orgId();
		Specification<Employee> spec = (root, query, cb) -> {
			List<Predicate> p = new ArrayList<>();
			p.add(cb.equal(root.get("orgId"), orgId));
			if (departmentId != null) {
				p.add(cb.equal(root.get("departmentId"), departmentId));
			}
			if (locationId != null) {
				p.add(cb.equal(root.get("locationId"), locationId));
			}
			if (status != null) {
				p.add(cb.equal(root.get("status"), status));
			}
			if (managerId != null) {
				p.add(cb.equal(root.get("managerId"), managerId));
			}
			if (q != null && !q.isBlank()) {
				String like = "%" + q.trim().toLowerCase() + "%";
				p.add(cb.or(cb.like(cb.lower(root.get("displayName")), like),
						cb.like(cb.lower(root.get("workEmail")), like),
						cb.like(cb.lower(root.get("employeeCode")), like)));
			}
			return cb.and(p.toArray(Predicate[]::new));
		};
		String property = SORTABLE.contains(sort) ? sort : "displayName";
		Sort order = descending ? Sort.by(property).descending() : Sort.by(property).ascending();
		Page<Employee> result = employees.findAll(spec, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), order));
		Map<UUID, String> displays = titles.displays(result.getContent().stream().map(Employee::getId).toList());
		return PageResponse.of(result, e -> summary(e, displays));
	}

	@Transactional(readOnly = true)
	public EmployeeResponse get(UUID id) {
		return view(find(id));
	}

	@Transactional(readOnly = true)
	public EmployeeResponse me() {
		return view(find(currentUser.requireEmployeeId()));
	}

	@Transactional(readOnly = true)
	public List<EmployeeSummary> reports(UUID id, boolean all) {
		find(id);
		List<Employee> reports = all
				? employees.findAllById(reportingLines.reports(id, true)).stream().sorted(java.util.Comparator.comparing(Employee::getDisplayName)).toList()
				: employees.findByOrgIdAndManagerIdOrderByDisplayName(currentUser.orgId(), id);
		Map<UUID, String> displays = titles.displays(reports.stream().map(Employee::getId).toList());
		return reports.stream().map(e -> summary(e, displays)).toList();
	}

	private EmployeeSummary summary(Employee e, Map<UUID, String> displays) {
		EmployeeSummary s = mapper.toSummary(e);
		return new EmployeeSummary(s.id(), s.employeeCode(), s.displayName(), s.workEmail(), s.departmentId(),
				s.designationId(), s.locationId(), s.managerId(), s.status(), e.getTitleId(), displays.get(e.getId()));
	}

	// ---- create ----

	@Transactional
	public EmployeeResponse create(CreateEmployeeRequest r) {
		UUID orgId = currentUser.orgId();
		r = records.resolveTitle(r);
		com.ivoyant.hrms.org.TypeView type = records.requireHireType(r.employmentType());
		if (type.fixedTerm() && (r.contractEndDate() == null || !r.contractEndDate().isAfter(r.joiningDate()))) {
			throw new UnprocessableException(List.of(ErrorItem.field("contractEndDate", "CONTRACT_END_REQUIRED",
					type.name() + " needs a contract end date after the joining date")));
		}
		List<ErrorItem> problems = new ArrayList<>();
		if (orgSettings.positionMandatory() && r.designationId() == null) {
			problems.add(ErrorItem.field("designationId", "REQUIRED", "Position is mandatory"));
		}
		if (!orgSettings.autoEmployeeId() && (r.employeeCode() == null || r.employeeCode().isBlank())) {
			problems.add(ErrorItem.field("employeeCode", "REQUIRED", "Employee code is required when automatic IDs are off"));
		}
		String domain = orgProfile.current().primaryDomain();
		String emailDomain = r.workEmail().substring(r.workEmail().lastIndexOf('@') + 1).toLowerCase();
		if (domain != null && !domain.isBlank() && !(emailDomain.equals(domain) || emailDomain.endsWith("." + domain))) {
			problems.add(ErrorItem.field("workEmail", "DOMAIN", "Work email must be on " + domain));
		}
		if (!problems.isEmpty()) {
			throw new UnprocessableException(problems);
		}
		if (!orgSettings.autoEmployeeId() && employees.existsByOrgIdAndEmployeeCode(orgId, r.employeeCode().trim())) {
			throw new ConflictException("Employee code '" + r.employeeCode() + "' is already in use");
		}
		if (employees.existsByWorkEmailIgnoreCase(r.workEmail())) {
			throw new ConflictException("Work email '" + r.workEmail() + "' is already in use");
		}
		records.validateOrgRefs(r.departmentId(), r.designationId(), r.locationId());
		if (r.locationId() != null) {
			records.requireActiveLocation(r.locationId());
		}
		if (r.departmentId() != null) {
			records.requireActiveDepartment(r.departmentId());
		}
		if (r.designationId() != null) {
			records.requireActivePosition(r.designationId());
		}
		if (r.managerId() != null) {
			find(r.managerId());
			records.requireReportingDepth(r.managerId(), null);
		}

		Employee e = new Employee();
		e.setOrgId(orgId);
		e.setEmployeeCode(orgSettings.autoEmployeeId() ? codes.next(r.joiningDate()) : r.employeeCode().trim());
		e.setFirstName(r.firstName().trim());
		e.setLastName(r.lastName().trim());
		e.setDisplayName(EmployeeRecords.displayName(e));
		e.setWorkEmail(r.workEmail().trim());
		e.setPersonalEmail(r.personalEmail());
		e.setPhone(r.phone());
		e.setDepartmentId(r.departmentId());
		e.setDesignationId(r.designationId());
		e.setTitleId(r.titleId());
		e.setLocationId(r.locationId());
		e.setManagerId(r.managerId());
		e.setEmploymentType(type.name());
		e.setEmploymentTypeId(type.id());
		e.setProbationEndDate(type.probationMonths() > 0 ? r.joiningDate().plusMonths(type.probationMonths()) : null);
		e.setContractEndDate(type.fixedTerm() ? r.contractEndDate() : null);
		records.requireValidManager(e);
		e.setJoiningDate(r.joiningDate());
		applyCustom(e, r.custom());
		employees.saveAndFlush(e);

		events.publishEvent(new EmployeeCreatedEvent(orgId, e.getId()));
		records.recordHireTitle(e);
		records.recordManagerChange(e, null, "HIRE", r.joiningDate());
		records.recordTypeChange(e, null, "HIRE", r.joiningDate());
		if (e.getDesignationId() != null) {
			events.publishEvent(new com.ivoyant.hrms.common.event.EmployeePositionChangedEvent(e.getId(), null, e.getDesignationId()));
		}
		FgaTuples.employeeAll(orgId, e.getId(), e.getManagerId(), e.getDepartmentId(), e.getLocationId())
				.forEach(fga::write);
		audit.created("employee", ENTITY, e.getId(), snapshot(e));
		return view(e);
	}

	/**
	 * Validates the supplied custom values against their field definitions and stores them. Required fields are not
	 * enforced here: HR can create the employee first and the employee fills the rest in later.
	 */
	private void applyCustom(Employee e, Map<String, Object> custom) {
		Map<String, Object> given = custom != null ? custom : Map.of();
		Map<String, FieldDefinition> byKey = fieldConfig.activeFields(EmployeeFields.AREA).stream()
				.filter(d -> !EmployeeFields.isColumn(d.key()) && d.type().hasValue())
				.collect(Collectors.toMap(FieldDefinition::key, Function.identity()));
		List<String> unknown = given.keySet().stream().filter(k -> !byKey.containsKey(k)).sorted().toList();
		if (!unknown.isEmpty()) {
			throw new BadRequestException("Unknown custom field(s): " + unknown);
		}
		for (Map.Entry<String, Object> entry : given.entrySet()) {
			FieldDefinition def = byKey.get(entry.getKey());
			if (FieldValues.isBlank(entry.getValue())) {
				continue;
			}
			try {
				EmployeeFields.apply(e, def.key(), FieldValues.normalize(def, entry.getValue()));
			}
			catch (FieldValues.Invalid ex) {
				throw new BadRequestException(ex.getMessage());
			}
		}
	}

	// ---- update ----

	/**
	 * Each key must be a field the caller may edit. EDIT fields are written now; HR_APPROVES fields become change
	 * requests that wait for an approver (listed in {@code pendingApproval}).
	 */
	@Transactional
	public EmployeeResponse update(UUID id, UpdateEmployeeRequest request) {
		Employee e = find(id);
		WriteResult result = fieldConfig.write(EmployeeFields.AREA, e.getId(), e.getId(), request.fields(), false);
		return view(find(id), result.pendingApproval());
	}

	// ---- view ----

	private EmployeeResponse view(Employee e) {
		return view(e, List.of());
	}

	/** The employee as the caller may see them: hidden fields omitted, masked ones masked. */
	private EmployeeResponse view(Employee e, List<String> pendingApproval) {
		UUID callerId = currentUser.requireEmployeeId();
		List<FieldDefinition> defs = fieldConfig.activeFields(EmployeeFields.AREA);
		Map<String, FieldAccess> access = fieldConfig.levels(EmployeeFields.AREA, e.getId());
		Map<String, Object> values = records.read(e.getId());
		Set<String> conditionHidden = fieldConfig.hiddenByCondition(EmployeeFields.AREA, values);

		Map<UUID, String> departments = org.departmentNames(idsOf(e.getDepartmentId()));
		Map<UUID, String> designations = org.designationNames(idsOf(e.getDesignationId()));
		Map<UUID, String> locations = org.locationNames(idsOf(e.getLocationId()));
		String managerName = e.getManagerId() == null ? null
				: employees.findById(e.getManagerId()).map(Employee::getDisplayName).orElse(null);

		Map<String, Object> fields = new LinkedHashMap<>();
		Map<String, FieldMeta> meta = new LinkedHashMap<>();
		for (FieldDefinition def : defs) {
			FieldAccess a = access.getOrDefault(def.key(), FieldAccess.HIDDEN);
			if (a == FieldAccess.HIDDEN || def.type() == FieldType.HEADING || conditionHidden.contains(def.key())) {
				continue;
			}
			Object raw = values.get(def.key());
			String display = switch (def.key()) {
				case "department" -> lookup(departments, e.getDepartmentId());
				case "designation" -> lookup(designations, e.getDesignationId());
				case "title" -> e.getTitleId() == null ? null : titles.names(List.of(e.getTitleId())).get(e.getTitleId());
				case "location" -> lookup(locations, e.getLocationId());
				case "manager" -> managerName;
				default -> null;
			};
			boolean masked = a == FieldAccess.MASKED;
			fields.put(def.key(), masked ? Masking.mask(raw) : raw);
			meta.put(def.key(), new FieldMeta(def.label(), def.type(), def.required(), a,
					a == FieldAccess.EDIT, masked ? null : display));
		}
		return new EmployeeResponse(e.getId(), e.getEmployeeCode(), e.getDisplayName(), fields,
				new EmployeeMeta(e.getId().equals(callerId), meta), pendingApproval, titles.display(e.getId()));
	}

	/** Raw values of every field, for the audit trail. */
	private Map<String, Object> snapshot(Employee e) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("employee_code", e.getEmployeeCode());
		for (String key : EmployeeFields.COLUMN_KEYS.stream().sorted().toList()) {
			out.put(key, EmployeeFields.read(e, key));
		}
		out.putAll(e.getCustom());
		return out;
	}

	/** The name for an id; an employee may have no department, position or location yet (immutable maps reject null keys). */
	private static String lookup(Map<UUID, String> names, UUID id) {
		return id == null ? null : names.get(id);
	}

	private static List<UUID> idsOf(UUID id) {
		return id == null ? List.of() : List.of(id);
	}

	private static String displayName(Employee e) {
		return (e.getFirstName() + " " + e.getLastName()).trim();
	}

	private Employee find(UUID id) {
		return employees.findByIdAndOrgId(id, currentUser.orgId())
				.orElseThrow(() -> new NotFoundException("Employee " + id + " not found"));
	}
}
