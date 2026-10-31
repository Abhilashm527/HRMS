package com.ivoyant.hrms.leave.service;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.ivoyant.hrms.leave.LeaveTypeUsage;

/** A leave type is in use once any request or ledger entry refers to it; then its code is locked and it can't be deleted. */
@Component
class LeaveTypeUsageService implements LeaveTypeUsage {

	private final JdbcClient jdbc;

	LeaveTypeUsageService(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public boolean inUse(UUID typeId) {
		return jdbc.sql("select exists (select 1 from leave_request where type_id = :t) or exists (select 1 from leave_ledger where type_id = :t and kind <> 'CREDIT')")
				.param("t", typeId).query(Boolean.class).single();
	}
}
