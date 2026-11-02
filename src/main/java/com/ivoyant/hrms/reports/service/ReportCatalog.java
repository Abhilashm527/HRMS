package com.ivoyant.hrms.reports.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every report in the library: what it shows, which module's access it needs, what period it takes and the SQL that produces it.
 * The SQL can use {@code :org, :from, :to, :asof, :dept, :loc, :scope, :cycle, :label}; employee rows are aliased {@code e}.
 */
final class ReportCatalog {

	private ReportCatalog() {
	}

	/** {@code type}: text, number, date, money, pct, secret (masked to the last 4 characters) or pay (money that is masked). */
	record Col(String label, String type) {
		boolean numeric() {
			return type.equals("number") || type.equals("money") || type.equals("pct") || type.equals("pay");
		}

		boolean masked() {
			return type.equals("secret") || type.equals("pay");
		}
	}

	/**
	 * {@code kind}: month, quarter, fy, range, asof or cycle. {@code admin}: needs Admin on the module (statutory files). {@code org}:
	 * takes department and location filters. {@code scope}: takes the active / exited filter.
	 */
	record Def(String key, String cat, String name, String desc, String freq, List<String> fmts, boolean sens, String kind, String module, boolean admin,
			boolean org, boolean scope, List<Col> cols, String sql) {
	}

	static final List<String[]> CATS = List.of(new String[] {"people", "People"}, new String[] {"time", "Time & attendance"}, new String[] {"pay", "Payroll"},
			new String[] {"stat", "Statutory"}, new String[] {"talent", "Talent"}, new String[] {"assets", "Assets"}, new String[] {"access", "Compliance & access"});

	// ------------------------------------------------------------------ SQL helpers

	private static final String LIVE = " e.org_id = :org and e.archived_at is null ";
	private static final String ORG = " and (cast(:dept as uuid) is null or e.department_id = cast(:dept as uuid)) and (cast(:loc as uuid) is null or e.location_id = cast(:loc as uuid)) ";
	private static final String SCOPE = " and (case cast(:scope as text) when 'all' then true when 'exited' then e.status = 'EXITED' else e.status <> 'EXITED' end) ";
	private static final String ACTIVE = " and e.status <> 'EXITED' ";
	private static final String JOINS = " left join department d on d.id = e.department_id left join location l on l.id = e.location_id left join employee m on m.id = e.manager_id left join job_title jt on jt.id = e.title_id ";
	private static final String RUNJ = " join pay_run r on r.id = s.run_id join employee e on e.id = s.employee_id left join department d on d.id = e.department_id ";
	private static final String RUNW = " where r.org_id = :org and r.period = date_trunc('month', cast(:from as date)) ";
	private static final String RUN = RUNJ + RUNW;

	private static String pf(String key) {
		return "(select nullif(v.value #>> '{}', '') from config_field_value v join config_field f on f.id = v.field_id where v.area_key = 'employee_profile' and v.record_id = e.id and f.key = '"
				+ key + "')";
	}

	private static String dob() {
		return "(case when " + pf("dob") + " ~ '^\\d{4}-\\d{2}-\\d{2}' then cast(left(" + pf("dob") + ", 10) as date) end)";
	}

	private static String line(String arr, String key) {
		return "(select coalesce(sum((x ->> 'amount')::numeric), 0) from jsonb_array_elements(s." + arr + ") x where x ->> 'key' = '" + key + "')";
	}

	private static String all(String arr) {
		return "(select coalesce(sum((x ->> 'amount')::numeric), 0) from jsonb_array_elements(s." + arr + ") x)";
	}

	private static Col t(String l) {
		return new Col(l, "text");
	}

	private static Col n(String l) {
		return new Col(l, "number");
	}

	private static Col d(String l) {
		return new Col(l, "date");
	}

	private static Col m(String l) {
		return new Col(l, "money");
	}

	private static Col p(String l) {
		return new Col(l, "pct");
	}

	private static Col s(String l) {
		return new Col(l, "secret");
	}

	private static Col pay(String l) {
		return new Col(l, "pay");
	}

	private static List<String> f(String s) {
		return List.of(s.split(","));
	}

	// ------------------------------------------------------------------ the library

	static final List<Def> DEFS = List.of(
			// ====================================================== people
			new Def("hc", "people", "Headcount & attrition", "Opening headcount, joiners, exits, closing headcount and attrition for the month by department", "Monthly",
					f("XLSX,PDF,CSV"), false, "month", "employee", false, true, false,
					List.of(t("Month"), t("Department"), n("Opening"), n("Joiners"), n("Exits"), n("Closing"), p("Attrition %")),
					"select cast(:label as text), coalesce(d.name, 'No department'),"
							+ " count(*) filter (where e.joining_date < :from and (e.exit_date is null or e.exit_date >= :from)),"
							+ " count(*) filter (where e.joining_date between :from and :to), count(*) filter (where e.exit_date between :from and :to),"
							+ " count(*) filter (where e.joining_date <= :to and (e.exit_date is null or e.exit_date > :to)),"
							+ " round(100.0 * count(*) filter (where e.exit_date between :from and :to) / nullif(count(*) filter (where e.joining_date < :from and (e.exit_date is null or e.exit_date >= :from)), 0), 2)"
							+ " from employee e" + JOINS + " where e.org_id = :org" + ORG + " group by d.name order by d.name nulls last"),
			new Def("master", "people", "Employee master", "Every employee with job, contact, manager and statutory IDs", "On demand", f("XLSX,CSV"), true, "asof", "employee",
					false, true, true,
					List.of(t("Employee ID"), t("Name"), t("Title"), t("Department"), t("Manager"), t("Location"), d("Date of joining"), t("Status"), t("Work email"),
							t("Phone"), s("PAN"), s("UAN")),
					"select e.employee_code, e.display_name, jt.name, d.name, m.display_name, l.name, e.joining_date, initcap(replace(e.status, '_', ' ')), e.work_email, e.phone, "
							+ pf("pan") + ", " + pf("uan") + " from employee e" + JOINS + " where" + LIVE + " and e.joining_date <= :asof" + ORG + SCOPE + " order by e.display_name"),
			new Def("joinexit", "people", "Joiners & exits", "Who joined or left in the period, with tenure for leavers", "Monthly", f("XLSX,CSV"), false, "range", "employee", false,
					true, false, List.of(t("Employee ID"), t("Name"), t("Department"), t("Event"), d("Date"), n("Tenure (years)")),
					"select * from (select e.employee_code, e.display_name, d.name, 'Joined', e.joining_date, cast(null as numeric) from employee e" + JOINS
							+ " where e.org_id = :org and e.joining_date between :from and :to" + ORG
							+ " union all select e.employee_code, e.display_name, d.name, 'Left', e.exit_date, round((e.exit_date - e.joining_date) / 365.25, 1) from employee e" + JOINS
							+ " where e.org_id = :org and e.exit_date between :from and :to" + ORG + ") x order by 5 desc"),
			new Def("probation", "people", "Probation & confirmation due", "Unconfirmed people whose probation ends within 60 days of the date, or is already over",
					"Weekly", f("XLSX,CSV"), false, "asof", "employee", false, true, false,
					List.of(t("Employee ID"), t("Name"), t("Department"), t("Manager"), d("Joined"), d("Probation ends"), t("Review status")),
					"select e.employee_code, e.display_name, d.name, m.display_name, e.joining_date, e.probation_end_date,"
							+ " case when e.probation_end_date < :asof then 'Overdue by ' || (:asof - e.probation_end_date) || ' days' else 'Due in ' || (e.probation_end_date - :asof) || ' days' end"
							+ " from employee e" + JOINS + " where" + LIVE + ACTIVE + " and e.confirmed_on is null and e.probation_end_date <= :asof + 60" + ORG
							+ " order by e.probation_end_date"),
			new Def("tenure", "people", "Tenure & span of control", "Direct and total reports per manager, with their team's average tenure", "Quarterly", f("XLSX,CSV"), false,
					"asof", "employee", false, true, false,
					List.of(t("Manager"), t("Department"), n("Direct reports"), n("Total reports"), n("Avg tenure (years)")),
					"with recursive t(root, id) as (select x.manager_id, x.id from employee x where x.org_id = :org and x.status <> 'EXITED' and x.manager_id is not null"
							+ " union all select t.root, x.id from employee x join t on x.manager_id = t.id where x.status <> 'EXITED')"
							+ " select e.display_name, d.name, (select count(*) from employee x where x.manager_id = e.id and x.status <> 'EXITED'),"
							+ " (select count(*) from t where t.root = e.id),"
							+ " (select round(avg((:asof - x.joining_date) / 365.25), 1) from employee x where x.manager_id = e.id and x.status <> 'EXITED')"
							+ " from employee e" + JOINS + " where" + LIVE + ACTIVE + ORG + " and exists (select 1 from employee x where x.manager_id = e.id and x.status <> 'EXITED')"
							+ " order by 4 desc, 1"),
			new Def("celebrate", "people", "Birthdays & work anniversaries", "Everyone celebrating in the selected month", "Monthly", f("XLSX,PDF,CSV"), false, "month", "employee",
					false, true, false, List.of(t("Name"), t("Department"), t("Occasion"), t("Date"), n("Years")),
					"select * from (select e.display_name, d.name, 'Birthday', to_char(" + dob() + ", 'DD Mon'), cast(null as int), extract(day from " + dob() + ") dd from employee e"
							+ JOINS + " where" + LIVE + ACTIVE + ORG + " and extract(month from " + dob() + ") = extract(month from cast(:from as date))"
							+ " union all select e.display_name, d.name, 'Work anniversary', to_char(e.joining_date, 'DD Mon'),"
							+ " cast(extract(year from cast(:from as date)) - extract(year from e.joining_date) as int), extract(day from e.joining_date) from employee e" + JOINS
							+ " where" + LIVE + ACTIVE + ORG
							+ " and extract(month from e.joining_date) = extract(month from cast(:from as date)) and extract(year from e.joining_date) < extract(year from cast(:from as date))"
							+ ") x order by dd, 1"),
			new Def("dq", "people", "Missing profile data", "People missing PAN, bank details, UAN, date of birth or address", "Weekly", f("XLSX,CSV"), false, "asof", "employee",
					false, true, false, List.of(t("Employee ID"), t("Name"), t("Department"), t("Manager"), t("Missing fields")),
					"select * from (select e.employee_code, e.display_name, d.name, m.display_name, concat_ws(', ', case when " + pf("pan") + " is null then 'PAN' end, case when "
							+ pf("acct") + " is null then 'Bank account' end, case when " + pf("ifsc") + " is null then 'IFSC' end, case when " + pf("uan")
							+ " is null then 'UAN' end, case when " + pf("dob") + " is null then 'Date of birth' end, case when " + pf("addr")
							+ " is null then 'Address' end) missing from employee e" + JOINS + " where" + LIVE + ACTIVE + ORG + ") x where missing <> '' order by 2"),
			new Def("diversity", "people", "Diversity", "Gender and age bands by department", "Quarterly", f("XLSX,PDF,CSV"), false, "asof", "employee", false, true, false,
					List.of(t("Department"), n("People"), n("Women"), n("Men"), n("Other"), n("Not stated"), n("Under 25"), n("25–34"), n("35–44"), n("45 and over")),
					"select coalesce(dept, 'No department'), count(*), count(*) filter (where g = 'Female'), count(*) filter (where g = 'Male'),"
							+ " count(*) filter (where g is not null and g not in ('Female', 'Male', 'Prefer not to say')), count(*) filter (where g is null or g = 'Prefer not to say'),"
							+ " count(*) filter (where age < 25), count(*) filter (where age between 25 and 34), count(*) filter (where age between 35 and 44), count(*) filter (where age >= 45)"
							+ " from (select d.name dept, " + pf("gender") + " g, extract(year from age(:asof, " + dob() + ")) age from employee e" + JOINS + " where" + LIVE + ACTIVE
							+ ORG + ") x group by dept order by dept nulls last"),
			// ====================================================== time
			new Def("leavereg", "time", "Leave register", "All approved leave by employee and type", "Monthly", f("XLSX,CSV"), false, "range", "leave", false, true, false,
					List.of(t("Employee ID"), t("Name"), t("Department"), t("Leave type"), d("From"), d("To"), n("Days"), t("Approved by")),
					"select e.employee_code, e.display_name, d.name, lt.name, q.from_date, q.to_date, q.days, ap.display_name from leave_request q join employee e on e.id = q.employee_id"
							+ JOINS + " join leave_type lt on lt.id = q.type_id left join employee ap on ap.id = q.decided_by"
							+ " where q.org_id = :org and q.status = 'APPROVED' and q.from_date <= :to and q.to_date >= :from" + ORG + " order by q.from_date desc, 2"),
			new Def("leavebal", "time", "Leave balances", "Opening, accrued, taken, lapsed and closing balance per leave type for the year", "Monthly", f("XLSX,CSV"), false, "asof",
					"leave", false, true, false,
					List.of(t("Employee ID"), t("Name"), t("Leave type"), n("Opening"), n("Accrued"), n("Taken"), n("Lapsed / encashed"), n("Closing")),
					"select e.employee_code, e.display_name, lt.name, coalesce(sum(g.days) filter (where g.kind = 'OPENING'), 0),"
							+ " coalesce(sum(g.days) filter (where g.kind in ('CREDIT', 'ADJUST')), 0), -coalesce(sum(g.days) filter (where g.kind in ('USED', 'REVERSAL')), 0),"
							+ " -coalesce(sum(g.days) filter (where g.kind in ('LAPSE', 'ENCASH', 'CARRY_OUT')), 0), coalesce(sum(g.days), 0)"
							+ " from leave_ledger g join employee e on e.id = g.employee_id join leave_type lt on lt.id = g.type_id"
							+ " where g.org_id = :org and g.year = extract(year from cast(:asof as date)) and g.at < :asof + 1" + ACTIVE + ORG
							+ " group by e.id, lt.name order by e.display_name, lt.name"),
			new Def("muster", "time", "Attendance muster", "Days present, working from home, on leave and absent for the month", "Monthly", f("XLSX,PDF,CSV"), false, "month",
					"attendance", false, true, false,
					List.of(t("Employee ID"), t("Name"), t("Department"), n("Working days"), n("Present"), n("WFH"), n("On leave"), n("Absent")),
					"with wd as (select count(*) n from generate_series(cast(:from as date), least(cast(:to as date), current_date), interval '1 day') g"
							+ " where extract(isodow from g) < 6 and not exists (select 1 from holiday h join holiday_calendar c on c.id = h.calendar_id join holiday_type ht on ht.id = h.type_id where c.org_id = :org and ht.day_off and h.date = cast(g as date)))"
							+ " select e.employee_code, e.display_name, d.name, wd.n, p.present, p.wfh, coalesce(lv.days, 0), greatest(wd.n - p.present - coalesce(lv.days, 0), 0)"
							+ " from employee e" + JOINS + " cross join wd"
							+ " cross join lateral (select count(*) present, count(*) filter (where a.wfh) wfh from attendance_record a where a.employee_id = e.id and a.day between :from and :to and a.in_at is not null) p"
							+ " left join lateral (select sum(least(q.to_date, cast(:to as date)) - greatest(q.from_date, cast(:from as date)) + 1) days from leave_request q"
							+ " where q.employee_id = e.id and q.status = 'APPROVED' and q.from_date <= :to and q.to_date >= :from) lv on true"
							+ " where" + LIVE + ACTIVE + " and e.joining_date <= :to" + ORG + " order by e.display_name"),
			new Def("regul", "time", "Regularisation requests", "Missed punches and corrections with approver and outcome", "Monthly", f("XLSX,CSV"), false, "range", "attendance",
					false, true, false, List.of(t("Employee ID"), t("Name"), d("Date"), t("Reason"), d("Requested"), t("Status"), t("Approver")),
					"select e.employee_code, e.display_name, g.day, g.reason, cast(g.created_at as date), initcap(g.status), coalesce(dc.display_name, ap.display_name)"
							+ " from attendance_regularisation g join employee e on e.id = g.employee_id" + JOINS
							+ " left join employee ap on ap.id = g.approver_id left join employee dc on dc.id = g.decided_by"
							+ " where g.org_id = :org and g.day between :from and :to" + ORG + " order by g.day desc"),
			new Def("lop", "time", "Loss of pay days", "Unpaid days per employee from the month's pay run", "Monthly", f("XLSX,CSV"), false, "month", "payroll", false, true, false,
					List.of(t("Employee ID"), t("Name"), t("Department"), n("Days in month"), n("Paid days"), n("Unpaid leave"), n("Other LOP"), n("Total LOP")),
					"select e.employee_code, e.display_name, d.name, s.days_in_month, s.paid_days, s.lop_days, s.extra_lop, s.lop_days + s.extra_lop from payslip s" + RUN + ORG
							+ " and s.lop_days + s.extra_lop > 0 order by 8 desc"),
			// ====================================================== pay
			new Def("payreg", "pay", "Payroll register", "Earnings and deductions per employee for the month", "Monthly", f("XLSX,PDF,CSV"), true, "month", "payroll", false, true,
					false,
					List.of(t("Employee ID"), t("Name"), t("Department"), n("Paid days"), pay("Earnings"), pay("Deductions"), pay("Employer contributions"), pay("Net pay")),
					"select e.employee_code, e.display_name, d.name, s.paid_days, s.gross, s.total_deductions, " + all("employer") + ", s.net from payslip s" + RUN + ORG
							+ " order by e.display_name"),
			new Def("variance", "pay", "Salary variance", "Net pay against last month with the likely reason", "Monthly", f("XLSX,CSV"), true, "month", "payroll", false, true, false,
					List.of(t("Employee ID"), t("Name"), pay("Last month"), pay("This month"), pay("Difference"), t("Reason")),
					"select e.employee_code, e.display_name, o.net, s.net, s.net - coalesce(o.net, 0), case when o.net is null then 'New in payroll'"
							+ " when s.lop_days + s.extra_lop <> o.lop then 'Loss of pay changed' when jsonb_array_length(s.adjustments) > 0 then 'One-off adjustment'"
							+ " when s.net <> o.net then 'Salary or tax change' else 'No change' end from payslip s" + RUNJ
							+ " left join lateral (select s2.net, s2.lop_days + s2.extra_lop lop from payslip s2 join pay_run r2 on r2.id = s2.run_id"
							+ " where r2.org_id = :org and r2.period = r.period - interval '1 month' and s2.employee_id = s.employee_id) o on true" + RUNW + ORG
							+ " order by abs(s.net - coalesce(o.net, 0)) desc"),
			new Def("bank", "pay", "Bank transfer file", "Net pay per employee with account details for a bulk upload", "Monthly", f("XLSX,CSV,TXT"), true, "month", "payroll",
					false, true, false, List.of(t("Employee ID"), t("Name"), t("Account holder"), s("Account number"), t("IFSC"), pay("Amount")),
					"select e.employee_code, e.display_name, coalesce(" + pf("legal") + ", e.display_name), " + pf("acct") + ", " + pf("ifsc") + ", s.net from payslip s" + RUN + ORG
							+ " order by e.display_name"),
			new Def("ctc", "pay", "CTC & salary structure", "Annual CTC, tax regime and PF per employee", "On demand", f("XLSX,CSV"), true, "asof", "payroll", false, true, false,
					List.of(t("Employee ID"), t("Name"), t("Department"), t("Title"), t("Tax regime"), t("PF"), pay("Annual CTC"), d("Effective from")),
					"select e.employee_code, e.display_name, d.name, jt.name, initcap(sa.regime), case when sa.pf_enabled then 'Yes' else 'No' end, sa.ctc_annual, sa.effective_from"
							+ " from salary sa join employee e on e.id = sa.employee_id" + JOINS + " where sa.org_id = :org" + ACTIVE + ORG + " order by e.display_name"),
			new Def("paycost", "pay", "Payroll cost by department", "Gross pay, employer contributions and total cost by department", "Monthly", f("XLSX,PDF,CSV"), true, "month",
					"payroll", false, true, false, List.of(t("Department"), n("Headcount"), pay("Gross"), pay("Employer PF"), pay("Employer ESI"), pay("Total cost")),
					"select coalesce(d.name, 'No department'), count(*), sum(s.gross), sum(" + line("employer", "pf") + "), sum(" + line("employer", "esi") + "), sum(s.gross) + sum("
							+ all("employer") + ") from payslip s" + RUN + ORG + " group by d.name order by 6 desc"),
			new Def("reimb", "pay", "Reimbursements", "Claims submitted, approved and paid", "Monthly", f("XLSX,CSV"), false, "range", "requests", false, true, false,
					List.of(t("Request"), t("Employee ID"), t("Name"), t("Claim"), m("Amount"), d("Expense date"), t("Status"), d("Decided on")),
					"select 'RQ-' || q.no, e.employee_code, e.display_name, coalesce(q.title, q.note), q.amount, q.expense_date, initcap(q.status), cast(q.decided_at as date)"
							+ " from employee_request q join employee e on e.id = q.employee_id" + JOINS
							+ " where q.org_id = :org and q.kind = 'REIMBURSEMENT' and cast(q.created_at as date) between :from and :to" + ORG + " order by q.created_at desc"),
			// ====================================================== statutory
			new Def("ecr", "stat", "PF ECR", "Electronic challan cum return in the EPFO upload format (#~# separated)", "Monthly", f("TXT,XLSX"), true, "month", "payroll", true,
					false, false,
					List.of(t("UAN"), t("Name"), n("Gross wages"), n("EPF wages"), n("EPS wages"), n("EDLI wages"), n("EPF contribution (EE)"), n("EPS contribution"),
							n("EPF-EPS difference (ER)"), n("NCP days"), n("Refund of advances")),
					"select coalesce(" + pf("uan") + ", ''), upper(e.display_name), round(s.gross), round(w.epf), round(least(w.epf, 15000)), round(least(w.epf, 15000)), round("
							+ line("deductions", "pf") + "), round(least(w.epf, 15000) * 0.0833), round(" + line("employer", "pf")
							+ ") - round(least(w.epf, 15000) * 0.0833), round(s.lop_days + s.extra_lop), 0 from payslip s" + RUNJ
							+ " cross join lateral (select " + line("earnings", "basic") + " epf) w" + RUNW + " and " + line("deductions", "pf") + " > 0 order by e.display_name"),
			new Def("esi", "stat", "ESI contribution", "Monthly contribution file for the ESIC portal", "Monthly", f("XLSX,CSV"), true, "month", "payroll", true, false, false,
					List.of(t("IP number"), t("Name"), n("Days paid"), n("Wages"), n("EE contribution"), n("ER contribution")),
					"select coalesce(" + pf("esicip") + ", ''), e.display_name, s.paid_days, s.gross, " + line("deductions", "esi") + ", " + line("employer", "esi")
							+ " from payslip s" + RUN + " and " + line("deductions", "esi") + " > 0 order by e.display_name"),
			new Def("pt", "stat", "Professional tax", "PT deducted per state", "Monthly", f("XLSX,CSV"), true, "month", "payroll", true, false, false,
					List.of(t("State"), t("Employee ID"), t("Name"), n("Gross"), n("PT")),
					"select coalesce(s.pt_state, '—'), e.employee_code, e.display_name, s.gross, " + line("deductions", "pt") + " from payslip s" + RUN
							+ " and " + line("deductions", "pt") + " > 0 order by 1, 3"),
			new Def("f24q", "stat", "Form 24Q", "Salary paid and TDS deducted per employee for the quarter, for the TDS return", "Quarterly", f("XLSX,CSV"), true, "quarter",
					"payroll", true, false, false, List.of(t("PAN"), t("Employee ID"), t("Name"), n("Amount paid"), n("TDS deducted")),
					"select coalesce(" + pf("pan") + ", 'PANNOTAVBL'), e.employee_code, e.display_name, sum(s.gross), sum(" + line("deductions", "tds") + ")"
							+ " from payslip s join pay_run r on r.id = s.run_id join employee e on e.id = s.employee_id"
							+ " where r.org_id = :org and r.period between date_trunc('month', cast(:from as date)) and :to group by e.id order by e.display_name"),
			new Def("gratuity", "stat", "Gratuity liability", "Years of service and accrued gratuity (15/26 of last basic per year) per employee", "Quarterly", f("XLSX,CSV"),
					true, "asof", "payroll", true, true, false,
					List.of(t("Employee ID"), t("Name"), d("Joined"), n("Years of service"), n("Last basic"), n("Accrued gratuity"), t("Eligible")),
					"select e.employee_code, e.display_name, e.joining_date, round((:asof - e.joining_date) / 365.25, 1), b.basic,"
							+ " round(15.0 / 26 * coalesce(b.basic, 0) * round((:asof - e.joining_date) / 365.25)), case when (:asof - e.joining_date) / 365.25 >= 4.66 then 'Yes' else 'Not yet' end"
							+ " from employee e" + JOINS + " left join lateral (select " + line("earnings", "basic")
							+ " basic from payslip s join pay_run r on r.id = s.run_id where s.employee_id = e.id order by r.period desc limit 1) b on true"
							+ " where" + LIVE + ACTIVE + ORG + " order by 4 desc"),
			// ====================================================== talent
			new Def("funnel", "talent", "Hiring funnel", "Candidates added in the period by job, and how far they got", "Weekly", f("XLSX,PDF,CSV"), false, "range", "recruiting",
					false, false, false,
					List.of(t("Role"), t("Department"), n("Applied"), n("Screened"), n("Interviewed"), n("Offered"), n("Hired"), p("Applied → hired")),
					"select j.title, d.name, count(c.id), count(c.id) filter (where c.stage in ('SCREENING', 'INTERVIEW', 'OFFER', 'HIRED') or exists (select 1 from candidate_event ce where ce.candidate_id = c.id and ce.text like 'Moved to %')),"
							+ " count(c.id) filter (where exists (select 1 from interview i where i.candidate_id = c.id)), count(c.id) filter (where exists (select 1 from job_offer o where o.candidate_id = c.id)),"
							+ " count(c.id) filter (where c.stage = 'HIRED'), round(100.0 * count(c.id) filter (where c.stage = 'HIRED') / nullif(count(c.id), 0), 1)"
							+ " from job_opening j left join department d on d.id = j.department_id join candidate c on c.job_id = j.id and cast(c.created_at as date) between :from and :to"
							+ " where j.org_id = :org group by j.id, d.name order by 3 desc"),
			new Def("tth", "talent", "Time to hire & offer acceptance", "Days from opening a job to its first offer, and offers accepted", "Monthly", f("XLSX,CSV"), false, "range",
					"recruiting", false, false, false,
					List.of(t("Role"), t("Recruiter"), d("Opened"), d("First offer"), n("Days to offer"), n("Offers"), n("Accepted")),
					"select j.title, rc.display_name, cast(j.created_at as date), cast(min(o.created_at) as date), cast(min(o.created_at) as date) - cast(j.created_at as date), count(o.id),"
							+ " count(o.id) filter (where o.status = 'ACCEPTED') from job_opening j left join employee rc on rc.id = j.recruiter_id"
							+ " left join candidate c on c.job_id = j.id left join job_offer o on o.candidate_id = c.id"
							+ " where j.org_id = :org and cast(j.created_at as date) <= :to and (j.closed_at is null or cast(j.closed_at as date) >= :from)"
							+ " group by j.id, rc.display_name order by 3 desc"),
			new Def("source", "talent", "Source effectiveness", "Applicants and hires by source", "Quarterly", f("XLSX,CSV"), false, "range", "recruiting", false, false, false,
					List.of(t("Source"), n("Applicants"), n("Interviewed"), n("Offered"), n("Hires"), p("Conversion %")),
					"select c.source, count(*), count(*) filter (where exists (select 1 from interview i where i.candidate_id = c.id)),"
							+ " count(*) filter (where exists (select 1 from job_offer o where o.candidate_id = c.id)), count(*) filter (where c.stage = 'HIRED'),"
							+ " round(100.0 * count(*) filter (where c.stage = 'HIRED') / nullif(count(*), 0), 1) from candidate c"
							+ " where c.org_id = :org and cast(c.created_at as date) between :from and :to group by c.source order by 2 desc"),
			new Def("onbprog", "talent", "Onboarding progress", "New hires joining around the date with tasks and documents outstanding", "Weekly", f("XLSX,CSV"), false, "asof",
					"recruiting", false, false, false,
					List.of(t("Name"), t("Title"), d("Joining date"), t("Manager"), t("Status"), t("Tasks done"), n("Documents pending")),
					"select h.first_name || ' ' || h.last_name, h.title, h.doj, m.display_name, initcap(replace(h.status, '_', ' ')),"
							+ " (select count(*) filter (where k.done) || ' of ' || count(*) from onboarding_task k where k.hire_id = h.id),"
							+ " (select count(*) from onboarding_hire_document x where x.hire_id = h.id and x.required and x.status <> 'VERIFIED')"
							+ " from onboarding_hire h left join employee m on m.id = h.manager_id"
							+ " where h.org_id = :org and h.cancelled_at is null and h.doj between :asof - 30 and :asof + 60 order by h.doj"),
			new Def("ratings", "talent", "Rating distribution", "Self, manager and final ratings for a review cycle", "Per cycle", f("XLSX,PDF,CSV"), true, "cycle", "performance",
					false, true, false,
					List.of(t("Department"), t("Manager"), t("Employee"), n("Self rating"), n("Manager rating"), n("Final rating")),
					"select d.name, rv.display_name, e.display_name, r.self_rating, r.manager_rating, r.final_rating from review r join employee e on e.id = r.employee_id"
							+ JOINS + " left join employee rv on rv.id = r.reviewer_id where r.cycle_id = cast(:cycle as uuid)" + ORG + " order by 1, 2, 3"),
			new Def("goals", "talent", "Goal completion", "Goals set, at risk and achieved per person", "Monthly", f("XLSX,CSV"), false, "asof", "performance", false, true, false,
					List.of(t("Employee"), t("Manager"), n("Goals"), n("Active"), n("At risk"), n("Achieved"), p("Avg progress")),
					"select e.display_name, m.display_name, count(g.id), count(g.id) filter (where g.status = 'ACTIVE'),"
							+ " count(g.id) filter (where g.status = 'ACTIVE' and g.due_date < :asof and g.progress < 100), count(g.id) filter (where g.status = 'DONE'),"
							+ " round(avg(g.progress)) from goal g join employee e on e.id = g.employee_id" + JOINS + " where g.org_id = :org and g.status <> 'DROPPED'" + ORG
							+ " group by e.id, m.display_name order by 1"),
			new Def("cycle", "talent", "Review cycle status", "Self review, manager review and calibration per person", "Weekly", f("XLSX,CSV"), false, "cycle", "performance", false,
					true, false, List.of(t("Employee"), t("Manager"), t("Self review"), t("Manager review"), t("Calibrated"), t("Shared")),
					"select e.display_name, rv.display_name, case when r.self_submitted_at is null then 'Pending' else 'Done' end,"
							+ " case when r.manager_submitted_at is null then 'Pending' else 'Done' end, case when r.final_rating is null then 'No' else 'Yes' end,"
							+ " case when c.status = 'CLOSED' then 'Yes' else 'No' end from review r join review_cycle c on c.id = r.cycle_id join employee e on e.id = r.employee_id"
							+ JOINS + " left join employee rv on rv.id = r.reviewer_id where r.cycle_id = cast(:cycle as uuid)" + ORG + " order by 1"),
			// ====================================================== assets
			new Def("assetreg", "assets", "Asset register", "Every hardware asset with holder, status, cost and warranty", "Monthly", f("XLSX,CSV"), true, "asof", "assets", false,
					false, false,
					List.of(t("Tag"), t("Type"), t("Model"), t("Serial"), t("Status"), t("Holder"), pay("Purchase cost"), d("Warranty until")),
					"select a.tag, ty.name, a.model, a.serial, st.name, e.display_name, a.purchase_cost, a.warranty_until from asset a join asset_type ty on ty.id = a.type_id"
							+ " join asset_status st on st.id = a.status_id left join asset_assignment x on x.asset_id = a.id and x.returned_on is null"
							+ " left join employee e on e.id = x.employee_id where a.org_id = :org and a.retired_on is null order by a.tag"),
			new Def("holdings", "assets", "Assets by employee", "Hardware and licences held by each person", "On demand", f("XLSX,CSV"), false, "asof", "assets", false, true, false,
					List.of(t("Employee ID"), t("Name"), t("Department"), t("Hardware"), t("Licences"), d("Holding since")),
					"select * from (select e.employee_code, e.display_name, d.name,"
							+ " (select string_agg(a.tag || ' ' || a.model, ', ') from asset_assignment x join asset a on a.id = x.asset_id where x.employee_id = e.id and x.returned_on is null) hw,"
							+ " (select string_agg(li.name, ', ') from license_seat ls join license li on li.id = ls.license_id where ls.employee_id = e.id and ls.revoked_on is null) lic,"
							+ " least((select min(x.assigned_on) from asset_assignment x where x.employee_id = e.id and x.returned_on is null),"
							+ " (select min(ls.assigned_on) from license_seat ls where ls.employee_id = e.id and ls.revoked_on is null))"
							+ " from employee e" + JOINS + " where" + LIVE + ACTIVE + ORG + ") x where hw is not null or lic is not null order by 2"),
			new Def("warranty", "assets", "Warranty & return expiry", "Warranty, insurance or loan returns ending within 90 days of the date", "Weekly", f("XLSX,CSV"), false, "asof",
					"assets", false, false, false, List.of(t("Tag"), t("Model"), t("Holder"), t("Coverage"), d("Ends"), n("Days left")),
					"select a.tag, a.model, e.display_name, k.what, k.ends, k.ends - :asof from asset a"
							+ " cross join lateral (values ('Warranty', a.warranty_until), ('Insurance', a.insured_until), ('Return due', a.return_due)) k(what, ends)"
							+ " left join asset_assignment x on x.asset_id = a.id and x.returned_on is null left join employee e on e.id = x.employee_id"
							+ " where a.org_id = :org and a.retired_on is null and k.ends between :asof and :asof + 90 order by k.ends"),
			new Def("licutil", "assets", "Licence utilisation & renewals", "Seats used against seats paid, renewal dates and the cost of unused seats", "Monthly",
					f("XLSX,PDF,CSV"), false, "asof", "assets", false, false, false,
					List.of(t("Licence"), t("Owner"), n("Seats"), n("Used"), p("Utilisation"), d("Renews"), m("Annual cost"), m("Unused seats cost")),
					"select li.name, o.display_name, li.seats, u.used, round(100.0 * u.used / nullif(li.seats, 0)), li.renews_on, li.cost_per_seat * coalesce(li.seats, u.used),"
							+ " li.cost_per_seat * greatest(coalesce(li.seats, 0) - u.used, 0) from license li left join employee o on o.id = li.owner_employee_id"
							+ " cross join lateral (select count(*) used from license_seat ls where ls.license_id = li.id and ls.revoked_on is null) u"
							+ " where li.org_id = :org order by 8 desc, 1"),
			new Def("recovery", "assets", "Pending recovery", "Assets still held by people who have left or are serving notice", "Weekly", f("XLSX,CSV"), false, "asof", "assets",
					false, false, false, List.of(t("Employee"), t("Status"), d("Last working day"), t("Tag"), t("Model"), n("Days overdue")),
					"select e.display_name, initcap(replace(e.status, '_', ' ')), e.exit_date, a.tag, a.model, greatest(:asof - e.exit_date, 0)"
							+ " from asset_assignment x join asset a on a.id = x.asset_id join employee e on e.id = x.employee_id"
							+ " where a.org_id = :org and x.returned_on is null and e.status in ('EXITED', 'ON_NOTICE') order by e.exit_date nulls last"),
			// ====================================================== compliance and access (super admins and HR admins)
			new Def("audit", "access", "Audit log", "Every change made in the system, by user and module", "On demand", f("CSV,XLSX"), false, "range", "access", false, false, false,
					List.of(t("When"), t("User"), t("Module"), t("Record"), t("Action")),
					"select to_char(g.at at time zone 'Asia/Kolkata', 'YYYY-MM-DD HH24:MI'), coalesce(u.display_name, 'System'), g.module, g.entity || coalesce(' ' || g.entity_id, ''), g.action"
							+ " from audit_log g left join employee u on u.id = g.actor_id where g.org_id = :org and g.at >= :from and g.at < :to + 1 order by g.at desc limit 50000"),
			new Def("roles", "access", "Access & roles", "Who holds which role, and at what scope", "Monthly", f("XLSX,PDF,CSV"), false, "asof", "access", false, true, false,
					List.of(t("Employee"), t("Work email"), t("Department"), t("Role"), t("Scope")),
					"select e.display_name, e.work_email, d.name, ro.name, initcap(ur.scope) from user_role ur join app_user au on au.id = ur.user_id join employee e on e.id = au.employee_id"
							+ JOINS + " join role ro on ro.id = ur.role_id where au.org_id = :org" + ACTIVE + ORG + " order by 1, 4"),
			new Def("logins", "access", "Sign-in activity", "Sign-ins per user with method, device and where they ended", "Weekly", f("CSV,XLSX"), false, "range", "access", false,
					false, false, List.of(t("When"), t("User"), t("Method"), t("Device"), t("IP"), t("Place"), t("Ended")),
					"select to_char(a.created_at at time zone 'Asia/Kolkata', 'YYYY-MM-DD HH24:MI'), e.display_name, initcap(a.method), coalesce(a.device_label, a.user_agent), host(a.ip), a.place,"
							+ " case when a.revoked_at is not null then 'Signed out' || coalesce(' (' || a.revoked_reason || ')', '') when a.expires_at < now() then 'Expired' else 'Active' end"
							+ " from auth_session a join app_user au on au.id = a.user_id join employee e on e.id = au.employee_id"
							+ " where a.org_id = :org and a.created_at >= :from and a.created_at < :to + 1 order by a.created_at desc limit 50000"),
			new Def("ageing", "access", "Approval ageing", "Pending approvals across modules with days waiting and the current approver", "Weekly", f("XLSX,CSV"), false, "asof",
					"access", false, false, false, List.of(t("Module"), t("Request"), t("Requested by"), t("Approver"), d("Waiting since"), n("Days")),
					"select * from (select 'Leave' mo, 'LV-' || q.no, e.display_name, m.display_name, cast(q.applied_at as date) since from leave_request q join employee e on e.id = q.employee_id"
							+ " left join employee m on m.id = e.manager_id where q.org_id = :org and q.status = 'PENDING'"
							+ " union all select 'Attendance fix', to_char(g.day, 'DD Mon'), e.display_name, ap.display_name, cast(g.created_at as date) from attendance_regularisation g"
							+ " join employee e on e.id = g.employee_id left join employee ap on ap.id = g.approver_id where g.org_id = :org and g.status = 'PENDING'"
							+ " union all select 'Timesheet', 'Week of ' || to_char(t.week_start, 'DD Mon'), e.display_name, ap.display_name, cast(t.submitted_at as date) from timesheet t"
							+ " join employee e on e.id = t.employee_id left join employee ap on ap.id = t.approver_id where t.org_id = :org and t.status = 'SUBMITTED'"
							+ " union all select initcap(q.kind), 'RQ-' || q.no, e.display_name, m.display_name, cast(q.created_at as date) from employee_request q"
							+ " join employee e on e.id = q.employee_id left join employee m on m.id = q.manager_id where q.org_id = :org and q.status in ('PENDING', 'OPEN')"
							+ ") x cross join lateral (select :asof - since days) dd order by since"),
			new Def("policyack", "access", "Policy acknowledgements", "Who has and hasn't acknowledged each published policy that needs it", "On demand", f("XLSX,CSV"), false, "asof",
					"access", false, true, false, List.of(t("Policy"), t("Version"), t("Employee"), t("Department"), d("Acknowledged on")),
					"select p.name, v.version, e.display_name, d.name, cast(k.at as date) from policy p"
							+ " join lateral (select * from policy_version pv where pv.policy_id = p.id order by pv.published_at desc limit 1) v on true"
							+ " join employee e on e.org_id = p.org_id and e.status <> 'EXITED' and e.archived_at is null" + JOINS
							+ " left join policy_ack k on k.version_id = v.id and k.employee_id = e.id"
							+ " where p.org_id = :org and p.archived_at is null and p.ack_required and v.requires_ack" + ORG + " order by 1, (k.at is null) desc, 3"));

	static final Map<String, Def> BY_KEY = new LinkedHashMap<>();

	static {
		DEFS.forEach(x -> BY_KEY.put(x.key(), x));
	}

	static String catLabel(String k) {
		return CATS.stream().filter(c -> c[0].equals(k)).map(c -> c[1]).findFirst().orElse(k);
	}
}
