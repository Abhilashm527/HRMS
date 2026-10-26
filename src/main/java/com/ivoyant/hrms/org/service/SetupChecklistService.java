package com.ivoyant.hrms.org.service;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.authz.AuthzService;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.org.OrgProfile;
import com.ivoyant.hrms.org.OrgSettings;
import com.ivoyant.hrms.org.OrgView;
import com.ivoyant.hrms.org.domain.SetupAck;
import com.ivoyant.hrms.org.domain.SetupAckId;
import com.ivoyant.hrms.org.dto.SetupResponse;
import com.ivoyant.hrms.org.repo.SetupAckRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Setup checklist (12 steps). Whether a step is done is worked out from the data on every call; only the
 * "reviewed" acknowledgements of steps an admin has to confirm are stored. Other modules' data is read with plain
 * queries so this module does not depend on them.
 */
@Service
public class SetupChecklistService {

	/** Steps an admin confirms by hand. */
	private static final Set<String> ACKABLE = Set.of("org_cfg", "roles", "ep", "fls");
	private static final Map<String, String> ACK_TITLES = Map.of("org_cfg", "Organization settings", "roles", "Roles",
			"ep", "Menus, tabs & fields", "fls", "Field security");

	private final SetupAckRepository acks;
	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final OrgProfile profile;
	private final OrgSettings settings;
	private final AuthzService authz;
	private final Audit audit;

	public SetupChecklistService(SetupAckRepository acks, JdbcClient jdbc, CurrentUser currentUser, OrgProfile profile,
			OrgSettings settings, AuthzService authz, Audit audit) {
		this.acks = acks;
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.profile = profile;
		this.settings = settings;
		this.authz = authz;
		this.audit = audit;
	}

	@Transactional(readOnly = true)
	public SetupResponse get() {
		UUID org = currentUser.orgId();
		Set<String> acked = new java.util.HashSet<>();
		acks.findByOrgId(org).forEach(a -> acked.add(a.getKey()));
		OrgView o = profile.current();
		List<SetupResponse.Step> steps = new ArrayList<>();

		boolean orgOk = notBlank(o.name()) && notBlank(o.code()) && notBlank(o.country());
		steps.add(step(1, "org", "orgprof", "Create the organization",
				"Name, legal entity, country, time zone and financial year.", orgOk, o.name() + " · " + o.code(), false));
		boolean cfg = acked.contains("org_cfg");
		steps.add(step(2, "org_cfg", "orgprof", "Organization settings",
				"Employee ID pattern, nesting, salary visibility and security defaults.", cfg,
				cfg ? "Reviewed" : "Review and confirm the defaults", true));

		long depts = count("select count(*) from department where org_id = :o and active", org);
		long noHead = count("select count(*) from department where org_id = :o and active and head_employee_id is null", org);
		steps.add(step(3, "depts", "depts", "Department hierarchy", "Departments, sub-departments and their heads.",
				depts > 0 && noHead == 0, depts + plural(depts, " department") + (noHead > 0 ? " · " + noHead + " without a head" : ""), false));

		long positions = count("select count(*) from designation where org_id = :o and active", org);
		long noBand = count("select count(*) from designation where org_id = :o and active and coalesce(band_max, 0) <= 0", org);
		steps.add(step(4, "positions", "desigs", "Positions", "Job levels and salary bands.", positions > 0 && noBand == 0,
				positions + plural(positions, " position") + (noBand > 0 ? " · " + noBand + " without a band" : ""), false));

		long titles = count("select count(*) from job_title where org_id = :o and active", org);
		long titleNoPos = count("select count(*) from job_title where org_id = :o and active and designation_id is null", org);
		long unlisted = count("select count(*) from employee where org_id = :o and status <> 'EXITED' and title_id is null", org);
		steps.add(step(5, "titles", "titles", "Titles", "Job titles, each mapped to a position.",
				titles > 0 && titleNoPos == 0 && unlisted == 0,
				titles + plural(titles, " title") + (titleNoPos > 0 ? " · " + titleNoPos + " without a position" : "")
						+ (unlisted > 0 ? " · " + unlisted + (unlisted == 1 ? " person" : " people") + " with an unlisted title" : ""),
				false));

		long roles = count("select count(*) from role where org_id = :o", org);
		long custom = count("select count(*) from role where org_id = :o and not system", org);
		boolean rolesAck = acked.contains("roles");
		steps.add(step(6, "roles", "roles", "Roles", "System roles plus the custom roles you need.", rolesAck,
				roles + plural(roles, " role") + " · " + custom + " custom", true));

		boolean ep = acked.contains("ep");
		steps.add(step(7, "ep", "ep", "Menus, tabs & fields",
				"For each role: which menus show, which tabs inside them, and what each field shows.", ep,
				ep ? "Reviewed" : "Check who can see salary, bank and ID numbers", true));

		boolean fga = authz.configured();
		steps.add(step(8, "fga", "fga", "Connect OpenFGA",
				"Store ID, model ID and auto-sync so every check runs in OpenFGA.", fga,
				fga ? "Connected · auto-sync on" : "Not connected", false));

		long assignments = count("""
				select count(*) from user_role ur join app_user u on u.id = ur.user_id where u.org_id = :o""", org);
		steps.add(step(9, "assign", "assign", "Assign roles", "Admins, payroll, auditors and delegations.", assignments > 0,
				assignments + " active " + (assignments == 1 ? "assignment" : "assignments"), false));

		// the people who hold the top position have no manager by design; anyone else without one is a gap
		long noManager = count("""
				select count(*) from employee e left join designation d on d.id = e.designation_id
				where e.org_id = :o and e.status <> 'EXITED' and e.manager_id is null
				  and not (d.active and d.reports_to_id is null)""", org);
		steps.add(step(10, "reporting", "reporting", "Reporting lines",
				"Everyone has a manager; add dotted lines and mentors.", noManager == 0,
				noManager == 0 ? "Everyone has a manager" : noManager + (noManager == 1 ? " person has" : " people have") + " no manager",
				false));

		long cfields = count("select count(*) from config_field where org_id = :o and not system and active", org);
		steps.add(step(11, "cfields", "cfields", "Custom fields",
				"Extra data on employees, departments, positions and titles.", cfields > 0, cfields + " active fields", false));

		boolean sec = settings.twoFactor() && settings.auditLog() && settings.logPermissionChanges();
		steps.add(step(12, "security", "audit", "Security & audit", "2-step sign-in and audit logging.", sec,
				sec ? "2-step on · audit log on" : "Turn on 2-step sign-in and audit logging in Organization", false));

		int done = (int) steps.stream().filter(SetupResponse.Step::done).count();
		return new SetupResponse(done, steps.size(), Math.round(done * 100f / steps.size()), done + " of " + steps.size() + " done", steps);
	}

	@Transactional
	public SetupResponse ack(String key) {
		requireKey(key);
		UUID org = currentUser.orgId();
		if (!acks.existsById(new SetupAckId(org, key))) {
			acks.saveAndFlush(new SetupAck(org, key, currentUser.requireEmployeeId()));
			audit.publish("org", "SetupStep", key, "ACK", null, Map.of("step", ACK_TITLES.get(key)));
		}
		return get();
	}

	@Transactional
	public SetupResponse unack(String key) {
		requireKey(key);
		acks.deleteById(new SetupAckId(currentUser.orgId(), key));
		audit.publish("org", "SetupStep", key, "UNACK", Map.of("step", ACK_TITLES.get(key)), null);
		return get();
	}

	private void requireKey(String key) {
		if (!ACKABLE.contains(key)) {
			throw new NotFoundException("Step '" + key + "' is not confirmed by hand");
		}
	}

	private long count(String sql, UUID org) {
		return jdbc.sql(sql).param("o", org).query(Long.class).single();
	}

	private static String plural(long n, String word) {
		return n == 1 ? word : word + "s";
	}

	private static boolean notBlank(String s) {
		return s != null && !s.isBlank();
	}

	private static SetupResponse.Step step(int n, String key, String tab, String title, String desc, boolean done,
			String status, boolean canAck) {
		return new SetupResponse.Step(n, key, tab, title, desc, done, status, canAck);
	}
}
