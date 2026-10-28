package com.ivoyant.hrms.common.seed;

import com.ivoyant.hrms.common.authz.AuthzService;
import com.ivoyant.hrms.common.authz.FgaSyncService;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Local profile only: seeds locations, departments, designations and ~12 employees (a manager chain 3 levels
 * deep, a super admin, an HR admin and a recruiting area owner), then queues the matching OpenFGA tuples.
 * Idempotent: every row has a deterministic id and is inserted with {@code on conflict do nothing}.
 * Logs the {@code X-Dev-User} value of every seeded employee.
 */
@Component
@Profile("local")
@Order(2)
class LocalSeed implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(LocalSeed.class);
	private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000001");

	private record Emp(String code, String first, String last, String dept, String designation, String location,
			String manager, String roleKey, boolean head) {
	}

	/**
	 * A starter location. {@code key} is the name the location was first seeded under: ids are derived from it, so
	 * employees keep pointing at the same row after the location is renamed.
	 */
	private record Loc(String key, String name, String code, String type, String address, String city, String state,
			String pin, String phone, String email, String start, String end, int grace, String methods, int radius,
			boolean lwf, boolean esi, String seReg, String gstin, Integer capacity, boolean store, String calendar,
			String headCode) {
	}

	private static final List<Loc> LOCATIONS = List.of(
			new Loc("Bengaluru", "Bengaluru HQ", "BLR-HQ", "HEAD_OFFICE",
					"Prestige Tech Park, Tower B, 4th floor, Outer Ring Road, Marathahalli", "Bengaluru", "Karnataka",
					"560103", "+91 80 4718 2200", "blr.office@ivoyant.com", "09:30", "18:30", 15, "BIOMETRIC,WEB,MOBILE_GPS", 200,
					true, true, "KA/BLR/SE/2016/04812", "29AAFCI4521K1ZP", 30, true, "Karnataka", "EMP003"),
			new Loc("Hyderabad", "Hyderabad office", "HYD-01", "BRANCH_OFFICE",
					"Knowledge City, Plot 12, Raidurg, HITEC City", "Hyderabad", "Telangana", "500081", "+91 40 4520 7710",
					"hyd.office@ivoyant.com", "09:00", "18:00", 10, "BIOMETRIC,MOBILE_GPS", 150, true, true,
					"TS/HYD/SE/2019/11230", "36AAFCI4521K1ZQ", 14, true, "Telangana", "EMP008"),
			new Loc("Mumbai", "Mumbai BKC", "MUM-01", "CO_WORKING", "WeWork, G Block, Bandra Kurla Complex, Bandra East",
					"Mumbai", "Maharashtra", "400051", "+91 22 6190 4400", "mum.office@ivoyant.com", "10:00", "19:00", 15,
					"WEB,QR", 100, true, false, "MH/MUM/SE/2017/77310", "27AAFCI4521K1ZR", 8, false, "Maharashtra", "EMP004"),
			new Loc("Pune", "Pune studio", "PUN-01", "CO_WORKING", "Smartworks, 3rd floor, Baner–Pashan Link Road, Baner",
					"Pune", "Maharashtra", "411045", "+91 20 6720 1180", "pune.office@ivoyant.com", "10:00", "19:00", 15,
					"WEB,MOBILE_GPS", 150, true, false, "MH/PUN/SE/2018/20417", "27AAFCI4521K1ZR", 6, false, "Maharashtra",
					"EMP006"),
			new Loc("Remote", "Remote", "REMOTE", "REMOTE_HUB", null, "Remote", null, null, null, null, "09:30", "18:30",
					15, "WEB", 150, false, false, null, null, null, false, "National holidays only", null));
	/** A starter position: name, grade, track, min experience, level, band in lakhs, reports to (name), approves, job level. */
	private record Pos(String name, String grade, String track, double minExp, int level, double bandMinL, double bandMaxL,
			String reportsTo, boolean approves, String jobLevel) {
	}

	private static final List<Pos> POSITIONS = List.of(
			new Pos("Chief Executive", "G10", "EXECUTIVE", 15, 10, 90, 300, null, true, "L8"),
			new Pos("Vice President", "G9", "EXECUTIVE", 12, 9, 60, 120, "Chief Executive", true, null),
			new Pos("Director", "G8", "MANAGEMENT", 10, 8, 36, 70, "Vice President", true, null),
			new Pos("Senior Manager", "G7", "MANAGEMENT", 8, 7, 30, 50, "Director", true, null),
			new Pos("Manager", "G6", "MANAGEMENT", 6, 6, 22, 40, "Senior Manager", true, null),
			new Pos("Lead", "G5", "INDIVIDUAL_CONTRIBUTOR", 5, 5, 15, 30, "Manager", true, null),
			new Pos("Senior Associate", "G4", "INDIVIDUAL_CONTRIBUTOR", 3, 4, 11, 24, "Lead", false, null),
			new Pos("Associate", "G3", "INDIVIDUAL_CONTRIBUTOR", 1, 3, 7, 16, "Lead", false, null),
			new Pos("Junior Associate", "G2", "INDIVIDUAL_CONTRIBUTOR", 0, 2, 3.5, 8, "Senior Associate", false, "L1"),
			new Pos("Trainee", "G1", "INDIVIDUAL_CONTRIBUTOR", 0, 1, 2, 4, "Senior Associate", false, "L1"));

	/**
	 * The job titles the seed people carry, and the position each belongs to. (They were once seeded as positions
	 * themselves; those rows are moved over to the real positions below.)
	 */
	private static final Map<String, String> POSITION_OF_TITLE = Map.ofEntries(Map.entry("CEO", "Chief Executive"),
			Map.entry("VP Engineering", "Vice President"), Map.entry("Head of People", "Director"),
			Map.entry("Finance Head", "Director"), Map.entry("Sales Head", "Director"), Map.entry("IT Head", "Director"),
			Map.entry("Design Lead", "Lead"), Map.entry("Software Engineer", "Senior Associate"),
			Map.entry("Recruiter", "Associate"), Map.entry("Accountant", "Associate"),
			Map.entry("Sales Executive", "Associate"), Map.entry("Product Designer", "Senior Associate"));

	/** Short codes for the seeded titles (initials would collide: Head of People / Head of Product). */
	private static final Map<String, String> TITLE_CODES = Map.ofEntries(Map.entry("CEO", "CEO"),
			Map.entry("VP Engineering", "VPE"), Map.entry("Head of People", "HPPL"), Map.entry("Finance Head", "FINH"),
			Map.entry("Sales Head", "SALH"), Map.entry("IT Head", "ITH"), Map.entry("Design Lead", "DSL"),
			Map.entry("Software Engineer", "SWE"), Map.entry("Recruiter", "REC"), Map.entry("Accountant", "ACC"),
			Map.entry("Sales Executive", "SEX"), Map.entry("Product Designer", "PDS"));

	/** A starter team: name, department, lead. Everyone in the department is a member; the first team listed for a department is primary. */
	private record SeedTeam(String name, String dept, String lead) {
	}

	private static final List<SeedTeam> TEAMS = List.of(new SeedTeam("Platform", "Engineering", "EMP003"),
			new SeedTeam("Mobile apps", "Engineering", "EMP003"), new SeedTeam("Design system", "Design", "EMP007"),
			new SeedTeam("Inside sales", "Sales", "EMP005"), new SeedTeam("Payroll ops", "Finance", "EMP004"),
			new SeedTeam("People ops", "People", "EMP002"), new SeedTeam("IT & facilities", "IT", "EMP006"));

	/** A starter project: name, code, client, status, manager. */
	private record SeedProject(String name, String code, String client, String status, String manager) {
	}

	private static final List<SeedProject> PROJECTS = List.of(
			new SeedProject("Dataflux platform", "DFX", "Internal", "ACTIVE", "EMP003"),
			new SeedProject("HRMS revamp", "HRM", "Internal", "ACTIVE", "EMP003"),
			new SeedProject("Northwind rollout", "NWR", "Northwind Traders", "ACTIVE", "EMP003"),
			new SeedProject("Design system", "DSY", "Internal", "ACTIVE", "EMP007"),
			new SeedProject("Contoso analytics", "CTA", "Contoso Ltd", "PLANNED", "EMP003"),
			new SeedProject("Q4 brand refresh", "BRD", "Internal", "ON_HOLD", "EMP005"));

	/** Who is on which project: employee code, project code, role, allocation (people stay at or under 100%). */
	private static final List<String[]> PROJECT_MEMBERS = List.of(new String[] {"EMP003", "HRM", "LEAD", "60"},
			new String[] {"EMP003", "NWR", "CONTRIBUTOR", "40"}, new String[] {"EMP008", "HRM", "CONTRIBUTOR", "60"},
			new String[] {"EMP008", "DFX", "CONTRIBUTOR", "40"}, new String[] {"EMP007", "DSY", "LEAD", "50"},
			new String[] {"EMP007", "BRD", "CONTRIBUTOR", "50"}, new String[] {"EMP012", "DSY", "CONTRIBUTOR", "100"});

	private static final Map<String, String> DEPARTMENTS = Map.of("Engineering", "ENG", "Design", "DES", "Sales",
			"SAL", "Finance", "FIN", "People", "PPL", "IT", "IT");
	private static final List<Emp> EMPLOYEES = List.of(
			new Emp("EMP001", "Aarav", "Mehta", "Engineering", "CEO", "Bengaluru", null, "super_admin", false),
			new Emp("EMP002", "Meera", "Iyer", "People", "Head of People", "Bengaluru", "EMP001", "hr_admin", true),
			new Emp("EMP003", "Priya", "Nair", "Engineering", "VP Engineering", "Bengaluru", "EMP001", "manager", true),
			new Emp("EMP004", "Rohan", "Shah", "Finance", "Finance Head", "Mumbai", "EMP001", "finance", true),
			new Emp("EMP005", "Kabir", "Singh", "Sales", "Sales Head", "Mumbai", "EMP001", "manager", true),
			new Emp("EMP006", "Sanjay", "Kulkarni", "IT", "IT Head", "Pune", "EMP001", "it_admin", true),
			new Emp("EMP007", "Ananya", "Rao", "Design", "Design Lead", "Bengaluru", "EMP003", "manager", true),
			new Emp("EMP008", "Vikram", "Patel", "Engineering", "Software Engineer", "Hyderabad", "EMP003", null, false),
			new Emp("EMP009", "Isha", "Kapoor", "People", "Recruiter", "Remote", "EMP002", "hr_ops", false),
			new Emp("EMP010", "Neha", "Gupta", "Finance", "Accountant", "Mumbai", "EMP004", null, false),
			new Emp("EMP011", "Arjun", "Reddy", "Sales", "Sales Executive", "Hyderabad", "EMP005", null, false),
			new Emp("EMP012", "Divya", "Menon", "Design", "Product Designer", "Remote", "EMP007", null, false));

	private final JdbcClient jdbc;
	private final FgaSyncService fgaSync;
	private final AuthzService authz;

	LocalSeed(JdbcClient jdbc, FgaSyncService fgaSync, AuthzService authz) {
		this.jdbc = jdbc;
		this.fgaSync = fgaSync;
		this.authz = authz;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		int inserted = seed();
		if (inserted > 0 || authz.isFreshStore()) {
			fgaSync.syncAll();
		}
		log.info("Local seed ready ({} new rows). Call the API with header  X-Dev-User: <id>  (profile 'local'):", inserted);
		for (Emp e : EMPLOYEES) {
			log.info("  {}  {} {}  ({}, {})", employeeId(e.code()), e.first(), e.last(), e.designation(),
					e.roleKey() == null ? "employee" : e.roleKey());
		}
	}

	private int seed() {
		int count = 0;
		for (Loc l : LOCATIONS) {
			count += jdbc.sql("""
					insert into location (id, org_id, name, code, type, address_line, city, state, postal_code, country,
					    timezone, phone, email, week_pattern, start_time, end_time, grace_minutes, holiday_calendar_id,
					    attendance_methods, geo_radius_m, wfh_allowed, lwf_applicable, esi_notified, shops_estab_reg_no,
					    gstin, seat_capacity, asset_store)
					values (:id, :org, :name, :code, :type, :addr, :city, :state, :pin, 'IN', 'Asia/Kolkata', :phone, :email,
					    'MON_FRI', cast(:start as time), cast(:end as time), :grace,
					    (select c.id from holiday_calendar c where c.org_id = :org and c.name = :calendar),
					    string_to_array(:methods, ','), :radius, true, :lwf, :esi, :se, :gstin, :cap, :store)
					on conflict do nothing
					""")
					.param("id", id("location", l.key())).param("org", ORG).param("name", l.name()).param("code", l.code())
					.param("type", l.type()).param("addr", l.address(), java.sql.Types.VARCHAR).param("city", l.city())
					.param("state", l.state(), java.sql.Types.VARCHAR).param("pin", l.pin(), java.sql.Types.VARCHAR)
					.param("phone", l.phone(), java.sql.Types.VARCHAR).param("email", l.email(), java.sql.Types.VARCHAR)
					.param("start", l.start()).param("end", l.end()).param("grace", l.grace())
					.param("calendar", l.calendar()).param("methods", l.methods()).param("radius", l.radius())
					.param("lwf", l.lwf()).param("esi", l.esi()).param("se", l.seReg(), java.sql.Types.VARCHAR)
					.param("gstin", l.gstin(), java.sql.Types.VARCHAR).param("cap", l.capacity(), java.sql.Types.INTEGER)
					.param("store", l.store()).update();
		}
		for (Map.Entry<String, String> d : DEPARTMENTS.entrySet()) {
			count += jdbc.sql("insert into department (id, org_id, name, code) values (:id, :org, :name, :code) "
					+ "on conflict do nothing")
					.param("id", id("department", d.getKey())).param("org", ORG)
					.param("name", d.getKey()).param("code", d.getValue()).update();
		}
		count += seedPositions();
		LocalDate joined = LocalDate.now().minusYears(2);
		for (Emp e : EMPLOYEES) {
			count += jdbc.sql("""
					insert into employee (id, org_id, employee_code, first_name, last_name, display_name, work_email,
					    department_id, designation_id, location_id, manager_id, employment_type, employment_type_id, status,
					    joining_date)
					values (:id, :org, :code, :first, :last, :display, :email, :dept, :desig, :loc, :mgr,
					    'Full-time', (select t.id from employment_type t where t.org_id = :org and t.code = 'FT'), 'ACTIVE', :joined)
					on conflict do nothing
					""")
					.param("id", employeeId(e.code())).param("org", ORG).param("code", e.code())
					.param("first", e.first()).param("last", e.last()).param("display", e.first() + " " + e.last())
					.param("email", (e.first() + "." + e.last() + "@ivoyant.com").toLowerCase())
					.param("dept", id("department", e.dept())).param("desig", id("designation", POSITION_OF_TITLE.get(e.designation())))
					.param("loc", id("location", e.location()))
					.param("mgr", e.manager() == null ? null : employeeId(e.manager()), java.sql.Types.OTHER)
					.param("joined", joined).update();
			count += jdbc.sql("""
					insert into reporting_line_history (org_id, employee_id, manager_id, from_date, reason)
					select :org, :e, :m, :d, 'HIRE'
					where not exists (select 1 from reporting_line_history h where h.employee_id = :e)
					""").param("org", ORG).param("e", employeeId(e.code())).param("m", e.manager() == null ? null : employeeId(e.manager()), java.sql.Types.OTHER)
					.param("d", joined).update();
			count += jdbc.sql("update employee set title_id = :t where id = :e and title_id is null")
					.param("t", id("job_title", e.designation())).param("e", employeeId(e.code())).update();
			count += jdbc.sql("""
					insert into employee_title_history (org_id, employee_id, title_id, is_primary, from_date, reason)
					select :org, :e, :t, true, :d, 'HIRE'
					where not exists (select 1 from employee_title_history h where h.employee_id = :e)
					""").param("org", ORG).param("e", employeeId(e.code())).param("t", id("job_title", e.designation()))
					.param("d", joined).update();
			if (e.head()) {
				jdbc.sql("update department set head_employee_id = :emp where id = :dept and head_employee_id is null")
						.param("emp", employeeId(e.code())).param("dept", id("department", e.dept())).update();
			}
			UUID userId = id("user", e.code());
			count += jdbc.sql("insert into app_user (id, org_id, employee_id, email, activated_at) select :id, :org, e.id, lower(e.work_email), now() from employee e where e.id = :emp "
					+ "on conflict do nothing")
					.param("id", userId).param("org", ORG).param("emp", employeeId(e.code())).update();
			count += assignRole(userId, "employee", "OWN");
			if (e.roleKey() != null) {
				count += assignRole(userId, e.roleKey(), "ORGANIZATION");
			}
			if (e.manager() == null || EMPLOYEES.stream().anyMatch(x -> e.code().equals(x.manager()))) {
				count += assignRole(userId, "manager", "TEAM");
			}
		}
		for (Loc l : LOCATIONS) {
			if (l.headCode() != null) {
				jdbc.sql("update location set head_employee_id = :emp where id = :loc and head_employee_id is null")
						.param("emp", employeeId(l.headCode())).param("loc", id("location", l.key())).update();
			}
		}
		count += seedTeamsAndProjects();
		// one dotted line and one mentor, so the relations screen has something to show
		for (String[] r : new String[][] {{"EMP008", "EMP007", "DOTTED", "Design system collaboration"}, {"EMP012", "EMP003", "MENTOR", "Buddy for the first year"}}) {
			count += jdbc.sql("""
					insert into employee_relation (org_id, employee_id, related_id, type, from_date, note)
					select :org, :e, :r, :t, current_date, :n
					where not exists (select 1 from employee_relation x where x.employee_id = :e and x.related_id = :r and x.type = :t)
					""").param("org", ORG).param("e", employeeId(r[0])).param("r", employeeId(r[1])).param("t", r[2]).param("n", r[3]).update();
		}
		// the recruiter owns the recruiting configuration area
		count += jdbc.sql("insert into area_owner (id, org_id, area_key, user_id) values (:id, :org, 'recruiting', :user) "
				+ "on conflict do nothing")
				.param("id", id("area_owner", "recruiting")).param("org", ORG).param("user", id("user", "EMP009"))
				.update();
		return count;
	}

	/** Starter teams (everyone in a team's department is a member) and projects with a few allocations. Idempotent. */
	private int seedTeamsAndProjects() {
		int count = 0;
		java.util.Set<String> primaryTaken = new java.util.HashSet<>();
		for (SeedTeam t : TEAMS) {
			count += jdbc.sql("""
					insert into team (id, org_id, name, department_id, lead_employee_id)
					values (:id, :org, :name, :dept, :lead) on conflict (id) do nothing
					""").param("id", id("team", t.name())).param("org", ORG).param("name", t.name())
					.param("dept", id("department", t.dept())).param("lead", employeeId(t.lead())).update();
			boolean primary = primaryTaken.add(t.dept());
			count += jdbc.sql("""
					insert into team_member (org_id, team_id, employee_id, is_primary)
					select :org, :team, e.id, :primary from employee e
					where e.org_id = :org and e.department_id = :dept and e.status <> 'EXITED'
					  and not exists (select 1 from team_member m where m.team_id = :team and m.employee_id = e.id)
					  and (not :primary or not exists (select 1 from team_member m where m.employee_id = e.id and m.is_primary and m.until is null))
					""").param("org", ORG).param("team", id("team", t.name())).param("dept", id("department", t.dept()))
					.param("primary", primary).update();
		}
		for (SeedProject p : PROJECTS) {
			count += jdbc.sql("""
					insert into project (id, org_id, name, code, client, status, owner_employee_id, billable)
					values (:id, :org, :name, :code, :client, :status, :owner, :billable) on conflict (id) do nothing
					""").param("id", id("project", p.code())).param("org", ORG).param("name", p.name()).param("code", p.code())
					.param("client", p.client()).param("status", p.status()).param("owner", employeeId(p.manager()))
					.param("billable", !"Internal".equals(p.client())).update();
		}
		for (String[] m : PROJECT_MEMBERS) {
			count += jdbc.sql("""
					insert into project_member (org_id, project_id, employee_id, role, allocation)
					select :org, :p, :e, :role, :alloc
					where not exists (select 1 from project_member x where x.project_id = :p and x.employee_id = :e and x.until is null)
					""").param("org", ORG).param("p", id("project", m[1])).param("e", employeeId(m[0])).param("role", m[2])
					.param("alloc", Integer.parseInt(m[3])).update();
		}
		return count;
	}

	/**
	 * The ten standard positions, one title per seeded job title, and (once) the move of people from the old
	 * "position per title" rows onto them. Idempotent.
	 */
	private int seedPositions() {
		int count = 0;
		List<UUID> legacy = POSITION_OF_TITLE.keySet().stream().map(t -> id("designation", t)).toList();
		// legacy rows must stop being roots before the standard top is inserted
		count += jdbc.sql("update designation set active = false, reports_to_id = null where org_id = :org and id in (:ids) and active")
				.param("org", ORG).param("ids", legacy).update();
		for (Pos p : POSITIONS) {
			count += jdbc.sql("""
					insert into designation (id, org_id, title, code, level, track, band_min, band_max, min_exp, approves,
					    job_level_id)
					values (:id, :org, :title, :code, :level, :track, :bmin, :bmax, :minExp, :approves,
					    (select l.id from job_level l where l.org_id = :org and l.code = :jl))
					on conflict (id) do nothing
					""")
					.param("id", id("designation", p.name())).param("org", ORG).param("title", p.name())
					.param("code", p.grade()).param("level", p.level()).param("track", p.track())
					.param("bmin", java.math.BigDecimal.valueOf(p.bandMinL() * 100_000).setScale(2))
					.param("bmax", java.math.BigDecimal.valueOf(p.bandMaxL() * 100_000).setScale(2))
					.param("minExp", java.math.BigDecimal.valueOf(p.minExp())).param("approves", p.approves())
					.param("jl", p.jobLevel(), java.sql.Types.VARCHAR).update();
		}
		for (Pos p : POSITIONS) {
			if (p.reportsTo() != null) {
				count += jdbc.sql("update designation set reports_to_id = :up where id = :id and reports_to_id is null")
						.param("up", id("designation", p.reportsTo())).param("id", id("designation", p.name())).update();
			}
		}
		for (Map.Entry<String, String> t : POSITION_OF_TITLE.entrySet()) {
			// a seeded title is limited to the department its (only) holder is in
			String dept = "CEO".equals(t.getKey()) ? null
					: EMPLOYEES.stream().filter(x -> x.designation().equals(t.getKey())).map(Emp::dept).findFirst().orElse(null);
			count += jdbc.sql("""
					insert into job_title (id, org_id, name, code, designation_id, department_id)
					values (:id, :org, :name, :code, :pos, :dept)
					on conflict do nothing
					""")
					.param("id", id("job_title", t.getKey())).param("org", ORG).param("name", t.getKey())
					.param("code", TITLE_CODES.get(t.getKey())).param("pos", id("designation", t.getValue()))
					.param("dept", dept == null ? null : id("department", dept), java.sql.Types.OTHER).update();
			count += jdbc.sql("update job_title set code = :code, department_id = :dept where id = :id and code is null")
					.param("code", TITLE_CODES.get(t.getKey())).param("id", id("job_title", t.getKey()))
					.param("dept", dept == null ? null : id("department", dept), java.sql.Types.OTHER).update();
			UUID old = id("designation", t.getKey());
			count += jdbc.sql("update employee set designation_id = :new where designation_id = :old")
					.param("new", id("designation", t.getValue())).param("old", old).update();
		}
		count += jdbc.sql("update designation set reports_to_id = null where reports_to_id in (:ids) and not active")
				.param("ids", legacy).update();
		count += jdbc.sql("""
				delete from designation d where d.id in (:ids) and not d.active
				  and not exists (select 1 from employee e where e.designation_id = d.id)
				  and not exists (select 1 from job_title t where t.designation_id = d.id)
				  and not exists (select 1 from designation c where c.reports_to_id = d.id)
				""").param("ids", legacy).update();
		return count;
	}

	private int assignRole(UUID userId, String roleKey, String scope) {
		return jdbc.sql("""
				insert into user_role (id, user_id, role_id, scope)
				select :id, :user, r.id, :scope from role r where r.org_id = :org and r.key = :key
				on conflict do nothing
				""")
				.param("id", id("user_role", userId + roleKey + scope)).param("user", userId).param("scope", scope)
				.param("org", ORG).param("key", roleKey).update();
	}

	static UUID employeeId(String code) {
		return id("employee", code);
	}

	private static UUID id(String kind, String name) {
		return UUID.nameUUIDFromBytes(("hrms-seed:" + kind + ":" + name).getBytes(StandardCharsets.UTF_8));
	}
}
