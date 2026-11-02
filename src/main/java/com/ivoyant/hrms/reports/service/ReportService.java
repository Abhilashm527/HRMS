package com.ivoyant.hrms.reports.service;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.security.ModuleAccess;
import com.ivoyant.hrms.common.web.ForbiddenException;
import com.ivoyant.hrms.common.web.NotFoundException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ready-made reports. Each one needs View on its module (people reports need Employees, leave reports need Leave and so on), takes an
 * optional date range, and returns columns and rows the page shows as a table and downloads as CSV.
 */
@Service
public class ReportService {

	/** A column: {@code type} is text, number, date, money (₹) or pct. */
	public record Col(String key, String label, String type) {
	}

	private record Def(String key, String name, String category, String module, String description, boolean period, List<Col> cols, String sql) {
	}

	private static Col t(String k, String l) {
		return new Col(k, l, "text");
	}

	private static Col n(String k, String l) {
		return new Col(k, l, "number");
	}

	private static Col d(String k, String l) {
		return new Col(k, l, "date");
	}

	private static Col m(String k, String l) {
		return new Col(k, l, "money");
	}

	private static Col p(String k, String l) {
		return new Col(k, l, "pct");
	}

	private static final String LIVE = " e.org_id = :org and e.archived_at is null ";

	private static final List<Def> DEFS = List.of(
			// ------------------------------------------------------------ people
			new Def("headcount-department", "Headcount by department", "People", "employee", "Active people and people on notice in each department.", false,
					List.of(t("department", "Department"), n("active", "Active"), n("notice", "On notice"), n("total", "Total"), p("share", "Share")), """
							select coalesce(d.name, 'No department'), count(*) filter (where e.status = 'ACTIVE'), count(*) filter (where e.status = 'ON_NOTICE'), count(*),
							       round(100.0 * count(*) / nullif(sum(count(*)) over (), 0), 1)
							from employee e left join department d on d.id = e.department_id
							where """ + LIVE + " and e.status <> 'EXITED' group by d.name order by count(*) desc"),
			new Def("headcount-location", "Headcount by location", "People", "employee", "Where people work.", false,
					List.of(t("location", "Location"), n("active", "Active"), n("notice", "On notice"), n("total", "Total"), p("share", "Share")), """
							select coalesce(l.name, 'No location'), count(*) filter (where e.status = 'ACTIVE'), count(*) filter (where e.status = 'ON_NOTICE'), count(*),
							       round(100.0 * count(*) / nullif(sum(count(*)) over (), 0), 1)
							from employee e left join location l on l.id = e.location_id
							where """ + LIVE + " and e.status <> 'EXITED' group by l.name order by count(*) desc"),
			new Def("headcount-type", "Headcount by employment type", "People", "employee", "Full-time, contract, interns and so on.", false,
					List.of(t("type", "Employment type"), n("total", "People"), p("share", "Share")), """
							select coalesce(t.name, e.employment_type), count(*), round(100.0 * count(*) / nullif(sum(count(*)) over (), 0), 1)
							from employee e left join employment_type t on t.id = e.employment_type_id
							where """ + LIVE + " and e.status <> 'EXITED' group by coalesce(t.name, e.employment_type) order by count(*) desc"),
			new Def("directory", "Employee master", "People", "employee", "Everyone on the books with their job details.", false,
					List.of(t("code", "Code"), t("name", "Name"), t("email", "Work email"), t("title", "Title"), t("department", "Department"), t("location", "Location"),
							t("type", "Type"), t("manager", "Manager"), d("joined", "Joined"), t("status", "Status")),
					"""
							select e.employee_code, e.display_name, e.work_email, jt.name, d.name, l.name, coalesce(t.name, e.employment_type), m.display_name, e.joining_date,
							       initcap(replace(e.status, '_', ' '))
							from employee e left join job_title jt on jt.id = e.title_id left join department d on d.id = e.department_id left join location l on l.id = e.location_id
							left join employment_type t on t.id = e.employment_type_id left join employee m on m.id = e.manager_id
							where """ + LIVE + " and e.status <> 'EXITED' order by e.display_name"),
			new Def("joiners", "Joiners", "People", "employee", "People who joined in the period.", true,
					List.of(t("code", "Code"), t("name", "Name"), t("title", "Title"), t("department", "Department"), t("manager", "Manager"), d("joined", "Joined")), """
							select e.employee_code, e.display_name, jt.name, d.name, m.display_name, e.joining_date
							from employee e left join job_title jt on jt.id = e.title_id left join department d on d.id = e.department_id left join employee m on m.id = e.manager_id
							where """ + LIVE + " and e.joining_date between :from and :to order by e.joining_date desc"),
			new Def("exits", "Exits", "People", "employee", "People whose last day fell in the period, with how long they stayed.", true,
					List.of(t("code", "Code"), t("name", "Name"), t("title", "Title"), t("department", "Department"), d("joined", "Joined"), d("left", "Last day"),
							n("tenure", "Tenure (years)")),
					"""
							select e.employee_code, e.display_name, jt.name, d.name, e.joining_date, e.exit_date, round((e.exit_date - e.joining_date) / 365.25, 1)
							from employee e left join job_title jt on jt.id = e.title_id left join department d on d.id = e.department_id
							where e.org_id = :org and e.exit_date between :from and :to order by e.exit_date desc"""),
			new Def("attrition", "Attrition by month", "People", "employee", "Exits against headcount, month by month over the period.", true,
					List.of(t("month", "Month"), n("start", "Headcount at start"), n("joined", "Joined"), n("left", "Left"), p("rate", "Attrition")), """
							select to_char(mo, 'Mon YYYY'),
							       (select count(*) from employee e where e.org_id = :org and e.joining_date < mo and (e.exit_date is null or e.exit_date >= mo)),
							       (select count(*) from employee e where e.org_id = :org and e.joining_date >= mo and e.joining_date < mo + interval '1 month'),
							       (select count(*) from employee e where e.org_id = :org and e.exit_date >= mo and e.exit_date < mo + interval '1 month'),
							       round(100.0 * (select count(*) from employee e where e.org_id = :org and e.exit_date >= mo and e.exit_date < mo + interval '1 month')
							         / nullif((select count(*) from employee e where e.org_id = :org and e.joining_date < mo and (e.exit_date is null or e.exit_date >= mo)), 0), 2)
							from generate_series(date_trunc('month', cast(:from as date)), date_trunc('month', cast(:to as date)), interval '1 month') mo
							order by mo"""),
			new Def("probation", "Probation ending", "People", "employee", "Unconfirmed people whose probation ends in the period.", true,
					List.of(t("code", "Code"), t("name", "Name"), t("department", "Department"), t("manager", "Manager"), d("joined", "Joined"), d("ends", "Probation ends")),
					"""
							select e.employee_code, e.display_name, d.name, m.display_name, e.joining_date, e.probation_end_date
							from employee e left join department d on d.id = e.department_id left join employee m on m.id = e.manager_id
							where """ + LIVE + "and e.status = 'ACTIVE' and e.confirmed_on is null and e.probation_end_date between :from and :to order by e.probation_end_date"),
			// ------------------------------------------------------------ time
			new Def("leave-usage", "Leave taken", "Time", "leave", "Approved leave days by person and type in the period.", true,
					List.of(t("code", "Code"), t("name", "Name"), t("department", "Department"), t("type", "Leave type"), n("requests", "Requests"), n("days", "Days")), """
							select e.employee_code, e.display_name, d.name, lt.name, count(*), sum(r.days)
							from leave_request r join employee e on e.id = r.employee_id join leave_type lt on lt.id = r.type_id left join department d on d.id = e.department_id
							where r.org_id = :org and r.status = 'APPROVED' and r.from_date <= :to and r.to_date >= :from
							group by e.employee_code, e.display_name, d.name, lt.name order by e.display_name, lt.name"""),
			new Def("leave-pending", "Leave waiting for approval", "Time", "leave", "Open leave requests and how long they've waited.", false,
					List.of(t("no", "Request"), t("name", "Employee"), t("type", "Type"), d("from", "From"), d("to", "To"), n("days", "Days"), t("approver", "Manager"),
							n("waiting", "Days waiting")),
					"""
							select 'LV-' || r.no, e.display_name, lt.name, r.from_date, r.to_date, r.days, m.display_name, current_date - cast(r.applied_at as date)
							from leave_request r join employee e on e.id = r.employee_id join leave_type lt on lt.id = r.type_id left join employee m on m.id = e.manager_id
							where r.org_id = :org and r.status = 'PENDING' order by r.applied_at"""),
			new Def("attendance", "Attendance summary", "Time", "attendance", "Days clocked in, work-from-home days and average hours per person.", true,
					List.of(t("code", "Code"), t("name", "Name"), t("department", "Department"), n("days", "Days present"), n("wfh", "WFH days"), n("hours", "Avg hours"),
							n("missing", "Missing clock-outs")),
					"""
							select e.employee_code, e.display_name, d.name, count(a.id), count(a.id) filter (where a.wfh),
							       round(avg(extract(epoch from (a.out_at - a.in_at)) / 3600.0) filter (where a.out_at is not null), 1),
							       count(a.id) filter (where a.in_at is not null and a.out_at is null and a.day < current_date)
							from employee e left join department d on d.id = e.department_id
							left join attendance_record a on a.employee_id = e.id and a.day between :from and :to
							where """ + LIVE + " and e.status <> 'EXITED' group by e.id, d.name order by e.display_name"),
			// ------------------------------------------------------------ pay
			new Def("payroll-runs", "Payroll by month", "Pay", "payroll", "Each pay run's totals.", true,
					List.of(t("month", "Month"), t("status", "Status"), n("people", "People"), m("gross", "Gross"), m("deductions", "Deductions"), m("net", "Net pay"),
							d("paid", "Pay date")),
					"""
							select to_char(r.period, 'Mon YYYY'), initcap(r.status), count(s.id), coalesce(sum(s.gross), 0), coalesce(sum(s.total_deductions), 0),
							       coalesce(sum(s.net), 0), r.pay_date
							from pay_run r left join payslip s on s.run_id = r.id
							where r.org_id = :org and r.period between date_trunc('month', cast(:from as date)) and :to group by r.id order by r.period desc"""),
			new Def("payroll-department", "Payroll cost by department", "Pay", "payroll", "Gross pay and company contributions by department for runs in the period.", true,
					List.of(t("department", "Department"), n("people", "People"), m("gross", "Gross"), m("net", "Net pay"), m("employer", "Company contributions"),
							m("cost", "Total cost")),
					"""
							select coalesce(d.name, 'No department'), count(distinct s.employee_id), sum(s.gross), sum(s.net),
							       sum((select coalesce(sum((x ->> 'amount')::numeric), 0) from jsonb_array_elements(s.employer) x)),
							       sum(s.gross) + sum((select coalesce(sum((x ->> 'amount')::numeric), 0) from jsonb_array_elements(s.employer) x))
							from payslip s join pay_run r on r.id = s.run_id join employee e on e.id = s.employee_id left join department d on d.id = e.department_id
							where r.org_id = :org and r.period between date_trunc('month', cast(:from as date)) and :to group by d.name order by sum(s.gross) desc"""),
			// ------------------------------------------------------------ talent
			new Def("recruiting", "Hiring pipeline by job", "Talent", "recruiting", "Candidates at each stage for every job.", false,
					List.of(t("job", "Job"), t("status", "Status"), n("openings", "Openings"), n("applied", "Applied"), n("screening", "Screening"), n("interview", "Interview"),
							n("offer", "Offer"), n("hired", "Hired"), n("rejected", "Rejected"), n("age", "Days open")),
					"""
							select j.title, initcap(replace(j.status, '_', ' ')), j.openings, count(c.id) filter (where c.stage = 'APPLIED'), count(c.id) filter (where c.stage = 'SCREENING'),
							       count(c.id) filter (where c.stage = 'INTERVIEW'), count(c.id) filter (where c.stage = 'OFFER'), count(c.id) filter (where c.stage = 'HIRED'),
							       count(c.id) filter (where c.stage = 'REJECTED'), current_date - cast(j.created_at as date)
							from job_opening j left join candidate c on c.job_id = j.id where j.org_id = :org group by j.id order by (j.status = 'OPEN') desc, j.created_at desc"""),
			new Def("ratings", "Performance ratings", "Talent", "performance", "Ratings from each review cycle.", false,
					List.of(t("cycle", "Cycle"), t("code", "Code"), t("name", "Name"), t("department", "Department"), t("reviewer", "Reviewer"), n("self", "Self"),
							n("manager", "Manager"), n("final", "Final")),
					"""
							select c.name, e.employee_code, e.display_name, d.name, rv.display_name, r.self_rating, r.manager_rating, r.final_rating
							from review r join review_cycle c on c.id = r.cycle_id join employee e on e.id = r.employee_id left join department d on d.id = e.department_id
							left join employee rv on rv.id = r.reviewer_id where c.org_id = :org order by c.period_end desc, e.display_name"""),
			// ------------------------------------------------------------ assets
			new Def("assets", "Hardware register", "Assets", "assets", "Every device, its status and who has it.", false,
					List.of(t("tag", "Tag"), t("type", "Type"), t("model", "Model"), t("serial", "Serial"), t("status", "Status"), t("holder", "Assigned to"),
							d("warranty", "Warranty until"), m("cost", "Cost")),
					"""
							select a.tag, ty.name, a.model, a.serial, st.name, e.display_name, a.warranty_until, a.purchase_cost
							from asset a join asset_type ty on ty.id = a.type_id join asset_status st on st.id = a.status_id
							left join asset_assignment x on x.asset_id = a.id and x.returned_on is null left join employee e on e.id = x.employee_id
							where a.org_id = :org and a.retired_on is null order by a.tag"""));

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final ModuleAccess access;
	private final Audit audit;

	ReportService(JdbcClient jdbc, CurrentUser currentUser, ModuleAccess access, Audit audit) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.access = access;
		this.audit = audit;
	}

	/** The reports this person may run, plus headline numbers for the modules they can see. */
	@Transactional(readOnly = true)
	public Map<String, Object> catalog() {
		List<Map<String, Object>> list = new ArrayList<>();
		for (Def x : DEFS) {
			if (access.has(x.module(), "VIEW")) {
				list.add(Map.of("key", x.key(), "name", x.name(), "category", x.category(), "description", x.description(), "period", x.period()));
			}
		}
		Map<String, Object> kpis = new LinkedHashMap<>();
		if (access.has("employee", "VIEW")) {
			kpis.put("headcount", one("select count(*) from employee e where " + LIVE + " and e.status <> 'EXITED'"));
			kpis.put("joined30", one("select count(*) from employee e where " + LIVE + " and e.joining_date > current_date - 30 and e.joining_date <= current_date"));
			kpis.put("left30", one("select count(*) from employee e where e.org_id = :org and e.exit_date > current_date - 30 and e.exit_date <= current_date"));
			long exits12 = one("select count(*) from employee e where e.org_id = :org and e.exit_date > current_date - 365 and e.exit_date <= current_date");
			long avg = one("""
					select coalesce(round(avg((select count(*) from employee e where e.org_id = :org and e.joining_date <= m and (e.exit_date is null or e.exit_date > m)))), 0)
					from generate_series(current_date - 365, current_date, interval '1 month') m""");
			kpis.put("attrition12", avg == 0 ? 0 : Math.round(1000.0 * exits12 / avg) / 10.0);
		}
		if (access.has("leave", "VIEW")) {
			kpis.put("onLeaveToday", one("select count(distinct employee_id) from leave_request where org_id = :org and status = 'APPROVED' and current_date between from_date and to_date"));
		}
		if (access.has("recruiting", "VIEW")) {
			kpis.put("openJobs", one("select count(*) from job_opening where org_id = :org and status = 'OPEN'"));
		}
		return Map.of("reports", list, "kpis", kpis);
	}

	/** Runs one report. Without dates, the period is the last 90 days (12 months for attrition and payroll; the next 30 days for probation). */
	@Transactional(readOnly = true)
	public Map<String, Object> run(String key, LocalDate from, LocalDate to) {
		Def x = DEFS.stream().filter(r -> r.key().equals(key)).findFirst().orElseThrow(() -> new NotFoundException("No such report"));
		if (!access.has(x.module(), "VIEW")) {
			throw new ForbiddenException("Your role can't see this report");
		}
		LocalDate today = LocalDate.now();
		if (to == null) {
			to = "probation".equals(key) ? today.plusDays(30) : today;
		}
		if (from == null) {
			from = switch (key) {
				case "probation" -> today;
				case "attrition", "payroll-runs", "payroll-department" -> to.minusMonths(11).withDayOfMonth(1);
				default -> to.minusDays(90);
			};
		}
		if (from.isAfter(to)) {
			LocalDate s = from;
			from = to;
			to = s;
		}
		if (ChronoUnit.DAYS.between(from, to) > 3660) {
			from = to.minusYears(10);
		}
		int width = x.cols().size();
		List<List<Object>> rows = jdbc.sql(x.sql()).param("org", currentUser.orgId()).param("from", from).param("to", to).query((rs, i) -> row(rs, width)).list();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("key", x.key());
		out.put("name", x.name());
		out.put("description", x.description());
		out.put("category", x.category());
		out.put("period", x.period());
		out.put("from", from);
		out.put("to", to);
		out.put("columns", x.cols());
		out.put("rows", rows);
		return out;
	}

	/** Same rows as CSV; downloads are recorded in the audit log because they take data out of the system. */
	@Transactional
	public String csv(String key, LocalDate from, LocalDate to) {
		Map<String, Object> r = run(key, from, to);
		@SuppressWarnings("unchecked")
		List<Col> cols = (List<Col>) r.get("columns");
		@SuppressWarnings("unchecked")
		List<List<Object>> rows = (List<List<Object>>) r.get("rows");
		StringBuilder b = new StringBuilder("﻿");
		b.append(String.join(",", cols.stream().map(c -> cell(c.label())).toList())).append("\r\n");
		for (List<Object> row : rows) {
			b.append(String.join(",", row.stream().map(v -> cell(v == null ? "" : v instanceof BigDecimal bd ? bd.toPlainString() : v.toString())).toList())).append("\r\n");
		}
		audit.publish("reports", "Report", null, "EXPORT", null, Map.of("report", key, "from", String.valueOf(r.get("from")), "to", String.valueOf(r.get("to")), "rows", rows.size()));
		return b.toString();
	}

	/** Quotes when needed and defuses spreadsheet formulas. */
	private static String cell(String s) {
		String v = !s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0 && !s.matches("-?\\d+(\\.\\d+)?") ? "'" + s : s;
		return v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r") ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
	}

	private static List<Object> row(ResultSet rs, int width) throws SQLException {
		List<Object> r = new ArrayList<>(width);
		for (int i = 1; i <= width; i++) {
			Object o = rs.getObject(i);
			if (o instanceof java.sql.Date dt) {
				o = dt.toLocalDate();
			}
			else if (o instanceof Long || o instanceof Integer || o instanceof Short) {
				o = ((Number) o).longValue();
			}
			r.add(o);
		}
		return r;
	}

	private long one(String sql) {
		Number n = jdbc.sql(sql).param("org", currentUser.orgId()).query(Number.class).single();
		return n == null ? 0 : n.longValue();
	}

}
