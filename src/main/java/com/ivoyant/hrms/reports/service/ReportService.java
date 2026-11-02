package com.ivoyant.hrms.reports.service;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.security.ModuleAccess;
import com.ivoyant.hrms.common.storage.FileStore;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.ForbiddenException;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.common.web.StatusException;
import com.ivoyant.hrms.common.web.UnprocessableException;
import com.ivoyant.hrms.reports.service.ReportCatalog.Col;
import com.ivoyant.hrms.reports.service.ReportCatalog.Def;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reports: the library, exports (kept 7 days for download again) and email schedules. Each report needs View on its module; statutory
 * files need Admin on Payroll; compliance reports are for super admins and HR admins. Restricted reports mask PAN, bank and pay figures
 * unless the person has Admin on the module and turns masking off.
 */
@Service
public class ReportService {

	public record FileOut(byte[] bytes, String mime, String name) {
	}

	/** What to run: dates are resolved by the page (or by the schedule) and shown as {@code label}. */
	public record Params(LocalDate from, LocalDate to, LocalDate asof, String label, UUID dept, UUID loc, String scope, UUID cycle) {
	}

	static final ZoneId IST = ZoneId.of("Asia/Kolkata");
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
	private static final DateTimeFormatter MON = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH);
	private static final List<String> FREQS = List.of("DAILY", "WEEKLY", "MONTHLY", "QUARTERLY");
	private static final List<String> WEEKDAYS = List.of("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun");

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final ModuleAccess access;
	private final Audit audit;
	private final FileStore store;

	ReportService(JdbcClient jdbc, CurrentUser currentUser, ModuleAccess access, Audit audit, FileStore store) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.access = access;
		this.audit = audit;
		this.store = store;
	}

	// ================================================================== access

	boolean canRun(Def d) {
		if ("access".equals(d.module())) {
			return access.has("access", "ADMIN");
		}
		return access.has(d.module(), d.admin() ? "ADMIN" : "VIEW");
	}

	private boolean canUnmask(Def d) {
		return !"access".equals(d.module()) && access.has(d.module(), "ADMIN");
	}

	Def def(String key) {
		Def d = ReportCatalog.BY_KEY.get(key);
		if (d == null) {
			throw new NotFoundException("No such report");
		}
		if (!canRun(d)) {
			throw new ForbiddenException(d.admin() ? "Only payroll admins can run statutory files" : "Your role can't run this report");
		}
		return d;
	}

	// ================================================================== library and overview

	@Transactional(readOnly = true)
	public Map<String, Object> home() {
		UUID org = currentUser.orgId();
		List<Def> mine = ReportCatalog.DEFS.stream().filter(this::canRun).toList();
		Set<String> keys = new HashSet<>(mine.stream().map(Def::key).toList());
		Map<String, Map<String, Object>> last = new LinkedHashMap<>();
		jdbc.sql("""
				select distinct on (x.report_key) x.report_key, x.run_at, coalesce(e.display_name, 'Schedule') from report_export x left join employee e on e.id = x.run_by
				where x.org_id = :org and x.status = 'READY' order by x.report_key, x.run_at desc""").param("org", org)
				.query((rs, i) -> last.put(rs.getString(1), Map.of("at", rs.getObject(2, OffsetDateTime.class), "by", rs.getString(3)))).list();
		List<Map<String, Object>> lib = new ArrayList<>();
		for (Def d : mine) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("key", d.key());
			m.put("cat", d.cat());
			m.put("name", d.name());
			m.put("desc", d.desc());
			m.put("freq", d.freq());
			m.put("fmts", d.fmts());
			m.put("restricted", d.sens());
			m.put("kind", d.kind());
			m.put("org", d.org());
			m.put("scope", d.scope());
			m.put("stat", "stat".equals(d.cat()));
			m.put("canUnmask", d.sens() && canUnmask(d));
			m.put("cols", d.cols().stream().map(Col::label).toList());
			m.put("last", last.get(d.key()));
			lib.add(m);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("cats", ReportCatalog.CATS.stream().map(c -> Map.of("key", c[0], "label", c[1])).toList());
		out.put("reports", lib);
		List<Map<String, Object>> kpis = new ArrayList<>();
		if (access.has("employee", "VIEW")) {
			long n = one("select count(*) from employee where org_id = :org and archived_at is null and status <> 'EXITED'");
			long depts = one("select count(distinct department_id) from employee where org_id = :org and archived_at is null and status <> 'EXITED'");
			LocalDate today = LocalDate.now(IST);
			LocalDate fy = LocalDate.of(today.getMonthValue() >= 4 ? today.getYear() : today.getYear() - 1, 4, 1);
			long joined = jdbc.sql("select count(*) from employee where org_id = :org and joining_date between :f and current_date").param("org", org).param("f", fy)
					.query(Long.class).single();
			kpis.add(kpi("users", "Active headcount", n, depts + " department" + (depts == 1 ? "" : "s"), false));
			kpis.add(kpi("userPlus", "Joined this FY", joined, "since " + fy.format(DAY), false));
		}
		if (!mine.isEmpty()) {
			String[] ks = keys.toArray(String[]::new);
			long on = count("select count(*) from report_schedule where org_id = :org and enabled and report_key = any(:k)", ks);
			long failed = count("select count(*) from report_schedule where org_id = :org and enabled and last_status = 'FAILED' and report_key = any(:k)", ks);
			kpis.add(kpi("clock", "Scheduled reports", on, failed > 0 ? failed + " failed on last run" : on == 0 ? "none set up" : "all delivered on last run", failed > 0));
			long ex = count("select count(*) from report_export where org_id = :org and run_at > now() - interval '30 days' and report_key = any(:k)", ks);
			long people = count("select count(distinct run_by) from report_export where org_id = :org and run_at > now() - interval '30 days' and report_key = any(:k)", ks);
			kpis.add(kpi("download", "Exports · last 30 days", ex, "by " + people + " " + (people == 1 ? "person" : "people"), false));
		}
		out.put("kpis", kpis);
		List<String> freq = new ArrayList<>();
		Map<String, Long> counts = new LinkedHashMap<>();
		jdbc.sql("select report_key, count(*) from report_export where org_id = :org and run_at > now() - interval '90 days' group by report_key order by 2 desc").param("org", org)
				.query((rs, i) -> counts.put(rs.getString(1), rs.getLong(2))).list();
		counts.keySet().stream().filter(keys::contains).forEach(freq::add);
		for (String k : List.of("hc", "payreg", "muster", "leavereg", "master", "ecr", "funnel", "assetreg")) {
			if (keys.contains(k) && !freq.contains(k)) {
				freq.add(k);
			}
		}
		out.put("frequent", freq.stream().limit(5).map(k -> Map.of("key", k, "count", counts.getOrDefault(k, 0L))).toList());
		out.put("schedules", schedules());
		out.put("recent", history(5));
		if (access.has("employee", "VIEW")) {
			out.put("hcBars", jdbc.sql("""
					select coalesce(d.name, 'No department'), count(*) from employee e left join department d on d.id = e.department_id
					where e.org_id = :org and e.archived_at is null and e.status <> 'EXITED' group by d.name order by 2 desc limit 8""").param("org", org)
					.query((rs, i) -> Map.of("label", rs.getString(1), "n", rs.getLong(2))).list());
		}
		if (access.has("leave", "VIEW")) {
			out.put("leaveBars", jdbc.sql("""
					select lt.name, coalesce(round(100.0 * -coalesce(sum(g.days) filter (where g.kind in ('USED', 'REVERSAL')), 0)
					  / nullif(sum(g.days) filter (where g.kind in ('OPENING', 'CREDIT', 'ADJUST')), 0)), 0)
					from leave_ledger g join leave_type lt on lt.id = g.type_id where g.org_id = :org and g.year = extract(year from current_date)
					group by lt.name having sum(g.days) filter (where g.kind in ('OPENING', 'CREDIT', 'ADJUST')) > 0 order by lt.name limit 6""")
					.param("org", org).query((rs, i) -> Map.of("label", rs.getString(1), "pct", rs.getLong(2))).list());
		}
		out.put("cycles", access.has("performance", "VIEW") ? jdbc.sql("select id, name from review_cycle where org_id = :org and status <> 'DRAFT' order by period_end desc")
				.param("org", org).query((rs, i) -> Map.of("id", rs.getObject(1, UUID.class), "name", rs.getString(2))).list() : List.of());
		return out;
	}

	private static Map<String, Object> kpi(String icon, String label, long value, String sub, boolean bad) {
		return Map.of("icon", icon, "label", label, "value", value, "sub", sub, "bad", bad);
	}

	private long count(String sql, String[] keys) {
		return jdbc.sql(sql).param("org", currentUser.orgId()).param("k", keys).query(Long.class).single();
	}

	private long one(String sql) {
		Number n = jdbc.sql(sql).param("org", currentUser.orgId()).query(Number.class).single();
		return n == null ? 0 : n.longValue();
	}

	// ================================================================== export

	/** Runs the report and keeps the file 7 days. {@code columns}: indexes to include (all when empty). */
	@Transactional
	public Map<String, Object> export(String key, Params p, String format, List<Integer> columns, boolean mask) {
		return doExport(def(key), p, format, columns, mask, null);
	}

	Map<String, Object> doExport(Def d, Params p, String format, List<Integer> columns, boolean mask, UUID scheduleId) {
		if (!d.fmts().contains(format)) {
			throw problem("format", "Pick one of " + String.join(", ", d.fmts()));
		}
		if ("cycle".equals(d.kind()) && p.cycle() == null) {
			throw problem("cycle", "Pick a review cycle");
		}
		if (p.from() != null && p.to() != null && p.from().isAfter(p.to())) {
			throw problem("from", "Pick a start date on or before the end date");
		}
		List<Integer> keep = columns == null || columns.isEmpty() ? IntStream.range(0, d.cols().size()).boxed().toList()
				: columns.stream().filter(i -> i != null && i >= 0 && i < d.cols().size()).distinct().sorted().toList();
		if (keep.isEmpty()) {
			throw problem("columns", "Choose at least one column");
		}
		boolean masked = d.sens() && !"stat".equals(d.cat()) && (mask || !canUnmask(d));
		LocalDate today = LocalDate.now(IST);
		LocalDate asof = p.asof() == null ? today : p.asof();
		LocalDate from = p.from() == null ? asof : p.from();
		LocalDate to = p.to() == null ? asof : p.to();
		int width = d.cols().size();
		List<List<Object>> raw = jdbc.sql(d.sql()).param("org", currentUser.orgId()).param("from", from).param("to", to).param("asof", asof)
				.param("label", p.label() == null ? "" : p.label()).param("dept", p.dept() == null ? null : p.dept().toString(), java.sql.Types.VARCHAR)
				.param("loc", p.loc() == null ? null : p.loc().toString(), java.sql.Types.VARCHAR).param("scope", p.scope() == null ? "active" : p.scope())
				.param("cycle", p.cycle() == null ? null : p.cycle().toString(), java.sql.Types.VARCHAR).query((rs, i) -> row(rs, width)).list();
		List<String> labels = keep.stream().map(i -> d.cols().get(i).label()).toList();
		List<Boolean> numeric = keep.stream().map(i -> d.cols().get(i).numeric() && !(masked && d.cols().get(i).masked())).toList();
		List<List<Object>> rows = new ArrayList<>(raw.size());
		for (List<Object> r : raw) {
			List<Object> o = new ArrayList<>(keep.size());
			for (int i : keep) {
				Object v = r.get(i);
				o.add(masked && d.cols().get(i).masked() && v != null ? maskOf(d.cols().get(i), v) : v);
			}
			rows.add(o);
		}
		String params = paramsText(d, p, masked);
		String sub = params + " · generated " + LocalDateTime.now(IST).format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH));
		byte[] bytes = ReportFiles.write(format, d.key(), d.name(), sub, labels, numeric, rows);
		UUID id = UUID.randomUUID();
		String fileKey = currentUser.orgId() + "/reports/" + id;
		String stamp = p.label() != null && !p.label().isBlank() ? p.label() : asof.toString();
		String name = d.key() + "-" + stamp.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("^-|-$", "") + "." + format.toLowerCase(Locale.ROOT);
		try {
			store.put(fileKey, bytes);
		}
		catch (IOException e) {
			throw new StatusException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_ERROR", "The file could not be saved");
		}
		jdbc.sql("""
				insert into report_export (id, org_id, report_key, params, format, run_by, schedule_id, row_count, file_key, file_name)
				values (:id, :org, :k, :p, :f, :by, :s, :n, :fk, :fn)""").param("id", id).param("org", currentUser.orgId()).param("k", d.key()).param("p", params)
				.param("f", format).param("by", currentUser.employeeId(), java.sql.Types.OTHER).param("s", scheduleId, java.sql.Types.OTHER).param("n", rows.size())
				.param("fk", fileKey).param("fn", name).update();
		audit.publish("reports", "Report", id, "EXPORT", null, Map.of("report", d.key(), "params", params, "rows", rows.size(), "format", format));
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("id", id);
		out.put("rows", rows.size());
		out.put("name", name);
		out.put("fileKey", fileKey);
		out.put("params", params);
		return out;
	}

	private static Object maskOf(Col c, Object v) {
		if ("pay".equals(c.type())) {
			return "XXXX";
		}
		String s = ReportFiles.text(v);
		return s.length() <= 4 ? "XXXX" : "X".repeat(Math.min(8, s.length() - 4)) + s.substring(s.length() - 4);
	}

	private String paramsText(Def d, Params p, boolean masked) {
		List<String> bits = new ArrayList<>();
		if ("asof".equals(d.kind())) {
			bits.add("As of " + (p.asof() == null ? LocalDate.now(IST) : p.asof()).format(DAY));
		}
		else if ("cycle".equals(d.kind())) {
			bits.add(jdbc.sql("select name from review_cycle where id = :c and org_id = :org").param("c", p.cycle()).param("org", currentUser.orgId()).query(String.class)
					.optional().orElse("Review cycle"));
		}
		else if (p.label() != null && !p.label().isBlank()) {
			bits.add(p.label());
		}
		else if (p.from() != null && p.to() != null) {
			bits.add(p.from().format(DAY) + " → " + p.to().format(DAY));
		}
		if (d.org()) {
			bits.add(p.dept() == null ? "All departments" : name("department", p.dept()));
			if (p.loc() != null) {
				bits.add(name("location", p.loc()));
			}
		}
		if (d.scope() && p.scope() != null && !"active".equals(p.scope())) {
			bits.add("all".equals(p.scope()) ? "Include exited" : "Exited only");
		}
		if (d.sens() && !"stat".equals(d.cat()) && !masked) {
			bits.add("Unmasked");
		}
		return String.join(" · ", bits);
	}

	private String name(String table, UUID id) {
		return jdbc.sql("select name from " + table + " where id = :id and org_id = :org").param("id", id).param("org", currentUser.orgId()).query(String.class).optional()
				.orElse("?");
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
			else if (o instanceof Double || o instanceof Float) {
				o = BigDecimal.valueOf(((Number) o).doubleValue());
			}
			r.add(o);
		}
		return r;
	}

	// ================================================================== history

	@Transactional(readOnly = true)
	public List<Map<String, Object>> history(int limit) {
		List<Map<String, Object>> out = new ArrayList<>();
		jdbc.sql("""
				select x.id, x.report_key, x.params, x.format, coalesce(e.display_name, 'Schedule'), x.run_at, x.row_count, x.status, x.error, x.schedule_id is not null
				from report_export x left join employee e on e.id = x.run_by where x.org_id = :org order by x.run_at desc limit 300""").param("org", currentUser.orgId())
				.query((rs, i) -> {
					Def d = ReportCatalog.BY_KEY.get(rs.getString(2));
					if (d == null || !canRun(d) || out.size() >= limit) {
						return 0;
					}
					OffsetDateTime at = rs.getObject(6, OffsetDateTime.class);
					String st = "FAILED".equals(rs.getString(8)) ? "Failed" : at.isBefore(OffsetDateTime.now().minusDays(7)) ? "Expired" : "Ready";
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("id", rs.getObject(1, UUID.class));
					m.put("key", d.key());
					m.put("name", d.name());
					m.put("params", rs.getString(3));
					m.put("format", rs.getString(4));
					m.put("by", rs.getBoolean(10) ? rs.getString(5) + " · scheduled" : rs.getString(5));
					m.put("at", at);
					m.put("rows", rs.getObject(7));
					m.put("status", st);
					m.put("error", rs.getString(9));
					out.add(m);
					return 1;
				}).list();
		return out;
	}

	@Transactional(readOnly = true)
	public FileOut file(UUID id) {
		Map<String, Object> x = jdbc.sql("select report_key, file_key, file_name, format, run_at, status from report_export where id = :id and org_id = :org").param("id", id)
				.param("org", currentUser.orgId()).query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("key", rs.getString(1));
					m.put("file", rs.getString(2));
					m.put("name", rs.getString(3));
					m.put("fmt", rs.getString(4));
					m.put("at", rs.getObject(5, OffsetDateTime.class));
					m.put("st", rs.getString(6));
					return m;
				}).optional().orElseThrow(() -> new NotFoundException("Export not found"));
		def((String) x.get("key"));
		if (!"READY".equals(x.get("st")) || x.get("file") == null || ((OffsetDateTime) x.get("at")).isBefore(OffsetDateTime.now().minusDays(7))) {
			throw new StatusException(HttpStatus.GONE, "EXPIRED", "This file has expired; run the report again");
		}
		try {
			return new FileOut(store.get((String) x.get("file")), ReportFiles.mime((String) x.get("fmt")), (String) x.get("name"));
		}
		catch (IOException e) {
			throw new NotFoundException("The file is no longer in storage");
		}
	}

	// ================================================================== schedules

	@Transactional(readOnly = true)
	public List<Map<String, Object>> schedules() {
		List<Map<String, Object>> out = new ArrayList<>();
		jdbc.sql("""
				select id, report_key, frequency, day_of_month, weekday, at_time, recipients, format, enabled, next_run_at, last_run_at, last_status, last_error
				from report_schedule where org_id = :org order by enabled desc, next_run_at""").param("org", currentUser.orgId()).query((rs, i) -> {
			Def d = ReportCatalog.BY_KEY.get(rs.getString(2));
			if (d == null || !canRun(d)) {
				return 0;
			}
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", rs.getObject(1, UUID.class));
			m.put("key", d.key());
			m.put("name", d.name());
			m.put("cat", ReportCatalog.catLabel(d.cat()));
			m.put("freq", rs.getString(3));
			m.put("day", rs.getString(4));
			m.put("weekday", rs.getString(5));
			m.put("time", rs.getObject(6, LocalTime.class).toString());
			m.put("to", rs.getString(7));
			m.put("format", rs.getString(8));
			m.put("enabled", rs.getBoolean(9));
			m.put("next", rs.getObject(10, OffsetDateTime.class));
			m.put("lastAt", rs.getObject(11, OffsetDateTime.class));
			m.put("last", rs.getString(12));
			m.put("error", rs.getString(13));
			out.add(m);
			return 1;
		}).list();
		return out;
	}

	/** Body: {@code key, freq, day, weekday, time, to, format}. */
	@Transactional
	public Map<String, Object> saveSchedule(UUID id, Map<String, Object> b) {
		Def d = def(String.valueOf(b.get("key")));
		String freq = String.valueOf(b.get("freq")).toUpperCase(Locale.ROOT);
		if (!FREQS.contains(freq)) {
			throw problem("freq", "Pick how often");
		}
		String day = String.valueOf(b.getOrDefault("day", "1"));
		if (!day.equals("Last day") && !day.matches("([1-9]|1\\d|2[0-8])")) {
			throw problem("day", "Pick a day between 1 and 28, or the last day");
		}
		String wd = String.valueOf(b.getOrDefault("weekday", "Mon"));
		if (!WEEKDAYS.contains(wd)) {
			throw problem("weekday", "Pick a weekday");
		}
		LocalTime time;
		try {
			time = LocalTime.parse(String.valueOf(b.get("time")));
		}
		catch (RuntimeException e) {
			throw problem("time", "Pick a time");
		}
		String to = String.valueOf(b.getOrDefault("to", "")).trim();
		List<String> mails = List.of(to.split("\\s*[,;]\\s*")).stream().filter(s -> !s.isBlank()).toList();
		if (mails.isEmpty()) {
			throw problem("to", "Add at least one recipient");
		}
		for (String mail : mails) {
			if (!mail.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
				throw problem("to", mail + " isn't an email address");
			}
		}
		if (mails.size() > 20) {
			throw problem("to", "Up to 20 recipients");
		}
		String fmt = String.valueOf(b.get("format"));
		if (!d.fmts().contains(fmt)) {
			throw problem("format", "Pick one of " + String.join(", ", d.fmts()));
		}
		OffsetDateTime next = nextRun(freq, day, wd, time, OffsetDateTime.now(IST));
		var q = id == null ? jdbc.sql("""
				insert into report_schedule (org_id, report_key, frequency, day_of_month, weekday, at_time, recipients, format, next_run_at, created_by)
				values (:org, :k, :f, :d, :w, :t, :to, :fmt, :next, :by) returning id""").param("by", currentUser.requireEmployeeId())
				: jdbc.sql("""
						update report_schedule set report_key = :k, frequency = :f, day_of_month = :d, weekday = :w, at_time = :t, recipients = :to, format = :fmt,
						  next_run_at = :next, last_status = case when last_status = 'FAILED' then null else last_status end, last_error = null
						where id = :id and org_id = :org returning id""").param("id", id);
		UUID out = q.param("org", currentUser.orgId()).param("k", d.key()).param("f", freq).param("d", day).param("w", wd).param("t", time)
				.param("to", String.join(", ", mails)).param("fmt", fmt).param("next", next).query(UUID.class).optional()
				.orElseThrow(() -> new NotFoundException("Schedule not found"));
		audit.publish("reports", "ReportSchedule", out, id == null ? "CREATE" : "UPDATE", null, Map.of("report", d.key(), "freq", freq, "to", String.join(", ", mails)));
		return Map.of("id", out, "next", next);
	}

	@Transactional
	public void setEnabled(UUID id, boolean on) {
		Map<String, Object> s = scheduleRow(id);
		def((String) s.get("key"));
		OffsetDateTime next = nextRun((String) s.get("freq"), (String) s.get("day"), (String) s.get("wd"), (LocalTime) s.get("time"), OffsetDateTime.now(IST));
		jdbc.sql("update report_schedule set enabled = :on, next_run_at = :n where id = :id").param("on", on).param("n", next).param("id", id).update();
		audit.publish("reports", "ReportSchedule", id, on ? "RESUME" : "PAUSE", null, null);
	}

	@Transactional
	public void deleteSchedule(UUID id) {
		Map<String, Object> s = scheduleRow(id);
		def((String) s.get("key"));
		jdbc.sql("delete from report_schedule where id = :id").param("id", id).update();
		audit.publish("reports", "ReportSchedule", id, "DELETE", null, null);
	}

	private Map<String, Object> scheduleRow(UUID id) {
		return jdbc.sql("select report_key, frequency, day_of_month, weekday, at_time from report_schedule where id = :id and org_id = :org").param("id", id)
				.param("org", currentUser.orgId()).query((rs, i) -> Map.<String, Object>of("key", rs.getString(1), "freq", rs.getString(2), "day", rs.getString(3), "wd",
						rs.getString(4), "time", rs.getObject(5, LocalTime.class)))
				.optional().orElseThrow(() -> new NotFoundException("Schedule not found"));
	}

	/** The first run strictly after {@code now}, in India time. */
	static OffsetDateTime nextRun(String freq, String day, String wd, LocalTime time, OffsetDateTime now) {
		LocalDateTime n = now.atZoneSameInstant(IST).toLocalDateTime();
		LocalDate d = n.toLocalDate();
		for (int i = 0; i < 800; i++, d = d.plusDays(1)) {
			if (matches(freq, day, wd, d) && LocalDateTime.of(d, time).isAfter(n)) {
				break;
			}
		}
		return LocalDateTime.of(d, time).atZone(IST).toOffsetDateTime();
	}

	private static boolean matches(String freq, String day, String wd, LocalDate d) {
		return switch (freq) {
			case "DAILY" -> true;
			case "WEEKLY" -> d.getDayOfWeek() == DayOfWeek.of(WEEKDAYS.indexOf(wd) + 1);
			case "QUARTERLY" -> (d.getMonthValue() - 1) % 3 == 0 && dayMatches(day, d);
			default -> dayMatches(day, d);
		};
	}

	private static boolean dayMatches(String day, LocalDate d) {
		return "Last day".equals(day) ? d.equals(d.with(TemporalAdjusters.lastDayOfMonth())) : d.getDayOfMonth() == Integer.parseInt(day);
	}

	/** What a scheduled run covers: the last full period before the run, for all departments. */
	Params scheduledParams(Def d, String freq) {
		LocalDate today = LocalDate.now(IST);
		LocalDate prevMonth = today.minusMonths(1).withDayOfMonth(1);
		LocalDate qStart = today.withDayOfMonth(1).minusMonths((today.getMonthValue() - 1) % 3).minusMonths(3);
		Params month = new Params(prevMonth, prevMonth.with(TemporalAdjusters.lastDayOfMonth()), null, prevMonth.format(MON), null, null, "active", null);
		Params quarter = new Params(qStart, qStart.plusMonths(3).minusDays(1), null, quarterLabel(qStart), null, null, "active", null);
		return switch (d.kind()) {
			case "month" -> month;
			case "quarter" -> quarter;
			case "range" -> switch (freq) {
				case "DAILY" -> new Params(today.minusDays(1), today.minusDays(1), null, today.minusDays(1).format(DAY), null, null, "active", null);
				case "WEEKLY" -> {
					LocalDate mon = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
					yield new Params(mon, mon.plusDays(6), null, "Week of " + mon.format(DAY), null, null, "active", null);
				}
				case "QUARTERLY" -> quarter;
				default -> month;
			};
			case "cycle" -> new Params(null, null, null, null, null, null, "active",
					jdbc.sql("select id from review_cycle where org_id = :org and status <> 'DRAFT' order by period_end desc limit 1").param("org", currentUser.orgId())
							.query(UUID.class).optional().orElse(null));
			default -> new Params(null, null, today, null, null, null, "active", null);
		};
	}

	/** Indian financial-year quarters: Q1 is Apr–Jun. */
	static String quarterLabel(LocalDate qStart) {
		int fy = qStart.getMonthValue() >= 4 ? qStart.getYear() : qStart.getYear() - 1;
		int q = ((qStart.getMonthValue() - 4 + 12) % 12) / 3 + 1;
		return "Q" + q + " FY " + fy + "–" + String.valueOf(fy + 1).substring(2);
	}

	private static UnprocessableException problem(String field, String msg) {
		return new UnprocessableException(List.of(ErrorItem.field(field, "INVALID", msg)));
	}
}
