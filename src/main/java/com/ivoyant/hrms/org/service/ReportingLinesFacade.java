package com.ivoyant.hrms.org.service;

import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.org.ReportingLines;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The direct reporting line, read straight from {@code employee.manager_id} (every walk is bounded, so a bad loop cannot hang). */
@Service
class ReportingLinesFacade implements ReportingLines {

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;

	ReportingLinesFacade(JdbcClient jdbc, CurrentUser currentUser) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<UUID> managerOf(UUID employeeId) {
		return jdbc.sql("select manager_id from employee where id = :e and org_id = :org").param("e", employeeId)
				.param("org", currentUser.orgId()).query(UUID.class).optional();
	}

	@Override
	@Transactional(readOnly = true)
	public List<UUID> chain(UUID employeeId) {
		return jdbc.sql("""
				with recursive up as (
				    select manager_id as id, 1 as d from employee where id = :e and manager_id is not null
				    union all
				    select e.manager_id, up.d + 1 from employee e join up on e.id = up.id where e.manager_id is not null and up.d < 200)
				select id from up order by d""").param("e", employeeId).query(UUID.class).list();
	}

	@Override
	@Transactional(readOnly = true)
	public List<UUID> reports(UUID employeeId, boolean all) {
		if (!all) {
			return jdbc.sql("select id from employee where manager_id = :e and status <> 'EXITED' order by display_name").param("e", employeeId)
					.query(UUID.class).list();
		}
		return jdbc.sql("""
				with recursive down as (
				    select id, 1 as d from employee where manager_id = :e and status <> 'EXITED'
				    union all
				    select c.id, down.d + 1 from employee c join down on c.manager_id = down.id where c.status <> 'EXITED' and down.d < 200)
				select distinct id from down""").param("e", employeeId).query(UUID.class).list();
	}

	@Override
	@Transactional(readOnly = true)
	public boolean isAbove(UUID a, UUID b) {
		return chain(b).contains(a);
	}
}
