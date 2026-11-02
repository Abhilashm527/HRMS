package com.ivoyant.hrms.payroll.service;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.security.ModuleAccess;
import com.ivoyant.hrms.common.web.ConflictException;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.ForbiddenException;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.common.web.UnprocessableException;
import com.ivoyant.hrms.notification.Notifier;
import com.ivoyant.hrms.payroll.service.PayrollEngine.Adjustment;
import com.ivoyant.hrms.payroll.service.PayrollEngine.Declaration;
import com.ivoyant.hrms.payroll.service.PayrollEngine.Line;
import com.ivoyant.hrms.payroll.service.PayrollEngine.Result;
import com.ivoyant.hrms.payroll.service.PayrollEngine.Settings;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Monthly payroll. A run for a month is created as a draft with a payslip for everyone who has a salary and worked that month; it then
 * moves through Review (check the flagged payslips), Approve and Disburse. Payslips become visible to employees when the run is
 * disbursed. Loss of pay comes from approved unpaid leave, plus any days HR adds on a payslip. Needs Payroll access: View to look,
 * Edit to run and change salaries, Approve to approve, Full to disburse.
 */
@Service
public class PayrollService {

	public record FileContent(byte[] bytes, String mime, String name) {
	}

	static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);
	private static final List<String> STEPS = List.of("DRAFT", "REVIEWED", "APPROVED", "PAID");

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final ModuleAccess access;
	private final Notifier notifier;
	private final Audit audit;
	private final JsonMapper json;

	PayrollService(JdbcClient jdbc, CurrentUser currentUser, ModuleAccess access, Notifier notifier, Audit audit, JsonMapper json) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.access = access;
		this.notifier = notifier;
		this.audit = audit;
		this.json = json;
	}

	// ================================================================== settings & salaries

	Settings settings() {
		jdbc.sql("insert into payroll_settings (org_id) values (:org) on conflict do nothing").param("org", org()).update();
		return jdbc.sql("""
				select basic_pct, hra_pct_of_basic, pf_rate, pf_wage_cap, esi_threshold, esi_employee_rate, esi_employer_rate from payroll_settings where org_id = :org""")
				.param("org", org()).query((rs, i) -> new Settings(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getBigDecimal(5),
						rs.getBigDecimal(6), rs.getBigDecimal(7)))
				.single();
	}

	@Transactional
	public Map<String, Object> settingsView() {
		require("VIEW");
		Settings s = settings();
		int payDay = jdbc.sql("select pay_day from payroll_settings where org_id = :org").param("org", org()).query(Integer.class).single();
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("basicPct", s.basicPct());
		m.put("hraPctOfBasic", s.hraPctOfBasic());
		m.put("pfRate", s.pfRate());
		m.put("pfWageCap", s.pfWageCap());
		m.put("esiThreshold", s.esiThreshold());
		m.put("esiEmployeeRate", s.esiEmployee());
		m.put("esiEmployerRate", s.esiEmployer());
		m.put("payDay", payDay);
		m.put("canEdit", access.has("payroll", "ADMIN"));
		return m;
	}

	@Transactional
	public Map<String, Object> saveSettings(Map<String, Object> b) {
		require("ADMIN");
		settings();
		BigDecimal basic = num(b.get("basicPct"), "basicPct", 10, 100);
		BigDecimal hra = num(b.get("hraPctOfBasic"), "hraPctOfBasic", 0, 100);
		BigDecimal pfCap = num(b.get("pfWageCap"), "pfWageCap", 0, 1_000_000);
		BigDecimal esiT = num(b.get("esiThreshold"), "esiThreshold", 0, 1_000_000);
		int day = num(b.get("payDay"), "payDay", 1, 31).intValue();
		jdbc.sql("""
				update payroll_settings set basic_pct = :b, hra_pct_of_basic = :h, pf_wage_cap = :pc, esi_threshold = :et, pay_day = :d, updated_at = now() where org_id = :org""")
				.param("b", basic).param("h", hra).param("pc", pfCap).param("et", esiT).param("d", day).param("org", org()).update();
		audit.publish("payroll", "PayrollSettings", org(), "UPDATE", null, b);
		return settingsView();
	}

	/** Everyone active with their salary (or the CTC on their profile when payroll has no salary yet). */
	@Transactional(readOnly = true)
	public Map<String, Object> salaries() {
		require("VIEW");
		Map<UUID, Map<String, String>> ids = identity();
		List<Map<String, Object>> rows = jdbc.sql("""
				select e.id, e.display_name, e.employee_code, jt.name, d.name, s.ctc_annual, s.regime, s.pf_enabled, s.effective_from, e.custom->>'ctc', l.state
				from employee e left join salary s on s.employee_id = e.id left join job_title jt on jt.id = e.title_id left join department d on d.id = e.department_id
				left join location l on l.id = e.location_id
				where e.org_id = :org and e.archived_at is null and e.status <> 'EXITED' order by lower(e.display_name)""").param("org", org()).query((rs, i) -> {
			Map<String, Object> m = new LinkedHashMap<>();
			UUID id = rs.getObject(1, UUID.class);
			m.put("id", id);
			m.put("name", rs.getString(2));
			m.put("code", rs.getString(3));
			m.put("title", rs.getString(4));
			m.put("department", rs.getString(5));
			BigDecimal ctc = rs.getBigDecimal(6);
			m.put("ctcAnnual", ctc);
			m.put("profileCtc", ctc == null ? profileCtc(rs.getString(10)) : null);
			m.put("regime", rs.getString(7) == null ? "NEW" : rs.getString(7));
			m.put("pfEnabled", rs.getObject(8) == null || rs.getBoolean(8));
			m.put("effectiveFrom", rs.getObject(9, LocalDate.class));
			m.put("ptState", rs.getString(11));
			Map<String, String> idn = ids.getOrDefault(id, Map.of());
			m.put("hasPan", idn.containsKey("pan"));
			m.put("hasBank", idn.containsKey("acct"));
			return m;
		}).list();
		return Map.of("items", rows, "canEdit", access.has("payroll", "EDIT"));
	}

	/** Body: {@code ctcAnnual, regime, pfEnabled, effectiveFrom}. */
	@Transactional
	public Map<String, Object> saveSalary(UUID employeeId, Map<String, Object> b) {
		require("EDIT");
		if (jdbc.sql("select count(*) from employee where id = :e and org_id = :org").param("e", employeeId).param("org", org()).query(Long.class).single() == 0) {
			throw new NotFoundException("Employee not found");
		}
		BigDecimal ctc = num(b.get("ctcAnnual"), "ctcAnnual", 1, 1_000_000_000);
		String regime = "OLD".equals(str(b.get("regime"))) ? "OLD" : "NEW";
		boolean pf = !Boolean.FALSE.equals(b.get("pfEnabled"));
		LocalDate eff = b.get("effectiveFrom") == null || str(b.get("effectiveFrom")).isBlank() ? LocalDate.now() : LocalDate.parse(str(b.get("effectiveFrom")));
		BigDecimal before = jdbc.sql("select ctc_annual from salary where employee_id = :e").param("e", employeeId).query(BigDecimal.class).optional().orElse(null);
		jdbc.sql("""
				insert into salary (employee_id, org_id, ctc_annual, regime, pf_enabled, effective_from, updated_by) values (:e, :org, :c, :r, :p, :f, :by)
				on conflict (employee_id) do update set ctc_annual = :c, regime = :r, pf_enabled = :p, effective_from = :f, updated_at = now(), updated_by = :by""")
				.param("e", employeeId).param("org", org()).param("c", ctc).param("r", regime).param("p", pf).param("f", eff).param("by", me()).update();
		audit.publish("payroll", "Salary", employeeId, "UPDATE", before == null ? null : Map.of("ctc", before), Map.of("ctc", ctc, "regime", regime));
		return Map.of("ok", true);
	}

	// ================================================================== runs

	@Transactional(readOnly = true)
	public Map<String, Object> runs() {
		require("VIEW");
		List<Map<String, Object>> rows = jdbc.sql("""
				select r.id, r.period, r.status, r.pay_date, count(p.id), coalesce(sum(p.gross), 0), coalesce(sum(p.net), 0)
				from pay_run r left join payslip p on p.run_id = r.id where r.org_id = :org group by r.id order by r.period desc""").param("org", org())
				.query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("id", rs.getObject(1, UUID.class));
					LocalDate p = rs.getObject(2, LocalDate.class);
					m.put("period", p);
					m.put("label", MONTH.format(p));
					m.put("status", rs.getString(3));
					m.put("payDate", rs.getObject(4, LocalDate.class));
					m.put("employees", rs.getLong(5));
					m.put("gross", rs.getBigDecimal(6));
					m.put("net", rs.getBigDecimal(7));
					return m;
				}).list();
		LocalDate next = LocalDate.now().withDayOfMonth(1);
		return Map.of("items", rows, "suggested", next, "canRun", access.has("payroll", "EDIT"));
	}

	/** Creates the month's run and works out every payslip. */
	@Transactional
	public Map<String, Object> createRun(String month) {
		require("EDIT");
		LocalDate period;
		try {
			period = YearMonth.parse(month).atDay(1);
		}
		catch (RuntimeException e) {
			throw new UnprocessableException(List.of(ErrorItem.field("month", "INVALID", "Pick a month like 2026-11")));
		}
		if (period.isAfter(LocalDate.now().withDayOfMonth(1).plusMonths(1))) {
			throw new UnprocessableException(List.of(ErrorItem.field("month", "FUTURE", "You can run this month or next at the latest")));
		}
		if (jdbc.sql("select count(*) from pay_run where org_id = :org and period = :p").param("org", org()).param("p", period).query(Long.class).single() > 0) {
			throw new ConflictException("There is already a run for " + MONTH.format(period));
		}
		int payDay = jdbc.sql("select pay_day from payroll_settings where org_id = :org").param("org", org()).query(Integer.class).optional().orElse(30);
		LocalDate payDate = period.withDayOfMonth(Math.min(payDay, period.lengthOfMonth()));
		UUID id = jdbc.sql("insert into pay_run (org_id, period, pay_date, created_by) values (:org, :p, :d, :by) returning id").param("org", org()).param("p", period)
				.param("d", payDate).param("by", me()).query(UUID.class).single();
		compute(id, period, null);
		audit.publish("payroll", "PayRun", id, "CREATE", null, Map.of("period", period.toString()));
		return run(id);
	}

	@Transactional
	public Map<String, Object> recalc(UUID runId) {
		require("EDIT");
		Map<String, Object> r = runRow(runId);
		if (!"DRAFT".equals(r.get("status"))) {
			throw new ConflictException("Reopen the run to recalculate it");
		}
		compute(runId, (LocalDate) r.get("period"), null);
		return run(runId);
	}

	/** Draft → Reviewed → Approved → Paid. */
	@Transactional
	public Map<String, Object> advance(UUID runId) {
		Map<String, Object> r = runRow(runId);
		String st = (String) r.get("status");
		LocalDate period = (LocalDate) r.get("period");
		switch (st) {
			case "DRAFT" -> {
				require("EDIT");
				long n = jdbc.sql("select count(*) from payslip where run_id = :r").param("r", runId).query(Long.class).single();
				if (n == 0) {
					throw new ConflictException("There are no payslips in this run. Add salaries first.");
				}
				jdbc.sql("update pay_run set status = 'REVIEWED', reviewed_by = :by, reviewed_at = now() where id = :r").param("by", me()).param("r", runId).update();
			}
			case "REVIEWED" -> {
				require("APPROVE");
				jdbc.sql("update pay_run set status = 'APPROVED', approved_by = :by, approved_at = now() where id = :r").param("by", me()).param("r", runId).update();
			}
			case "APPROVED" -> {
				require("ADMIN");
				jdbc.sql("update pay_run set status = 'PAID', paid_by = :by, paid_at = now() where id = :r").param("by", me()).param("r", runId).update();
				List<UUID> people = jdbc.sql("select employee_id from payslip where run_id = :r").param("r", runId).query(UUID.class).list();
				notifier.inApp(people, "PAYROLL", "Your payslip is ready", "Payslip for " + MONTH.format(period), "/me/pay");
			}
			default -> throw new ConflictException("This run is already disbursed");
		}
		audit.publish("payroll", "PayRun", runId, "ADVANCE", Map.of("status", st), Map.of("period", period.toString()));
		return run(runId);
	}

	/** Back to draft to change something; not once it's paid. */
	@Transactional
	public Map<String, Object> reopen(UUID runId) {
		require("EDIT");
		Map<String, Object> r = runRow(runId);
		if ("PAID".equals(r.get("status"))) {
			throw new ConflictException("A disbursed run can't be reopened");
		}
		jdbc.sql("update pay_run set status = 'DRAFT', reviewed_by = null, reviewed_at = null, approved_by = null, approved_at = null where id = :r").param("r", runId)
				.update();
		return run(runId);
	}

	@Transactional
	public void deleteRun(UUID runId) {
		require("EDIT");
		Map<String, Object> r = runRow(runId);
		if (!"DRAFT".equals(r.get("status"))) {
			throw new ConflictException("Only a draft run can be deleted");
		}
		jdbc.sql("delete from pay_run where id = :r").param("r", runId).update();
		audit.publish("payroll", "PayRun", runId, "DELETE", Map.of("period", r.get("period").toString()), null);
	}

	@Transactional(readOnly = true)
	public Map<String, Object> run(UUID runId) {
		require("VIEW");
		Map<String, Object> r = runRow(runId);
		List<Map<String, Object>> slips = jdbc.sql("""
				select p.id, e.id, e.display_name, e.employee_code, jt.name, d.name, p.gross, p.total_deductions, p.net, p.deductions::text, p.employer::text,
				       p.paid_days, p.days_in_month, p.lop_days + p.extra_lop, p.flags::text, p.pt_state
				from payslip p join employee e on e.id = p.employee_id left join job_title jt on jt.id = e.title_id left join department d on d.id = e.department_id
				where p.run_id = :r order by lower(e.display_name)""").param("r", runId).query((rs, i) -> {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", rs.getObject(1, UUID.class));
			m.put("employee", Map.of("id", rs.getObject(2, UUID.class), "name", rs.getString(3), "code", rs.getString(4), "title", rs.getString(5) == null ? "" : rs.getString(5),
					"department", rs.getString(6) == null ? "" : rs.getString(6)));
			m.put("gross", rs.getBigDecimal(7));
			m.put("deductions", rs.getBigDecimal(8));
			m.put("net", rs.getBigDecimal(9));
			Map<String, BigDecimal> d = byKey(rs.getString(10));
			m.put("pf", d.getOrDefault("pf", BigDecimal.ZERO));
			m.put("esi", d.getOrDefault("esi", BigDecimal.ZERO));
			m.put("pt", d.getOrDefault("pt", BigDecimal.ZERO));
			m.put("tds", d.getOrDefault("tds", BigDecimal.ZERO));
			Map<String, BigDecimal> er = byKey(rs.getString(11));
			m.put("employerPf", er.getOrDefault("pf", BigDecimal.ZERO));
			m.put("employerEsi", er.getOrDefault("esi", BigDecimal.ZERO));
			m.put("paidDays", rs.getBigDecimal(12));
			m.put("daysInMonth", rs.getInt(13));
			m.put("lopDays", rs.getBigDecimal(14));
			m.put("flags", json.readValue(rs.getString(15), new TypeReference<List<String>>() {
			}));
			m.put("ptState", rs.getString(16));
			return m;
		}).list();
		Map<String, BigDecimal> tot = new LinkedHashMap<>();
		for (String k : List.of("gross", "deductions", "net", "pf", "esi", "pt", "tds", "employerPf", "employerEsi")) {
			tot.put(k, slips.stream().map(s -> (BigDecimal) s.get(k)).reduce(BigDecimal.ZERO, BigDecimal::add));
		}
		List<Map<String, Object>> missing = jdbc.sql("""
				select e.id, e.display_name from employee e where e.org_id = :org and e.archived_at is null and e.joining_date <= :end
				  and (e.exit_date is null or e.exit_date >= :start) and not exists (select 1 from salary s where s.employee_id = e.id)
				  and not exists (select 1 from payslip p where p.run_id = :r and p.employee_id = e.id) order by lower(e.display_name)""")
				.param("org", org()).param("start", r.get("period")).param("end", ((LocalDate) r.get("period")).plusMonths(1).minusDays(1)).param("r", runId)
				.query((rs, i) -> Map.<String, Object>of("id", rs.getObject(1, UUID.class), "name", rs.getString(2))).list();
		String st = (String) r.get("status");
		r.put("label", MONTH.format((LocalDate) r.get("period")));
		r.put("step", STEPS.indexOf(st));
		r.put("totals", tot);
		r.put("slips", slips);
		r.put("flagged", slips.stream().filter(s -> !((List<?>) s.get("flags")).isEmpty()).count());
		r.put("noSalary", missing);
		r.put("can", Map.of("edit", "DRAFT".equals(st) && access.has("payroll", "EDIT"), "review", "DRAFT".equals(st) && access.has("payroll", "EDIT"), "approve",
				"REVIEWED".equals(st) && access.has("payroll", "APPROVE"), "disburse", "APPROVED".equals(st) && access.has("payroll", "ADMIN"), "reopen",
				!"PAID".equals(st) && !"DRAFT".equals(st) && access.has("payroll", "EDIT")));
		return r;
	}

	/** Body: {@code adjustments: [{label, amount, kind: EARNING|DEDUCTION}], extraLop}. Draft runs only. */
	@Transactional
	public Map<String, Object> adjust(UUID slipId, Map<String, Object> b) {
		require("EDIT");
		Map<String, Object> s = jdbc.sql("select p.run_id, p.employee_id, r.status, r.period from payslip p join pay_run r on r.id = p.run_id where p.id = :id and r.org_id = :org")
				.param("id", slipId).param("org", org())
				.query((rs, i) -> Map.<String, Object>of("run", rs.getObject(1, UUID.class), "emp", rs.getObject(2, UUID.class), "st", rs.getString(3), "p",
						rs.getObject(4, LocalDate.class)))
				.optional().orElseThrow(() -> new NotFoundException("Payslip not found"));
		if (!"DRAFT".equals(s.get("st"))) {
			throw new ConflictException("Reopen the run to change payslips");
		}
		List<Adjustment> adj = new ArrayList<>();
		if (b.get("adjustments") instanceof List<?> l) {
			for (Object o : l) {
				if (o instanceof Map<?, ?> m) {
					String label = str(m.get("label"));
					if (label == null || label.isBlank()) {
						throw new UnprocessableException(List.of(ErrorItem.field("adjustments", "LABEL", "Name each adjustment")));
					}
					BigDecimal amt = num(m.get("amount"), "adjustments", 1, 10_000_000);
					adj.add(new Adjustment(label.strip(), amt, "DEDUCTION".equals(str(m.get("kind"))) ? "DEDUCTION" : "EARNING"));
				}
			}
		}
		BigDecimal extraLop = b.get("extraLop") == null || str(b.get("extraLop")).isBlank() ? BigDecimal.ZERO : num(b.get("extraLop"), "extraLop", 0, 31);
		jdbc.sql("update payslip set adjustments = cast(:a as jsonb), extra_lop = :x where id = :id").param("a", json.writeValueAsString(adj)).param("x", extraLop)
				.param("id", slipId).update();
		compute((UUID) s.get("run"), (LocalDate) s.get("p"), (UUID) s.get("emp"));
		return slip(slipId);
	}

	@Transactional(readOnly = true)
	public Map<String, Object> slip(UUID slipId) {
		Map<String, Object> m = jdbc.sql("""
				select p.id, p.run_id, r.period, r.status, r.pay_date, e.id, e.display_name, e.employee_code, jt.name, d.name, l.name,
				       p.days_in_month, p.paid_days, p.lop_days, p.extra_lop, p.earnings::text, p.deductions::text, p.employer::text, p.adjustments::text,
				       p.gross, p.total_deductions, p.net, p.pt_state, p.regime, p.flags::text, e.joining_date
				from payslip p join pay_run r on r.id = p.run_id join employee e on e.id = p.employee_id left join job_title jt on jt.id = e.title_id
				left join department d on d.id = e.department_id left join location l on l.id = e.location_id where p.id = :id and r.org_id = :org""")
				.param("id", slipId).param("org", org()).query((rs, i) -> {
					Map<String, Object> x = new LinkedHashMap<>();
					x.put("id", rs.getObject(1, UUID.class));
					x.put("runId", rs.getObject(2, UUID.class));
					x.put("period", rs.getObject(3, LocalDate.class));
					x.put("label", MONTH.format(rs.getObject(3, LocalDate.class)));
					x.put("status", rs.getString(4));
					x.put("payDate", rs.getObject(5, LocalDate.class));
					x.put("employee", Map.of("id", rs.getObject(6, UUID.class), "name", rs.getString(7), "code", rs.getString(8), "title", rs.getString(9) == null ? "" : rs.getString(9),
							"department", rs.getString(10) == null ? "" : rs.getString(10), "location", rs.getString(11) == null ? "" : rs.getString(11)));
					x.put("daysInMonth", rs.getInt(12));
					x.put("paidDays", rs.getBigDecimal(13));
					x.put("lopDays", rs.getBigDecimal(14));
					x.put("extraLop", rs.getBigDecimal(15));
					x.put("earnings", lines(rs.getString(16)));
					x.put("deductions", lines(rs.getString(17)));
					x.put("employer", lines(rs.getString(18)));
					x.put("adjustments", json.readValue(rs.getString(19), new TypeReference<List<Map<String, Object>>>() {
					}));
					x.put("gross", rs.getBigDecimal(20));
					x.put("totalDeductions", rs.getBigDecimal(21));
					x.put("net", rs.getBigDecimal(22));
					x.put("ptState", rs.getString(23));
					x.put("regime", rs.getString(24));
					x.put("flags", json.readValue(rs.getString(25), new TypeReference<List<String>>() {
					}));
					x.put("joiningDate", rs.getObject(26, LocalDate.class));
					return x;
				}).optional().orElseThrow(() -> new NotFoundException("Payslip not found"));
		@SuppressWarnings("unchecked")
		UUID emp = (UUID) ((Map<String, Object>) m.get("employee")).get("id");
		boolean own = emp.equals(currentUser.employeeId());
		if (!access.has("payroll", "VIEW") && !(own && "PAID".equals(m.get("status")))) {
			throw new ForbiddenException("You can't see this payslip");
		}
		Map<String, String> idn = identity().getOrDefault(emp, Map.of());
		m.put("pan", idn.get("pan"));
		m.put("uan", idn.get("uan"));
		String acct = idn.get("acct");
		m.put("bank", acct == null ? null : "•••• " + acct.substring(Math.max(0, acct.length() - 4)));
		return m;
	}

	// ================================================================== statutory

	@Transactional(readOnly = true)
	public Map<String, Object> statutory(UUID runId) {
		Map<String, Object> r = run(runId);
		@SuppressWarnings("unchecked")
		Map<String, BigDecimal> t = (Map<String, BigDecimal>) r.get("totals");
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> slips = (List<Map<String, Object>>) r.get("slips");
		LocalDate p = (LocalDate) r.get("period");
		LocalDate next = p.plusMonths(1);
		Map<String, BigDecimal> ptBy = new LinkedHashMap<>();
		for (Map<String, Object> s : slips) {
			BigDecimal pt = (BigDecimal) s.get("pt");
			if (pt.signum() > 0) {
				ptBy.merge(s.get("ptState") == null ? "—" : (String) s.get("ptState"), pt, BigDecimal::add);
			}
		}
		List<Map<String, Object>> cards = new ArrayList<>();
		cards.add(card("PF", "Provident fund", t.get("pf").add(t.get("employerPf")), List.of(kv("Employee share", t.get("pf")), kv("Employer share", t.get("employerPf")),
				kv("Members", BigDecimal.valueOf(slips.stream().filter(s -> ((BigDecimal) s.get("pf")).signum() > 0).count()))), "Due " + next.withDayOfMonth(15), "pf"));
		cards.add(card("ESI", "Employees' State Insurance", t.get("esi").add(t.get("employerEsi")), List.of(kv("Employee share", t.get("esi")),
				kv("Employer share", t.get("employerEsi")), kv("Covered", BigDecimal.valueOf(slips.stream().filter(s -> ((BigDecimal) s.get("esi")).signum() > 0).count()))),
				"Due " + next.withDayOfMonth(15), "esi"));
		cards.add(card("PT", "Professional tax", t.get("pt"), ptBy.entrySet().stream().map(e -> kv(e.getKey(), e.getValue())).toList(), "Due per state", "pt"));
		cards.add(card("TDS", "Income tax deducted", t.get("tds"), List.of(kv("Employees with TDS", BigDecimal.valueOf(slips.stream().filter(s -> ((BigDecimal) s.get("tds")).signum() > 0).count()))),
				"Due " + next.withDayOfMonth(7), "tds"));
		return Map.of("period", p, "label", r.get("label"), "status", r.get("status"), "cards", cards);
	}

	/** A CSV for a filing: {@code pf}, {@code esi}, {@code pt}, {@code tds} or {@code register} (everything). */
	@Transactional(readOnly = true)
	public FileContent export(UUID runId, String kind) {
		Map<String, Object> r = run(runId);
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> slips = (List<Map<String, Object>>) r.get("slips");
		Map<UUID, Map<String, String>> ids = identity();
		StringBuilder b = new StringBuilder();
		switch (kind) {
			case "pf" -> {
				b.append("UAN,Name,Basic wage,Employee PF,Employer PF\n");
				for (Map<String, Object> s : slips) {
					Map<String, Object> e = emp(s);
					BigDecimal pf = (BigDecimal) s.get("pf");
					if (pf.signum() > 0) {
						b.append(csv(ids.getOrDefault((UUID) e.get("id"), Map.of()).getOrDefault("uan", ""))).append(',').append(csv((String) e.get("name"))).append(',')
								.append(pf.multiply(BigDecimal.valueOf(100)).divide(BigDecimal.valueOf(12), 0, RoundingMode.HALF_UP)).append(',').append(pf).append(',')
								.append(s.get("employerPf")).append('\n');
					}
				}
			}
			case "esi" -> {
				b.append("Name,Code,Paid days,Gross,Employee ESI,Employer ESI\n");
				slips.stream().filter(s -> ((BigDecimal) s.get("esi")).signum() > 0).forEach(s -> b.append(csv((String) emp(s).get("name"))).append(',')
						.append(csv((String) emp(s).get("code"))).append(',').append(s.get("paidDays")).append(',').append(s.get("gross")).append(',').append(s.get("esi"))
						.append(',').append(s.get("employerEsi")).append('\n'));
			}
			case "pt" -> {
				b.append("State,Name,Code,Gross,Professional tax\n");
				slips.stream().filter(s -> ((BigDecimal) s.get("pt")).signum() > 0).forEach(s -> b.append(csv(String.valueOf(s.get("ptState")))).append(',')
						.append(csv((String) emp(s).get("name"))).append(',').append(csv((String) emp(s).get("code"))).append(',').append(s.get("gross")).append(',')
						.append(s.get("pt")).append('\n'));
			}
			case "tds" -> {
				b.append("PAN,Name,Code,Gross,TDS\n");
				slips.stream().filter(s -> ((BigDecimal) s.get("tds")).signum() > 0).forEach(s -> b.append(csv(ids.getOrDefault((UUID) emp(s).get("id"), Map.of())
						.getOrDefault("pan", ""))).append(',').append(csv((String) emp(s).get("name"))).append(',').append(csv((String) emp(s).get("code"))).append(',')
						.append(s.get("gross")).append(',').append(s.get("tds")).append('\n'));
			}
			default -> {
				b.append("Code,Name,Department,Paid days,LOP days,Gross,PF,ESI,PT,TDS,Deductions,Net\n");
				for (Map<String, Object> s : slips) {
					Map<String, Object> e = emp(s);
					b.append(csv((String) e.get("code"))).append(',').append(csv((String) e.get("name"))).append(',').append(csv((String) e.get("department"))).append(',')
							.append(s.get("paidDays")).append(',').append(s.get("lopDays")).append(',').append(s.get("gross")).append(',').append(s.get("pf")).append(',')
							.append(s.get("esi")).append(',').append(s.get("pt")).append(',').append(s.get("tds")).append(',').append(s.get("deductions")).append(',')
							.append(s.get("net")).append('\n');
				}
			}
		}
		String name = (kind.equals("register") ? "payroll-register" : kind + "-return") + "-" + ((LocalDate) r.get("period")).toString().substring(0, 7) + ".csv";
		return new FileContent(("﻿" + b).getBytes(java.nio.charset.StandardCharsets.UTF_8), "text/csv", name);
	}

	/** Each person's pay and tax for a financial year (April–March), from disbursed runs. */
	@Transactional(readOnly = true)
	public Map<String, Object> annual(int fyStart, UUID onlyEmployee) {
		LocalDate from = LocalDate.of(fyStart, 4, 1);
		LocalDate to = LocalDate.of(fyStart + 1, 3, 1);
		Map<UUID, Map<String, Object>> merged = new LinkedHashMap<>();
		jdbc.sql("""
				select e.id, e.display_name, e.employee_code, p.gross, p.net, p.deductions::text
				from payslip p join pay_run r on r.id = p.run_id join employee e on e.id = p.employee_id
				where r.org_id = :org and r.status = 'PAID' and r.period between :a and :b and (cast(:e as uuid) is null or e.id = :e)""").param("org", org())
				.param("a", from).param("b", to).param("e", onlyEmployee, java.sql.Types.OTHER).query((rs, i) -> {
					Map<String, Object> m = merged.computeIfAbsent(rs.getObject(1, UUID.class), k -> {
						Map<String, Object> x = new LinkedHashMap<>();
						x.put("id", k);
						x.put("months", 0L);
						x.put("gross", BigDecimal.ZERO);
						x.put("net", BigDecimal.ZERO);
						x.put("tds", BigDecimal.ZERO);
						x.put("pf", BigDecimal.ZERO);
						x.put("pt", BigDecimal.ZERO);
						return x;
					});
					m.put("name", rs.getString(2));
					m.put("code", rs.getString(3));
					m.put("months", (Long) m.get("months") + 1);
					m.put("gross", ((BigDecimal) m.get("gross")).add(rs.getBigDecimal(4)));
					m.put("net", ((BigDecimal) m.get("net")).add(rs.getBigDecimal(5)));
					Map<String, BigDecimal> d = byKey(rs.getString(6));
					for (String k : List.of("tds", "pf", "pt")) {
						m.put(k, ((BigDecimal) m.get(k)).add(d.getOrDefault(k, BigDecimal.ZERO)));
					}
					return 0;
				}).list();
		Map<UUID, Map<String, String>> ids = identity();
		List<Map<String, Object>> out = new ArrayList<>(merged.values());
		out.forEach(m -> m.put("pan", ids.getOrDefault((UUID) m.get("id"), Map.of()).get("pan")));
		out.sort((a, b) -> ((String) a.get("name")).compareToIgnoreCase((String) b.get("name")));
		return Map.of("fy", fyStart + "–" + String.valueOf(fyStart + 1).substring(2), "items", out);
	}

	@Transactional(readOnly = true)
	public Map<String, Object> annualAll(int fyStart) {
		require("VIEW");
		return annual(fyStart, null);
	}

	// ================================================================== self service

	@Transactional
	public Map<String, Object> mine() {
		UUID me = currentUser.requireEmployeeId();
		List<Map<String, Object>> slips = jdbc.sql("""
				select p.id, r.period, p.net, p.gross from payslip p join pay_run r on r.id = p.run_id
				where p.employee_id = :e and r.status = 'PAID' order by r.period desc limit 36""").param("e", me)
				.query((rs, i) -> Map.<String, Object>of("id", rs.getObject(1, UUID.class), "period", rs.getObject(2, LocalDate.class), "label",
						MONTH.format(rs.getObject(2, LocalDate.class)), "net", rs.getBigDecimal(3), "gross", rs.getBigDecimal(4)))
				.list();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("slips", slips);
		out.put("tax", taxFor(me));
		int fy = LocalDate.now().getMonthValue() >= 4 ? LocalDate.now().getYear() : LocalDate.now().getYear() - 1;
		out.put("statements", List.of(fy, fy - 1).stream().filter(y -> !annual(y, me).get("items").equals(List.of())).map(y -> Map.of("fyStart", y, "label",
				"FY " + y + "–" + String.valueOf(y + 1).substring(2))).toList());
		return out;
	}

	/** Both regimes' estimated yearly tax for the caller, from their salary and declaration. */
	private Map<String, Object> taxFor(UUID emp) {
		Map<String, Object> s = jdbc.sql("select ctc_annual, regime, pf_enabled from salary where employee_id = :e").param("e", emp)
				.query((rs, i) -> Map.<String, Object>of("ctc", rs.getBigDecimal(1), "regime", rs.getString(2), "pf", rs.getBoolean(3))).optional().orElse(null);
		if (s == null) {
			return null;
		}
		int fy = LocalDate.now().getMonthValue() >= 4 ? LocalDate.now().getYear() : LocalDate.now().getYear() - 1;
		Declaration d = declaration(emp, fy);
		Settings st = settings();
		BigDecimal ctc = (BigDecimal) s.get("ctc");
		BigDecimal basic = ctc.divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP).multiply(st.basicPct()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
		BigDecimal pfYear = Boolean.TRUE.equals(s.get("pf")) ? basic.min(st.pfWageCap()).multiply(st.pfRate()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)
				.multiply(BigDecimal.valueOf(12)) : BigDecimal.ZERO;
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("fy", "FY " + fy + "–" + String.valueOf(fy + 1).substring(2));
		m.put("ctcAnnual", ctc);
		m.put("regime", s.get("regime"));
		m.put("newRegime", PayrollEngine.annualTax(ctc, "NEW", Declaration.NONE, pfYear));
		m.put("oldRegime", PayrollEngine.annualTax(ctc, "OLD", d, pfYear));
		m.put("declared", d != Declaration.NONE);
		return m;
	}

	@Transactional(readOnly = true)
	public FileContent payslipPdf(UUID slipId) {
		Map<String, Object> s = slip(slipId);
		return new FileContent(PayslipPdf.render(s, orgName()), "application/pdf", "payslip-" + ((LocalDate) s.get("period")).toString().substring(0, 7) + ".pdf");
	}

	@Transactional(readOnly = true)
	public FileContent statementPdf(int fyStart, UUID employee) {
		UUID me = currentUser.requireEmployeeId();
		UUID who = employee == null ? me : employee;
		if (!who.equals(me)) {
			require("VIEW");
		}
		Map<String, Object> a = annual(fyStart, who);
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> items = (List<Map<String, Object>>) a.get("items");
		if (items.isEmpty()) {
			throw new NotFoundException("No disbursed payslips in that year");
		}
		return new FileContent(PayslipPdf.statement(items.get(0), (String) a.get("fy"), orgName()), "application/pdf", "tax-statement-FY" + fyStart + ".pdf");
	}

	// ================================================================== working it out

	/** (Re)computes the run's payslips, or one person's when {@code only} is set. Keeps adjustments and extra LOP already entered. */
	private void compute(UUID runId, LocalDate period, UUID only) {
		LocalDate end = period.plusMonths(1).minusDays(1);
		int dim = period.lengthOfMonth();
		Settings s = settings();
		record Person(UUID id, LocalDate join, LocalDate exit, BigDecimal ctc, String regime, boolean pf, String state, LocalDate effective) {
		}
		List<Person> people = jdbc.sql("""
				select e.id, e.joining_date, e.exit_date, s.ctc_annual, s.regime, s.pf_enabled, l.state, s.effective_from
				from employee e join salary s on s.employee_id = e.id left join location l on l.id = e.location_id
				where e.org_id = :org and e.archived_at is null and e.joining_date <= :end and (e.exit_date is null or e.exit_date >= :start)
				  and (cast(:only as uuid) is null or e.id = :only)""").param("org", org()).param("start", period).param("end", end)
				.param("only", only, java.sql.Types.OTHER).query((rs, i) -> new Person(rs.getObject(1, UUID.class), rs.getObject(2, LocalDate.class),
						rs.getObject(3, LocalDate.class), rs.getBigDecimal(4), rs.getString(5), rs.getBoolean(6), rs.getString(7), rs.getObject(8, LocalDate.class)))
				.list();
		Map<UUID, BigDecimal> lop = unpaidLeave(period, end);
		Map<UUID, Map<String, Object>> keep = new HashMap<>();
		jdbc.sql("select employee_id, adjustments::text, extra_lop from payslip where run_id = :r").param("r", runId)
				.query((rs, i) -> keep.put(rs.getObject(1, UUID.class), Map.of("adj", rs.getString(2), "x", rs.getBigDecimal(3)))).list();
		Map<UUID, BigDecimal> lastGross = new HashMap<>();
		jdbc.sql("""
				select distinct on (p.employee_id) p.employee_id, p.gross, p.paid_days, p.days_in_month from payslip p join pay_run r on r.id = p.run_id
				where r.org_id = :org and r.period < :p order by p.employee_id, r.period desc""").param("org", org()).param("p", period)
				.query((rs, i) -> rs.getBigDecimal(3).compareTo(BigDecimal.valueOf(rs.getInt(4))) == 0 ? lastGross.put(rs.getObject(1, UUID.class), rs.getBigDecimal(2)) : null)
				.list();
		Map<UUID, Map<String, String>> ids = identity();
		int fy = period.getMonthValue() >= 4 ? period.getYear() : period.getYear() - 1;
		if (only == null) {
			jdbc.sql("delete from payslip where run_id = :r and employee_id not in (select employee_id from salary)").param("r", runId).update();
		}
		for (Person p : people) {
			LocalDate a = p.join().isAfter(period) ? p.join() : period;
			LocalDate b = p.exit() != null && p.exit().isBefore(end) ? p.exit() : end;
			BigDecimal employed = BigDecimal.valueOf(b.toEpochDay() - a.toEpochDay() + 1);
			Map<String, Object> k = keep.get(p.id());
			BigDecimal extra = k == null ? BigDecimal.ZERO : (BigDecimal) k.get("x");
			BigDecimal leaveLop = lop.getOrDefault(p.id(), BigDecimal.ZERO);
			BigDecimal paid = employed.subtract(leaveLop).subtract(extra).max(BigDecimal.ZERO);
			List<Adjustment> adj = k == null ? List.of() : json.readValue((String) k.get("adj"), new TypeReference<List<Adjustment>>() {
			});
			Declaration d = "OLD".equals(p.regime()) ? declaration(p.id(), fy) : Declaration.NONE;
			Result r = PayrollEngine.month(p.ctc(), paid, dim, p.pf(), p.regime(), p.state(), period.getMonth(), s, adj, d);
			List<String> flags = new ArrayList<>();
			if (!p.join().isBefore(period)) {
				flags.add("New joiner · joined " + p.join());
			}
			if (p.exit() != null && !p.exit().isAfter(end)) {
				flags.add("Leaving · last day " + p.exit());
			}
			if (leaveLop.signum() > 0) {
				flags.add(leaveLop.stripTrailingZeros().toPlainString() + " day(s) unpaid leave");
			}
			if (extra.signum() > 0) {
				flags.add(extra.stripTrailingZeros().toPlainString() + " day(s) loss of pay added");
			}
			if (!adj.isEmpty()) {
				flags.add(adj.size() + " adjustment(s)");
			}
			BigDecimal prev = lastGross.get(p.id());
			if (prev != null && paid.compareTo(BigDecimal.valueOf(dim)) == 0 && adj.isEmpty() && prev.compareTo(r.gross()) != 0) {
				flags.add("Gross changed from ₹" + prev.toPlainString());
			}
			Map<String, String> idn = ids.getOrDefault(p.id(), Map.of());
			if (!idn.containsKey("acct")) {
				flags.add("No bank account");
			}
			if (!idn.containsKey("pan")) {
				flags.add("No PAN");
			}
			jdbc.sql("""
					insert into payslip (run_id, employee_id, days_in_month, paid_days, lop_days, extra_lop, earnings, deductions, employer, adjustments, gross,
					  total_deductions, net, pt_state, regime, flags)
					values (:r, :e, :dim, :paid, :lop, :x, cast(:ea as jsonb), cast(:de as jsonb), cast(:er as jsonb), cast(:adj as jsonb), :g, :td, :n, :st, :rg, cast(:f as jsonb))
					on conflict (run_id, employee_id) do update set days_in_month = :dim, paid_days = :paid, lop_days = :lop, extra_lop = :x, earnings = cast(:ea as jsonb),
					  deductions = cast(:de as jsonb), employer = cast(:er as jsonb), adjustments = cast(:adj as jsonb), gross = :g, total_deductions = :td, net = :n,
					  pt_state = :st, regime = :rg, flags = cast(:f as jsonb)""")
					.param("r", runId).param("e", p.id()).param("dim", dim).param("paid", paid).param("lop", leaveLop).param("x", extra)
					.param("ea", json.writeValueAsString(r.earnings())).param("de", json.writeValueAsString(r.deductions())).param("er", json.writeValueAsString(r.employer()))
					.param("adj", json.writeValueAsString(adj)).param("g", r.gross()).param("td", r.totalDeductions()).param("n", r.net())
					.param("st", p.state(), java.sql.Types.VARCHAR).param("rg", p.regime()).param("f", json.writeValueAsString(flags)).update();
		}
	}

	/** Approved unpaid leave in the month, in working days (Monday to Friday), half days as 0.5. */
	private Map<UUID, BigDecimal> unpaidLeave(LocalDate start, LocalDate end) {
		Map<UUID, BigDecimal> out = new HashMap<>();
		jdbc.sql("""
				select r.employee_id, r.from_date, r.to_date, r.half_day from leave_request r join leave_type t on t.id = r.type_id
				where r.org_id = :org and r.status = 'APPROVED' and not t.paid and r.from_date <= :end and r.to_date >= :start""").param("org", org())
				.param("start", start).param("end", end).query((rs, i) -> {
					LocalDate a = rs.getObject(2, LocalDate.class).isBefore(start) ? start : rs.getObject(2, LocalDate.class);
					LocalDate b = rs.getObject(3, LocalDate.class).isAfter(end) ? end : rs.getObject(3, LocalDate.class);
					double days = 0;
					for (LocalDate d = a; !d.isAfter(b); d = d.plusDays(1)) {
						if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) {
							days++;
						}
					}
					if (rs.getBoolean(4)) {
						days = Math.min(days, 0.5);
					}
					out.merge(rs.getObject(1, UUID.class), BigDecimal.valueOf(days), BigDecimal::add);
					return 0;
				}).list();
		return out;
	}

	private Declaration declaration(UUID emp, int fy) {
		String key = fy + "-" + String.valueOf(fy + 1).substring(2);
		return jdbc.sql("select sec80c, sec80d, rent_monthly, nps, home_loan from tax_declaration where employee_id = :e and fy = :fy and status in ('SUBMITTED', 'VERIFIED')")
				.param("e", emp).param("fy", key)
				.query((rs, i) -> new Declaration(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getBigDecimal(5))).optional()
				.orElse(Declaration.NONE);
	}

	/** PAN, bank account and UAN from each person's profile fields. */
	private Map<UUID, Map<String, String>> identity() {
		Map<UUID, Map<String, String>> out = new HashMap<>();
		jdbc.sql("""
				select v.record_id, f.key, v.value #>> '{}' from config_field_value v join config_field f on f.id = v.field_id
				where v.org_id = :org and v.area_key = 'employee_profile' and f.key in ('pan', 'acct', 'uan')""").param("org", org()).query((rs, i) -> {
			String val = rs.getString(3);
			if (val != null && !val.isBlank()) {
				out.computeIfAbsent(UUID.fromString(rs.getString(1)), k -> new HashMap<>()).put(rs.getString(2), val);
			}
			return 0;
		}).list();
		return out;
	}

	private Map<String, Object> runRow(UUID runId) {
		return jdbc.sql("select id, period, status, pay_date, paid_at from pay_run where id = :id and org_id = :org").param("id", runId).param("org", org())
				.query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("id", rs.getObject(1, UUID.class));
					m.put("period", rs.getObject(2, LocalDate.class));
					m.put("status", rs.getString(3));
					m.put("payDate", rs.getObject(4, LocalDate.class));
					m.put("paidAt", rs.getObject(5, OffsetDateTime.class));
					return m;
				}).optional().orElseThrow(() -> new NotFoundException("Pay run not found"));
	}

	private List<Line> lines(String js) {
		return json.readValue(js, new TypeReference<List<Line>>() {
		});
	}

	private Map<String, BigDecimal> byKey(String js) {
		Map<String, BigDecimal> m = new HashMap<>();
		for (Line l : lines(js)) {
			if (!"adj".equals(l.key())) {
				m.merge(l.key(), l.amount(), BigDecimal::add);
			}
		}
		return m;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> emp(Map<String, Object> slip) {
		return (Map<String, Object>) slip.get("employee");
	}

	private static Map<String, Object> card(String code, String title, BigDecimal amount, List<Map<String, Object>> lines, String due, String file) {
		return Map.of("code", code, "title", title, "amount", amount, "lines", lines, "due", due, "file", file);
	}

	private static Map<String, Object> kv(String k, BigDecimal v) {
		return Map.of("k", k, "v", v);
	}

	private static String csv(String s) {
		String v = s == null ? "" : s;
		if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0) {
			v = "'" + v;
		}
		return "\"" + v.replace("\"", "\"\"") + "\"";
	}

	/** Profile CTC is entered in lakh; small numbers are lakh, large ones already rupees. */
	static BigDecimal profileCtc(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			BigDecimal v = new BigDecimal(raw.trim());
			return v.compareTo(BigDecimal.valueOf(1000)) < 0 ? v.multiply(BigDecimal.valueOf(100_000)) : v;
		}
		catch (NumberFormatException e) {
			return null;
		}
	}

	private String orgName() {
		return jdbc.sql("select name from org where id = :org").param("org", org()).query(String.class).optional().orElse("");
	}

	private void require(String level) {
		if (!access.has("payroll", level)) {
			throw new ForbiddenException("Your role can't " + switch (level) {
				case "VIEW" -> "see payroll";
				case "EDIT" -> "run payroll";
				case "APPROVE" -> "approve payroll";
				default -> "disburse payroll";
			});
		}
	}

	private static BigDecimal num(Object v, String field, long min, long max) {
		try {
			BigDecimal x = new BigDecimal(String.valueOf(v).trim());
			if (x.compareTo(BigDecimal.valueOf(min)) < 0 || x.compareTo(BigDecimal.valueOf(max)) > 0) {
				throw new NumberFormatException();
			}
			return x;
		}
		catch (RuntimeException e) {
			throw new UnprocessableException(List.of(ErrorItem.field(field, "INVALID", "Enter a number between " + min + " and " + max)));
		}
	}

	private static String str(Object o) {
		return o == null ? null : o.toString();
	}

	private UUID me() {
		return currentUser.employeeId();
	}

	private UUID org() {
		return currentUser.orgId();
	}
}
