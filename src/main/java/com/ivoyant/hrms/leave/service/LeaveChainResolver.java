package com.ivoyant.hrms.leave.service;

import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.leave.ResolvedStep;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Works out who approves a leave request: each step of the chain in order, skipped when its day condition does not apply, resolved to a
 * person (the reporting manager, the manager's manager, the department head, or HR), replaced by an active delegate, and left out when it is
 * the applicant or somebody already in the chain. A step nobody fills goes to HR.
 */
@Component
class LeaveChainResolver {

	/** A step of the chain as configured. */
	record Step(String approver, String condition, BigDecimal minDays) {
	}

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;

	LeaveChainResolver(JdbcClient jdbc, CurrentUser currentUser) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
	}

	List<ResolvedStep> resolve(UUID applicant, BigDecimal days, List<Step> steps) {
		List<ResolvedStep> out = new ArrayList<>();
		Set<UUID> chosen = new HashSet<>();
		int sort = 1;
		for (Step step : steps) {
			if ("OVER_DAYS".equals(step.condition()) && days.compareTo(step.minDays()) <= 0) {
				continue;
			}
			UUID who = resolveApprover(step.approver(), applicant);
			if (who == null) {
				who = hr();
			}
			UUID delegatedFrom = null;
			if (who != null) {
				UUID delegate = activeDelegate(who);
				if (delegate != null) {
					delegatedFrom = who;
					who = delegate;
				}
			}
			if (who == null || who.equals(applicant) || !chosen.add(who)) {
				continue;
			}
			String name = jdbc.sql("select display_name from employee where id = :id").param("id", who).query(String.class).optional().orElse("");
			out.add(new ResolvedStep(sort++, step.approver(), who, name, delegatedFrom));
		}
		return out;
	}

	private UUID resolveApprover(String approver, UUID applicant) {
		return switch (approver) {
			case "REPORTING_MANAGER" -> manager(applicant);
			case "SKIP_LEVEL_MANAGER" -> {
				UUID m = manager(applicant);
				yield m == null ? null : manager(m);
			}
			case "DEPARTMENT_HEAD" -> jdbc.sql("select d.head_employee_id from employee e join department d on d.id = e.department_id where e.id = :id")
					.param("id", applicant).query(UUID.class).optional().orElse(null);
			default -> hr();
		};
	}

	private UUID manager(UUID employee) {
		return jdbc.sql("select m.id from employee e join employee m on m.id = e.manager_id where e.id = :id and m.status <> 'EXITED'")
				.param("id", employee).query(UUID.class).optional().orElse(null);
	}

	/** The first active HR admin (an organization-wide "leave HR approver" setting can take over later). */
	private UUID hr() {
		return jdbc.sql("""
				select e.id from user_role ur join role r on r.id = ur.role_id join app_user u on u.id = ur.user_id join employee e on e.id = u.employee_id
				where r.org_id = :org and r.key = 'hr_admin' and e.status <> 'EXITED' order by e.employee_code limit 1""")
				.param("org", currentUser.orgId()).query(UUID.class).optional().orElse(null);
	}

	/** Somebody who is covering for this approver on leave today. */
	private UUID activeDelegate(UUID approver) {
		return jdbc.sql("""
				select te.id from delegation d join app_user fu on fu.id = d.from_user_id join app_user tu on tu.id = d.to_user_id
				join employee te on te.id = tu.employee_id
				where fu.employee_id = :who and d.module = 'leave' and d.org_id = :org and now() between d.starts_at and d.ends_at and te.status <> 'EXITED'
				order by d.starts_at desc limit 1""").param("who", approver).param("org", currentUser.orgId()).query(UUID.class).optional().orElse(null);
	}
}
