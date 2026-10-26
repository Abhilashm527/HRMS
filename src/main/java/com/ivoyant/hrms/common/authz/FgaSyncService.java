package com.ivoyant.hrms.common.authz;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Rebuilds the OpenFGA tuples from the database through the outbox. Additive: writes are idempotent, stale tuples
 * are not removed. Reads SQL directly so it works across modules without depending on them.
 */
@Service
public class FgaSyncService {

	private static final Logger log = LoggerFactory.getLogger(FgaSyncService.class);

	private final JdbcClient jdbc;
	private final FgaOutbox outbox;

	public FgaSyncService(JdbcClient jdbc, FgaOutbox outbox) {
		this.jdbc = jdbc;
		this.outbox = outbox;
	}

	/** Queues every tuple implied by the current data; returns how many were queued. */
	@Transactional
	public int syncAll() {
		List<FgaTuple> tuples = new ArrayList<>();

		List<UUID> orgs = jdbc.sql("select id from org").query(UUID.class).list();
		List<String> areas = jdbc.sql("select key from config_area").query(String.class).list();
		for (UUID org : orgs) {
			areas.forEach(a -> tuples.add(FgaTuples.configAreaOrg(org, a)));
		}

		jdbc.sql("select org_id, id, head_employee_id from location").query((rs, i) -> {
			UUID org = rs.getObject(1, UUID.class);
			UUID id = rs.getObject(2, UUID.class);
			UUID head = rs.getObject(3, UUID.class);
			List<FgaTuple> t = new ArrayList<>(List.of(FgaTuples.locationOrg(org, id)));
			if (head != null) {
				t.add(FgaTuples.locationHead(id, head));
			}
			return t;
		}).list().forEach(tuples::addAll);

		jdbc.sql("select org_id, id, head_employee_id, parent_id from department").query((rs, i) -> {
			UUID org = rs.getObject(1, UUID.class);
			UUID id = rs.getObject(2, UUID.class);
			UUID head = rs.getObject(3, UUID.class);
			UUID parent = rs.getObject(4, UUID.class);
			List<FgaTuple> t = new ArrayList<>(List.of(FgaTuples.departmentOrg(org, id)));
			if (parent != null) {
				t.add(FgaTuples.departmentParent(id, parent));
			}
			if (head != null) {
				t.add(FgaTuples.departmentHead(id, head));
			}
			return t;
		}).list().forEach(tuples::addAll);

		jdbc.sql("select org_id, id, manager_id, department_id, location_id from employee where status <> 'EXITED'")
				.query((rs, i) -> FgaTuples.employeeAll(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
						rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getObject(5, UUID.class)))
				.list().forEach(tuples::addAll);

		// dotted-line managers whose line is in force today
		jdbc.sql("""
				select employee_id, related_id from employee_relation
				where type = 'DOTTED' and ended_at is null and from_date <= current_date
				  and (to_date is null or to_date >= current_date)
				""").query((rs, i) -> FgaTuples.dotted(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class))).list()
				.forEach(tuples::add);

		// every role an employee holds (any scope), and the roles that own a config area
		jdbc.sql("""
				select distinct u.employee_id, r.key
				from user_role ur
				join app_user u on u.id = ur.user_id
				join role r on r.id = ur.role_id
				""").query((rs, i) -> FgaTuples.roleMember(rs.getObject(1, UUID.class), rs.getString(2)))
				.list().forEach(tuples::add);

		jdbc.sql("""
				select ao.area_key, r.key
				from area_owner_role ao join role r on r.id = ao.role_id
				""").query((rs, i) -> FgaTuples.areaOwnerRole(rs.getString(1), rs.getString(2)))
				.list().forEach(tuples::add);

		// organisation-wide admin roles
		jdbc.sql("""
				select u.org_id, u.employee_id, r.key
				from user_role ur
				join app_user u on u.id = ur.user_id
				join role r on r.id = ur.role_id
				where ur.scope = 'ORGANIZATION' and r.key in ('super_admin', 'hr_admin')
				""").query((rs, i) -> FgaTuples.orgRole(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
						rs.getString(3))).list().forEach(tuples::add);

		jdbc.sql("""
				select ao.area_key, u.employee_id
				from area_owner ao join app_user u on u.id = ao.user_id
				""").query((rs, i) -> FgaTuples.configAreaOwner(rs.getString(1), rs.getObject(2, UUID.class)))
				.list().forEach(tuples::add);

		// leave delegations that have not ended: the delegate acts for every direct report of the delegator
		jdbc.sql("""
				select fu.employee_id as from_emp, tu.employee_id as to_emp, d.starts_at, d.ends_at, e.id as report_id
				from delegation d
				join app_user fu on fu.id = d.from_user_id
				join app_user tu on tu.id = d.to_user_id
				join employee e on e.manager_id = fu.employee_id
				where d.module = 'leave' and d.ends_at > now()
				""").query((rs, i) -> FgaTuples.delegate(rs.getObject("to_emp", UUID.class),
						rs.getObject("report_id", UUID.class), rs.getObject("starts_at", Instant.class),
						rs.getObject("ends_at", Instant.class))).list().forEach(tuples::add);

		tuples.forEach(outbox::write);
		log.info("Queued {} OpenFGA tuples for sync", tuples.size());
		return tuples.size();
	}
}
