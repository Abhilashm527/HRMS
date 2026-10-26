package com.ivoyant.hrms.employee.service;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.event.EmployeeTitleChangedEvent;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.UnprocessableException;
import com.ivoyant.hrms.employee.domain.Employee;
import com.ivoyant.hrms.org.OrgDirectory;
import com.ivoyant.hrms.org.OrgSettings;
import com.ivoyant.hrms.org.TitleView;
import com.ivoyant.hrms.org.Titles;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * An employee's titles: the primary one (which sets their position) and, while the organization allows it, up to three
 * extra ones; plus the history of both. Changing the primary title and moving to another department can be done in one
 * step, because a title limited to a department must fit the department the person ends up in.
 */
@Service
public class EmployeeTitleService {

	private static final int MAX_EXTRA = 3;
	private static final Set<String> REASONS = Set.of("HIRE", "PROMOTION", "TRANSFER", "CORRECTION", "MERGE");

	public record TitleRef(UUID id, String name, String code, UUID positionId, String positionName) {
	}

	public record HistoryRow(UUID titleId, String title, boolean primary, LocalDate from, LocalDate to, String reason) {
	}

	public record EmployeeTitles(TitleRef primary, List<TitleRef> extras, List<HistoryRow> history, String titleDisplay) {
	}

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final EmployeeRecords records;
	private final Titles titles;
	private final OrgDirectory org;
	private final OrgSettings settings;
	private final Audit audit;
	private final ApplicationEventPublisher events;

	public EmployeeTitleService(JdbcClient jdbc, CurrentUser currentUser, EmployeeRecords records, Titles titles,
			OrgDirectory org, OrgSettings settings, Audit audit, ApplicationEventPublisher events) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.records = records;
		this.titles = titles;
		this.org = org;
		this.settings = settings;
		this.audit = audit;
		this.events = events;
	}

	@Transactional(readOnly = true)
	public EmployeeTitles get(UUID employeeId) {
		Employee e = records.find(employeeId);
		TitleRef primary = e.getTitleId() == null ? null : ref(e.getTitleId());
		List<TitleRef> extras = jdbc.sql("select title_id from employee_extra_title where employee_id = :e order by since, created_at")
				.param("e", employeeId).query(UUID.class).list().stream().map(this::ref).toList();
		List<HistoryRow> history = jdbc.sql("""
				select h.title_id, t.name, h.is_primary, h.from_date, h.to_date, h.reason
				from employee_title_history h join job_title t on t.id = h.title_id
				where h.employee_id = :e order by h.from_date desc, h.created_at desc""").param("e", employeeId)
				.query((rs, i) -> new HistoryRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getBoolean(3),
						rs.getObject(4, LocalDate.class), rs.getObject(5, LocalDate.class), rs.getString(6)))
				.list();
		return new EmployeeTitles(primary, extras, history, titles.display(employeeId));
	}

	private TitleRef ref(UUID titleId) {
		TitleView t = titles.get(titleId).orElse(null);
		if (t == null) {
			return new TitleRef(titleId, null, null, null, null);
		}
		String position = t.positionId() == null ? null : org.designationNames(List.of(t.positionId())).get(t.positionId());
		return new TitleRef(t.id(), t.name(), t.code(), t.positionId(), position);
	}

	/**
	 * Body: {@code primaryId}, {@code extraIds}, {@code effectiveDate}, {@code reason}, and optionally {@code departmentId}
	 * to move the person to another department in the same step.
	 */
	@Transactional
	public EmployeeTitles set(UUID employeeId, Map<String, Object> body) {
		Employee e = records.find(employeeId);
		UUID oldPrimary = e.getTitleId();
		UUID primary = uuid(body.get("primaryId"), "primaryId");
		if (primary == null) {
			throw invalid("primaryId", "REQUIRED", "Pick the person's title");
		}
		UUID department = body.get("departmentId") == null ? e.getDepartmentId() : uuid(body.get("departmentId"), "departmentId");
		LocalDate effective = body.get("effectiveDate") == null || body.get("effectiveDate").toString().isBlank()
				? LocalDate.now() : LocalDate.parse(body.get("effectiveDate").toString());
		String reason = body.get("reason") == null ? null : body.get("reason").toString().trim().toUpperCase();
		if (reason != null && !REASONS.contains(reason)) {
			throw invalid("reason", "INVALID", "Reason must be one of " + String.join(", ", REASONS));
		}
		Set<UUID> extras = new LinkedHashSet<>();
		if (body.get("extraIds") instanceof List<?> list) {
			for (Object o : list) {
				UUID id = uuid(o, "extraIds");
				if (id == null || id.equals(primary) || !extras.add(id)) {
					throw invalid("extraIds", "INVALID", "Each extra title must be different from the others and from the main title");
				}
			}
		}
		if (!extras.isEmpty() && !settings.multiTitle()) {
			throw invalid("extraIds", "MULTI_TITLE_OFF", "More than one title per employee is off in Settings → Organization");
		}
		if (extras.size() > MAX_EXTRA) {
			throw invalid("extraIds", "TOO_MANY_TITLES", "An employee can have at most " + MAX_EXTRA + " extra titles");
		}
		for (UUID x : extras) {
			TitleView t = titles.get(x).orElseThrow(() -> invalid("extraIds", "INVALID", "Title " + x + " does not exist"));
			if (!t.active()) {
				throw invalid("extraIds", "TITLE_INACTIVE", t.name() + " is inactive. Choose an active title");
			}
			if (!titles.validFor(x, department)) {
				throw invalid("extraIds", "TITLE_NOT_IN_DEPARTMENT", t.name() + " doesn't fit this person's department");
			}
		}
		// the employee row: primary title (which sets the position) and department
		Map<String, Object> values = new LinkedHashMap<>();
		if (!primary.equals(oldPrimary)) {
			values.put("title", primary.toString());
		}
		if (!Objects.equals(department, e.getDepartmentId())) {
			values.put("department", department == null ? null : department.toString());
		}
		if (!values.isEmpty()) {
			records.apply(employeeId, values, reason, effective);
		}
		// extra titles
		Set<UUID> current = new LinkedHashSet<>(jdbc.sql("select title_id from employee_extra_title where employee_id = :e")
				.param("e", employeeId).query(UUID.class).list());
		List<UUID> added = extras.stream().filter(x -> !current.contains(x)).toList();
		List<UUID> removed = current.stream().filter(x -> !extras.contains(x)).toList();
		String why = reason != null ? reason : "CORRECTION";
		for (UUID x : added) {
			jdbc.sql("insert into employee_extra_title (employee_id, title_id, org_id, since, created_by) values (:e, :t, :org, :d, :by)")
					.param("e", employeeId).param("t", x).param("org", e.getOrgId()).param("d", effective)
					.param("by", currentUser.employeeId(), java.sql.Types.OTHER).update();
			jdbc.sql("""
					insert into employee_title_history (org_id, employee_id, title_id, is_primary, from_date, reason, created_by)
					values (:org, :e, :t, false, :d, :r, :by)""").param("org", e.getOrgId()).param("e", employeeId)
					.param("t", x).param("d", effective).param("r", why).param("by", currentUser.employeeId(), java.sql.Types.OTHER).update();
		}
		for (UUID x : removed) {
			jdbc.sql("delete from employee_extra_title where employee_id = :e and title_id = :t").param("e", employeeId).param("t", x).update();
			jdbc.sql("update employee_title_history set to_date = :d where employee_id = :e and title_id = :t and not is_primary and to_date is null")
					.param("d", effective).param("e", employeeId).param("t", x).update();
		}
		if (!added.isEmpty() || !removed.isEmpty()) {
			events.publishEvent(new EmployeeTitleChangedEvent(employeeId, oldPrimary, primary, added, removed, why, effective));
		}
		audit.publish("employee", "Employee", employeeId, "TITLES",
				Map.of("primary", String.valueOf(oldPrimary), "extras", current.toString()),
				Map.of("primary", primary.toString(), "extras", extras.toString()));
		return get(employeeId);
	}

	private static UUID uuid(Object v, String field) {
		if (v == null || v.toString().isBlank()) {
			return null;
		}
		try {
			return UUID.fromString(v.toString().trim());
		}
		catch (IllegalArgumentException ex) {
			throw invalid(field, "INVALID", "'" + v + "' is not a valid id");
		}
	}

	private static UnprocessableException invalid(String field, String code, String message) {
		List<ErrorItem> errors = new ArrayList<>();
		errors.add(ErrorItem.field(field, code, message));
		return new UnprocessableException(errors);
	}
}
