package com.ivoyant.hrms.leave.service;

import static com.ivoyant.hrms.leave.service.LeaveSupport.days;
import static com.ivoyant.hrms.leave.service.LeaveSupport.problem;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.security.ModuleAccess;
import com.ivoyant.hrms.common.web.ForbiddenException;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.leave.LeavePolicy;
import com.ivoyant.hrms.leave.LeaveTypeView;
import com.ivoyant.hrms.leave.service.LeaveSupport.Type;

/**
 * The leave ledger. Every change to a balance is a row (credit, use, adjustment, encashment, lapse, carry forward); a balance is the sum.
 * Credits are posted lazily the first time a person's year is read, from the leave type's accrual: yearly (pro rata for joiners), monthly,
 * quarterly, per event (the whole quota) or earned (only by adjustment, e.g. comp-off). Each credit has a period key so it is posted once.
 */
@Service
public class LeaveLedgerService {

	static final List<String> KINDS = List.of("OPENING", "CREDIT", "USED", "ADJUST", "ENCASH", "LAPSE", "CARRY_OUT", "REVERSAL");
	private static final Map<String, String> KIND_LABEL = Map.of("OPENING", "Carried forward", "CREDIT", "Credited", "USED", "Leave taken", "ADJUST", "Adjustment", "ENCASH",
			"Encashed", "LAPSE", "Lapsed", "CARRY_OUT", "Carried to next year", "REVERSAL", "Leave cancelled");

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final ModuleAccess access;
	private final LeavePolicy policy;
	private final LeaveSupport support;
	private final Audit audit;

	LeaveLedgerService(JdbcClient jdbc, CurrentUser currentUser, ModuleAccess access, LeavePolicy policy, LeaveSupport support, Audit audit) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.access = access;
		this.policy = policy;
		this.support = support;
		this.audit = audit;
	}

	// ================================================================== posting

	void post(UUID employeeId, UUID typeId, int year, String kind, BigDecimal days, String period, UUID ref, String note) {
		jdbc.sql("""
				insert into leave_ledger (org_id, employee_id, type_id, year, kind, days, period, ref_id, note, by_id)
				values (:org, :e, :t, :y, :k, :d, :p, :r, :n, :by)""").param("org", currentUser.orgId()).param("e", employeeId).param("t", typeId).param("y", year)
				.param("k", kind).param("d", days).param("p", period).param("r", ref, java.sql.Types.OTHER).param("n", note)
				.param("by", currentUser.employeeId(), java.sql.Types.OTHER).update();
	}

	boolean yearClosed(int year) {
		return jdbc.sql("select count(*) from leave_year_close where org_id = :org and year = :y").param("org", currentUser.orgId()).param("y", year).query(Long.class).single() > 0;
	}

	/** Posts the credits the person is due for the year up to today (or the whole year when it is past). Safe to call repeatedly. */
	@Transactional
	public void ensureCredits(UUID employeeId, int year) {
		LocalDate today = LocalDate.now();
		if (year > today.getYear()) {
			return;
		}
		Map<String, Object> p = jdbc.sql("select joining_date, exit_date from employee where id = :id and org_id = :org").param("id", employeeId).param("org", currentUser.orgId())
				.query((rs, i) -> {
					Map<String, Object> m = new HashMap<>();
					m.put("join", rs.getObject(1, LocalDate.class));
					m.put("exit", rs.getObject(2, LocalDate.class));
					return m;
				}).optional().orElse(null);
		if (p == null) {
			return;
		}
		LocalDate join = (LocalDate) p.get("join");
		LocalDate exit = (LocalDate) p.get("exit");
		LocalDate start = join != null && join.getYear() == year ? join : LocalDate.of(year, 1, 1);
		if (join != null && join.getYear() > year) {
			return;
		}
		LocalDate end = year < today.getYear() ? LocalDate.of(year, 12, 31) : today;
		if (exit != null && exit.isBefore(end)) {
			end = exit;
		}
		if (end.isBefore(start)) {
			return;
		}
		for (LeaveTypeView t : policy.activeTypes()) {
			if (t.quota() == null || "EARNED".equals(t.accrual()) || !policy.eligible(employeeId, t, end)) {
				continue;
			}
			BigDecimal q = t.quota();
			switch (t.accrual()) {
				case "YEARLY" -> {
					BigDecimal share = start.getMonthValue() == 1 && start.getDayOfMonth() == 1 ? q
							: q.multiply(BigDecimal.valueOf(13 - start.getMonthValue())).divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
					credit(employeeId, t.id(), year, days(share), "Y", "Yearly credit");
				}
				case "EVENT" -> credit(employeeId, t.id(), year, q, "Y", "Entitlement for the year");
				case "MONTHLY" -> {
					BigDecimal each = q.divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
					for (int m = start.getMonthValue(); m <= end.getMonthValue(); m++) {
						credit(employeeId, t.id(), year, each, String.format("M%02d", m), "Monthly credit · " + java.time.Month.of(m).getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH));
					}
				}
				case "QUARTERLY" -> {
					BigDecimal each = q.divide(BigDecimal.valueOf(4), 2, RoundingMode.HALF_UP);
					for (int qtr = (start.getMonthValue() - 1) / 3 + 1; qtr <= (end.getMonthValue() - 1) / 3 + 1; qtr++) {
						credit(employeeId, t.id(), year, each, "Q" + qtr, "Quarterly credit · Q" + qtr);
					}
				}
				default -> {
				}
			}
		}
	}

	private void credit(UUID employeeId, UUID typeId, int year, BigDecimal d, String period, String note) {
		jdbc.sql("""
				insert into leave_ledger (org_id, employee_id, type_id, year, kind, days, period, note)
				values (:org, :e, :t, :y, 'CREDIT', :d, :p, :n) on conflict do nothing""").param("org", currentUser.orgId()).param("e", employeeId).param("t", typeId)
				.param("y", year).param("d", d).param("p", period).param("n", note).update();
	}

	// ================================================================== reading

	/** One leave type's figures for a person's year. */
	public record Line(Type type, BigDecimal opening, BigDecimal credited, BigDecimal used, BigDecimal adjusted, BigDecimal encashed, BigDecimal lapsed, BigDecimal carried,
			BigDecimal pending) {

		public BigDecimal closing() {
			return opening.add(credited).subtract(used).add(adjusted).subtract(encashed).subtract(lapsed);
		}

		/** What can still be applied for: the closing balance less what was carried out and what is waiting for approval. */
		public BigDecimal available() {
			return closing().subtract(carried).subtract(pending);
		}
	}

	/** Every leave type (active ones, plus inactive ones the person has entries for) for the year. */
	public List<Line> lines(UUID employeeId, int year) {
		Map<UUID, Map<String, BigDecimal>> sums = new HashMap<>();
		jdbc.sql("select type_id, kind, sum(days) from leave_ledger where employee_id = :e and year = :y group by type_id, kind").param("e", employeeId).param("y", year)
				.query((rs, i) -> {
					sums.computeIfAbsent(rs.getObject(1, UUID.class), k -> new HashMap<>()).put(rs.getString(2), rs.getBigDecimal(3));
					return null;
				}).list();
		Map<UUID, BigDecimal> pending = new HashMap<>();
		jdbc.sql("select type_id, sum(days) from leave_request where employee_id = :e and status = 'PENDING' and extract(year from from_date) = :y group by type_id")
				.param("e", employeeId).param("y", year).query((rs, i) -> pending.put(rs.getObject(1, UUID.class), rs.getBigDecimal(2))).list();
		List<Line> out = new ArrayList<>();
		for (Type t : support.types()) {
			Map<String, BigDecimal> s = sums.getOrDefault(t.id(), Map.of());
			if (!t.active() && s.isEmpty()) {
				continue;
			}
			BigDecimal z = BigDecimal.ZERO;
			out.add(new Line(t, s.getOrDefault("OPENING", z), s.getOrDefault("CREDIT", z), s.getOrDefault("USED", z).negate().subtract(s.getOrDefault("REVERSAL", z)),
					s.getOrDefault("ADJUST", z), s.getOrDefault("ENCASH", z).negate(), s.getOrDefault("LAPSE", z).negate(), s.getOrDefault("CARRY_OUT", z).negate(),
					pending.getOrDefault(t.id(), z)));
		}
		return out;
	}

	public BigDecimal available(UUID employeeId, UUID typeId, int year) {
		ensureCredits(employeeId, year);
		return lines(employeeId, year).stream().filter(l -> l.type().id().equals(typeId)).findFirst().map(Line::available).orElse(BigDecimal.ZERO);
	}

	static Map<String, Object> balanceView(Line l) {
		Map<String, Object> m = LeaveSupport.typeRef(l.type());
		m.put("quota", l.type().quota() == null ? days(l.credited().add(l.opening())) : days(l.type().quota()));
		m.put("opening", days(l.opening()));
		m.put("credited", days(l.credited()));
		m.put("used", days(l.used()));
		m.put("pending", days(l.pending()));
		m.put("available", days(l.available()));
		m.put("paid", l.type().paid());
		m.put("active", l.type().active());
		return m;
	}

	/** The rate one day of leave is encashed at: monthly basic (40% of CTC) divided by 26. Zero when no CTC is on file. */
	public BigDecimal dailyRate(UUID employeeId) {
		String ctc = jdbc.sql("select custom ->> 'ctc' from employee where id = :id").param("id", employeeId).query(String.class).optional().orElse(null);
		if (ctc == null || ctc.isBlank()) {
			return BigDecimal.ZERO;
		}
		BigDecimal basic = new BigDecimal(ctc.trim()).divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP).multiply(new BigDecimal("0.40"));
		return basic.divide(BigDecimal.valueOf(26), 2, RoundingMode.HALF_UP);
	}

	// ================================================================== the Balances tab

	/** Everyone's balances for the year, a page at a time. {@code filter}: all, low (CL/SL/EL at or below 2 days), hiel (EL at 10 or more). */
	@Transactional
	public Map<String, Object> board(String q, UUID dept, UUID loc, String filter, int year, int page, int size) {
		requireView();
		StringBuilder where = new StringBuilder(" where e.org_id = :org and e.archived_at is null and e.status <> 'EXITED'");
		Map<String, Object> params = new HashMap<>();
		params.put("org", currentUser.orgId());
		if (q != null && !q.isBlank()) {
			where.append(" and (lower(e.display_name) like :q or lower(e.employee_code) like :q or lower(coalesce(dep.name, '')) like :q)");
			params.put("q", "%" + q.trim().toLowerCase() + "%");
		}
		if (dept != null) {
			where.append(" and e.department_id = :d");
			params.put("d", dept);
		}
		if (loc != null) {
			where.append(" and e.location_id = :l");
			params.put("l", loc);
		}
		List<Map<String, Object>> people = jdbc.sql(LeaveSupport.PERSON_SQL + where + " order by lower(e.display_name)").params(params).query((rs, i) -> LeaveSupport.person(rs)).list();
		List<Type> shown = support.types().stream().filter(Type::active).toList();
		Map<UUID, LeaveTypeView> views = new HashMap<>();
		policy.activeTypes().forEach(v -> views.put(v.id(), v));
		List<Map<String, Object>> rows = new ArrayList<>();
		boolean filtering = filter != null && !filter.isBlank() && !"all".equals(filter);
		// Filtering needs everyone's figures; otherwise only the page is worked out.
		int from = filtering ? 0 : Math.max(0, page) * size;
		int to = filtering ? people.size() : Math.min(people.size(), from + size);
		for (Map<String, Object> p : people.subList(Math.min(from, people.size()), to)) {
			UUID id = (UUID) p.get("id");
			ensureCredits(id, year);
			Map<String, Line> byCode = new HashMap<>();
			lines(id, year).forEach(l -> byCode.put(l.type().code(), l));
			if ("low".equals(filter) && !List.of("CL", "SL", "EL").stream().anyMatch(c -> byCode.containsKey(c) && byCode.get(c).available().compareTo(BigDecimal.TWO) <= 0)) {
				continue;
			}
			if ("hiel".equals(filter) && !(byCode.containsKey("EL") && byCode.get("EL").available().compareTo(BigDecimal.TEN) >= 0)) {
				continue;
			}
			Map<String, Object> row = new LinkedHashMap<>(p);
			List<Map<String, Object>> cells = new ArrayList<>();
			for (Type t : shown) {
				Line l = byCode.get(t.code());
				LeaveTypeView v = views.get(t.id());
				boolean has = l != null && (l.opening().signum() != 0 || l.credited().signum() != 0 || l.adjusted().signum() != 0 || l.used().signum() != 0);
				boolean eligible = v != null && policy.eligible(id, v, LocalDate.now());
				cells.add(l == null || !eligible && !has ? Map.of("code", t.code(), "eligible", false) : balanceView(l));
			}
			row.put("cells", cells);
			rows.add(row);
		}
		long total = filtering ? rows.size() : people.size();
		if (filtering) {
			int a = Math.min(rows.size(), Math.max(0, page) * size);
			rows = rows.subList(a, Math.min(rows.size(), a + size));
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("year", year);
		out.put("types", shown.stream().map(LeaveSupport::typeRef).toList());
		out.put("items", rows);
		out.put("page", page);
		out.put("size", size);
		out.put("total", total);
		return out;
	}

	// ================================================================== the Leave history tab

	@Transactional
	public Map<String, Object> ledger(UUID employeeId, int year) {
		UUID me = currentUser.employeeId();
		if (!employeeId.equals(me)) {
			requireView();
		}
		Map<String, Object> person = support.person(employeeId);
		if (!person.containsKey("code")) {
			throw new NotFoundException("Employee " + employeeId + " not found");
		}
		ensureCredits(employeeId, year);
		Set<Integer> years = new TreeSet<>(java.util.Comparator.reverseOrder());
		years.add(LocalDate.now().getYear());
		years.add(year);
		jdbc.sql("select distinct year from leave_ledger where employee_id = :e").param("e", employeeId).query(Integer.class).list().forEach(years::add);
		Set<Integer> closed = new java.util.HashSet<>(jdbc.sql("select year from leave_year_close where org_id = :org").param("org", currentUser.orgId()).query(Integer.class).list());

		List<Map<String, Object>> rows = new ArrayList<>();
		for (Line l : lines(employeeId, year)) {
			Map<String, Object> m = LeaveSupport.typeRef(l.type());
			m.put("opening", days(l.opening()));
			m.put("credited", days(l.credited()));
			m.put("used", days(l.used()));
			m.put("adjusted", days(l.adjusted()));
			m.put("encashed", days(l.encashed()));
			m.put("lapsed", days(l.lapsed()));
			m.put("closing", days(l.closing()));
			m.put("carried", days(l.carried()));
			m.put("pending", days(l.pending()));
			m.put("available", days(l.available()));
			rows.add(m);
		}
		List<Map<String, Object>> overview = new ArrayList<>();
		for (int y : years) {
			List<Line> ls = lines(employeeId, y);
			Map<String, Object> o = new LinkedHashMap<>();
			o.put("year", y);
			o.put("used", days(ls.stream().map(Line::used).reduce(BigDecimal.ZERO, BigDecimal::add)));
			o.put("encashed", days(ls.stream().map(Line::encashed).reduce(BigDecimal.ZERO, BigDecimal::add)));
			o.put("lapsed", days(ls.stream().map(Line::lapsed).reduce(BigDecimal.ZERO, BigDecimal::add)));
			o.put("el", ls.stream().filter(l -> "EL".equals(l.type().code())).findFirst().map(l -> days(l.closing())).orElse(null));
			o.put("closed", closed.contains(y));
			overview.add(o);
		}
		Map<UUID, Type> types = support.typesById();
		List<Map<String, Object>> tx = jdbc.sql("select type_id, kind, days, note, at, ref_id from leave_ledger where employee_id = :e and year = :y order by at desc, kind")
				.param("e", employeeId).param("y", year).query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					Type t = types.get(rs.getObject(1, UUID.class));
					m.put("code", t == null ? "?" : t.code());
					m.put("color", t == null ? 0 : t.color());
					m.put("kind", rs.getString(2));
					m.put("label", KIND_LABEL.getOrDefault(rs.getString(2), rs.getString(2)));
					m.put("days", days(rs.getBigDecimal(3)));
					m.put("note", rs.getString(4));
					m.put("at", rs.getObject(5, OffsetDateTime.class));
					m.put("refId", rs.getObject(6, UUID.class));
					return m;
				}).list();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("person", person);
		out.put("year", year);
		out.put("closed", closed.contains(year));
		out.put("rate", dailyRate(employeeId));
		out.put("rows", rows);
		out.put("overview", overview);
		out.put("transactions", tx);
		out.put("canManage", access.has("leave", "ADMIN"));
		return out;
	}

	/** People to pick on the Leave history tab, including those who have left when {@code exited}. */
	@Transactional(readOnly = true)
	public List<Map<String, Object>> people(String q, boolean exited) {
		requireView();
		String where = " where e.org_id = :org and e.archived_at is null" + (exited ? "" : " and e.status <> 'EXITED'")
				+ (q == null || q.isBlank() ? "" : " and (lower(e.display_name) like :q or lower(e.employee_code) like :q)");
		var sql = jdbc.sql(LeaveSupport.PERSON_SQL + where + " order by (e.status = 'EXITED'), lower(e.display_name) limit 50").param("org", currentUser.orgId());
		if (q != null && !q.isBlank()) {
			sql = sql.param("q", "%" + q.trim().toLowerCase() + "%");
		}
		return sql.query((rs, i) -> LeaveSupport.person(rs)).list();
	}

	/** {@code {typeCode, year, direction: CREDIT|DEBIT, days, reason}} */
	@Transactional
	public Map<String, Object> adjust(UUID employeeId, Map<String, Object> b) {
		if (!access.has("leave", "ADMIN")) {
			throw new ForbiddenException("Only leave admins can adjust balances");
		}
		Type t = support.typeByCode(String.valueOf(b.get("typeCode")));
		int year = b.get("year") == null ? LocalDate.now().getYear() : Integer.parseInt(b.get("year").toString());
		BigDecimal d;
		try {
			d = new BigDecimal(String.valueOf(b.get("days")).trim());
		}
		catch (NumberFormatException e) {
			throw problem("days", "INVALID", "Enter the number of days, e.g. 1.5");
		}
		if (d.signum() <= 0 || d.remainder(new BigDecimal("0.5")).signum() != 0) {
			throw problem("days", "INVALID", "Days must be more than 0, in half days");
		}
		String reason = LeaveSupport.text(b.get("reason"));
		if (reason == null) {
			throw problem("reason", "REQUIRED", "Add a reason — it's recorded in the audit trail");
		}
		boolean debit = "DEBIT".equalsIgnoreCase(String.valueOf(b.get("direction")));
		BigDecimal signed = debit ? d.negate() : d;
		ensureCredits(employeeId, year);
		if (debit) {
			BigDecimal avail = lines(employeeId, year).stream().filter(l -> l.type().id().equals(t.id())).findFirst().map(Line::available).orElse(BigDecimal.ZERO);
			if (avail.compareTo(d) < 0) {
				throw problem("days", "BALANCE", "Only " + days(avail).toPlainString() + " " + t.code() + " available to deduct");
			}
		}
		post(employeeId, t.id(), year, "ADJUST", signed, null, null, reason);
		if (yearClosed(year)) {
			recarry(employeeId, t, year);
		}
		audit.publish("leave", "LeaveBalance", employeeId, "ADJUST", null, Map.of("type", t.code(), "year", year, "days", signed, "reason", reason));
		return ledger(employeeId, year);
	}

	/** After a closed year changes, moves the difference in what should have been carried into the next year. */
	void recarry(UUID employeeId, Type t, int year) {
		Line l = lines(employeeId, year).stream().filter(x -> x.type().id().equals(t.id())).findFirst().orElse(null);
		if (l == null) {
			return;
		}
		BigDecimal should = carryOf(t, l.closing());
		BigDecimal diff = should.subtract(l.carried());
		if (diff.signum() == 0) {
			return;
		}
		post(employeeId, t.id(), year, "CARRY_OUT", diff.negate(), null, null, "Carry forward recalculated");
		post(employeeId, t.id(), year + 1, "ADJUST", diff, null, null, "Carry forward from " + year + " recalculated");
	}

	/** How much of a closing balance carries into next year under the type's rule. */
	static BigDecimal carryOf(Type t, BigDecimal closing) {
		if (closing.signum() <= 0) {
			return BigDecimal.ZERO;
		}
		return switch (t.carry()) {
			case "ALL" -> closing;
			case "UPTO" -> closing.min(t.carryMax());
			default -> BigDecimal.ZERO;
		};
	}

	void requireView() {
		if (!access.has("leave", "VIEW")) {
			throw new ForbiddenException("Your role can't see other people's leave");
		}
	}

	static String kindLabel(String kind) {
		return KIND_LABEL.getOrDefault(kind, kind);
	}
}
