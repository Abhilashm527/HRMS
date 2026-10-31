package com.ivoyant.hrms.leave.service;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.leave.LeaveDays;

@Service
class LeaveDaysService implements LeaveDays {

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;

	LeaveDaysService(JdbcClient jdbc, CurrentUser currentUser) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
	}

	@Override
	@Transactional(readOnly = true)
	public Map<UUID, OnLeave> onLeave(LocalDate day) {
		Map<UUID, OnLeave> out = new HashMap<>();
		jdbc.sql("""
				select r.employee_id, t.code, t.name, r.half_day from leave_request r join leave_type t on t.id = r.type_id
				 where r.org_id = :org and r.status = 'APPROVED' and r.from_date <= :d and r.to_date >= :d""").param("org", currentUser.orgId()).param("d", day)
				.query((rs, i) -> out.put(rs.getObject(1, UUID.class), new OnLeave(rs.getString(2), rs.getString(3), rs.getBoolean(4)))).list();
		return out;
	}
}
