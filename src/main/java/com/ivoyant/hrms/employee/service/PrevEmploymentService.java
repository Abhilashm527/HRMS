package com.ivoyant.hrms.employee.service;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.ForbiddenException;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.common.web.UnprocessableException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Previous employers on the Documents tab. The person and HR edit; verifying needs verify rights. */
@Service
public class PrevEmploymentService {

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final EmployeeActions actions;
	private final Audit audit;

	PrevEmploymentService(JdbcClient jdbc, CurrentUser currentUser, EmployeeActions actions, Audit audit) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.actions = actions;
		this.audit = audit;
	}

	private void requireView(UUID id) {
		if (!id.equals(currentUser.employeeId()) && !actions.can("upload_docs", id) && !actions.can("verify_docs", id)) {
			throw new ForbiddenException("You cannot see this person's previous employment");
		}
	}

	private void requireWrite(UUID id) {
		if (!id.equals(currentUser.employeeId()) && !actions.can("update_employee", id)) {
			throw new ForbiddenException("You cannot change this person's previous employment");
		}
	}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> list(UUID id) {
		requireView(id);
		return jdbc.sql("select id, company, title, location, from_date, to_date, reason_for_leaving, pf_member, verified from employee_prev_employment where employee_id = :e order by sort, from_date desc nulls last")
				.param("e", id).query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("id", rs.getObject(1, UUID.class));
					m.put("company", rs.getString(2));
					m.put("title", rs.getString(3));
					m.put("location", rs.getString(4));
					m.put("from", rs.getObject(5, LocalDate.class));
					m.put("to", rs.getObject(6, LocalDate.class));
					m.put("reasonForLeaving", rs.getString(7));
					m.put("pfMember", rs.getObject(8));
					m.put("verified", rs.getBoolean(9));
					return m;
				}).list();
	}

	@Transactional
	public Map<String, Object> create(UUID id, Map<String, Object> body) {
		requireWrite(id);
		Values v = Values.of(body);
		UUID pid = UUID.randomUUID();
		jdbc.sql("""
				insert into employee_prev_employment (id, org_id, employee_id, company, title, location, from_date, to_date, reason_for_leaving, pf_member, sort)
				values (:id, :org, :e, :c, :t, :l, :f, :to, :r, :pf, (select coalesce(max(sort), 0) + 10 from employee_prev_employment where employee_id = :e))""")
				.param("id", pid).param("org", currentUser.orgId()).param("e", id).param("c", v.company).param("t", v.title).param("l", v.location)
				.param("f", v.from).param("to", v.to).param("r", v.reason).param("pf", v.pf, java.sql.Types.BOOLEAN).update();
		audit.publish("employee", "Employee", id, "PREV_EMPLOYMENT_ADD", null, v.company);
		return Map.of("id", pid);
	}

	@Transactional
	public void update(UUID id, UUID pid, Map<String, Object> body) {
		requireWrite(id);
		Values v = Values.of(body);
		int n = jdbc.sql("update employee_prev_employment set company = :c, title = :t, location = :l, from_date = :f, to_date = :to, reason_for_leaving = :r, pf_member = :pf, verified = false, verified_by = null where id = :p and employee_id = :e")
				.param("c", v.company).param("t", v.title).param("l", v.location).param("f", v.from).param("to", v.to).param("r", v.reason)
				.param("pf", v.pf, java.sql.Types.BOOLEAN).param("p", pid).param("e", id).update();
		if (n == 0) {
			throw new NotFoundException("Previous employer " + pid + " not found");
		}
		audit.publish("employee", "Employee", id, "PREV_EMPLOYMENT_EDIT", null, v.company);
	}

	@Transactional
	public void delete(UUID id, UUID pid) {
		requireWrite(id);
		if (jdbc.sql("delete from employee_prev_employment where id = :p and employee_id = :e").param("p", pid).param("e", id).update() == 0) {
			throw new NotFoundException("Previous employer " + pid + " not found");
		}
	}

	@Transactional
	public void verify(UUID id, UUID pid) {
		actions.require("verify_docs", id);
		if (jdbc.sql("update employee_prev_employment set verified = true, verified_by = :by where id = :p and employee_id = :e")
				.param("by", currentUser.employeeId(), java.sql.Types.OTHER).param("p", pid).param("e", id).update() == 0) {
			throw new NotFoundException("Previous employer " + pid + " not found");
		}
		audit.publish("employee", "Employee", id, "PREV_EMPLOYMENT_VERIFY", null, pid.toString());
	}

	private record Values(String company, String title, String location, LocalDate from, LocalDate to, String reason, Boolean pf) {

		static Values of(Map<String, Object> b) {
			String company = b.get("company") == null ? "" : b.get("company").toString().trim();
			if (company.isEmpty()) {
				throw new UnprocessableException(List.of(ErrorItem.field("company", "REQUIRED", "Company is required")));
			}
			LocalDate from = date(b.get("from"), "from");
			LocalDate to = date(b.get("to"), "to");
			if (from != null && to != null && to.isBefore(from)) {
				throw new UnprocessableException(List.of(ErrorItem.field("to", "INVALID", "The end date is before the start date")));
			}
			return new Values(company, text(b.get("title")), text(b.get("location")), from, to, text(b.get("reasonForLeaving")),
					b.get("pfMember") instanceof Boolean pf ? pf : null);
		}

		private static String text(Object o) {
			return o == null || o.toString().isBlank() ? null : o.toString().trim();
		}

		private static LocalDate date(Object o, String key) {
			try {
				return o == null || o.toString().isBlank() ? null : LocalDate.parse(o.toString());
			}
			catch (java.time.format.DateTimeParseException ex) {
				throw new UnprocessableException(List.of(ErrorItem.field(key, "INVALID_FORMAT", "Use the date format yyyy-mm-dd")));
			}
		}
	}
}
