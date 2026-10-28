package com.ivoyant.hrms.org.service;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.authz.FgaOutbox;
import com.ivoyant.hrms.common.authz.FgaTuples;
import com.ivoyant.hrms.common.config.HrmsProperties;
import com.ivoyant.hrms.org.EmployeeCodes;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Makes sure the organization has a first super admin. When {@code HRMS_BOOTSTRAP_ADMIN_EMAIL} is set and nobody
 * holds {@code super_admin}, the employee with that work email becomes one (a minimal employee is created if there is
 * none). Once a super admin exists the setting is ignored. It runs at startup because sign-in is handled by Entra, so
 * there is no login hook inside the app.
 */
@Component
@Order(0)
class BootstrapAdmin implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(BootstrapAdmin.class);

	private final HrmsProperties props;
	private final JdbcClient jdbc;
	private final EmployeeCodes codes;
	private final FgaOutbox fga;
	private final Audit audit;
	private final org.springframework.context.ApplicationEventPublisher events;

	BootstrapAdmin(HrmsProperties props, JdbcClient jdbc, EmployeeCodes codes, FgaOutbox fga, Audit audit, org.springframework.context.ApplicationEventPublisher events) {
		this.events = events;
		this.props = props;
		this.jdbc = jdbc;
		this.codes = codes;
		this.fga = fga;
		this.audit = audit;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		String email = props.bootstrapAdminEmail();
		if (email == null || email.isBlank()) {
			return;
		}
		UUID org = props.defaultOrgId();
		long admins = jdbc.sql("""
				select count(*) from user_role ur join role r on r.id = ur.role_id
				where r.org_id = :org and r.key = 'super_admin'""").param("org", org).query(Long.class).single();
		if (admins > 0) {
			log.info("A super admin already exists; HRMS_BOOTSTRAP_ADMIN_EMAIL is ignored");
			return;
		}
		String work = email.trim().toLowerCase(Locale.ROOT);
		UUID employee = jdbc.sql("select id from employee where org_id = :org and lower(work_email) = :email")
				.param("org", org).param("email", work).query(UUID.class).optional().orElseGet(() -> createEmployee(org, work));

		UUID user = jdbc.sql("select id from app_user where employee_id = :e").param("e", employee).query(UUID.class)
				.optional().orElseGet(() -> {
					UUID id = UUID.randomUUID();
					jdbc.sql("insert into app_user (id, org_id, employee_id, email, status, break_glass, activated_at) values (:id, :org, :e, :email, 'ACTIVE', true, now())")
							.param("id", id).param("org", org).param("e", employee).param("email", work).update();
					return id;
				});
		jdbc.sql("update app_user set break_glass = true where id = :id").param("id", user).update();
		assign(org, user, employee, "employee", "OWN");
		assign(org, user, employee, "super_admin", "ORGANIZATION");
		fga.write(FgaTuples.orgRole(org, employee, "super_admin"));
		audit.publish("iam", "UserRole", user, "BOOTSTRAP", null, Map.of("role", "super_admin", "email", work));
		log.info("{} is now a super admin (bootstrap)", work);
		events.publishEvent(new com.ivoyant.hrms.common.event.BootstrapAdminReadyEvent(employee));
	}

	private UUID createEmployee(UUID org, String email) {
		UUID id = UUID.randomUUID();
		String local = email.substring(0, email.indexOf('@'));
		String[] parts = local.split("[._-]+");
		String first = capitalise(parts[0]);
		String last = parts.length > 1 ? capitalise(parts[parts.length - 1]) : "Admin";
		jdbc.sql("""
				insert into employee (id, org_id, employee_code, first_name, last_name, display_name, work_email,
				    employment_type, employment_type_id, status, joining_date)
				values (:id, :org, :code, :first, :last, :display, :email, 'Full-time',
				    (select t.id from employment_type t where t.org_id = :org and t.code = 'FT'), 'ACTIVE', :joined)
				""").param("id", id).param("org", org).param("code", codes.next(LocalDate.now()))
				.param("first", first).param("last", last).param("display", first + " " + last).param("email", email)
				.param("joined", LocalDate.now()).update();
		FgaTuples.employeeAll(org, id, null, null, null).forEach(fga::write);
		log.info("Created employee {} for the bootstrap super admin", email);
		return id;
	}

	private void assign(UUID org, UUID user, UUID employee, String roleKey, String scope) {
		int rows = jdbc.sql("""
				insert into user_role (id, user_id, role_id, scope)
				select gen_random_uuid(), :user, r.id, :scope from role r
				where r.org_id = :org and r.key = :key
				  and not exists (select 1 from user_role x where x.user_id = :user and x.role_id = r.id and x.scope = :scope)
				""").param("user", user).param("scope", scope).param("org", org).param("key", roleKey).update();
		if (rows > 0) {
			fga.write(FgaTuples.roleMember(employee, roleKey));
		}
	}

	private static String capitalise(String s) {
		return s.isEmpty() ? s : Arrays.stream(new String[] {s}).map(x -> x.substring(0, 1).toUpperCase(Locale.ROOT) + x.substring(1))
				.collect(Collectors.joining());
	}
}
