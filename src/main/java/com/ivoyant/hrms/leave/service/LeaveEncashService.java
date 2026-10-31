package com.ivoyant.hrms.leave.service;

import static com.ivoyant.hrms.leave.service.LeaveSupport.days;
import static com.ivoyant.hrms.leave.service.LeaveSupport.money;
import static com.ivoyant.hrms.leave.service.LeaveSupport.problem;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.security.ModuleAccess;
import com.ivoyant.hrms.common.web.ForbiddenException;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.leave.service.LeaveLedgerService.Line;
import com.ivoyant.hrms.leave.service.LeaveSupport.Type;
import com.ivoyant.hrms.notification.Notifier;

/**
 * Leave encashment and the leave year-end.
 *
 * Encashment turns encashable leave into money at the person's daily rate: on request (up to the type's limit), at exit (the whole
 * encashable balance), or at year-end. Pending → approved (sent to payroll) → paid; or rejected. Approving takes the days off the balance.
 *
 * Year-end closes a leave year for everyone still employed at its end: per leave type the closing balance carries forward up to the type's
 * limit, the excess is encashed (when the type allows and the run asks for it) up to the encash limit, and the rest lapses. Everything a run
 * posts carries the run's id, so reopening the year removes exactly that.
 */
@Service
public class LeaveEncashService {

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final ModuleAccess access;
	private final LeaveSupport support;
	private final LeaveLedgerService ledger;
	private final Notifier notifier;
	private final Audit audit;
	private final ObjectMapper json = new ObjectMapper();

	LeaveEncashService(JdbcClient jdbc, CurrentUser currentUser, ModuleAccess access, LeaveSupport support, LeaveLedgerService ledger, Notifier notifier, Audit audit) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.access = access;
		this.support = support;
		this.ledger = ledger;
		this.notifier = notifier;
		this.audit = audit;
	}

	private void requireAdmin() {
		if (!access.has("leave", "ADMIN")) {
			throw new ForbiddenException("Only leave admins can do this");
		}
	}

	// ================================================================== encashment

	record Enc(UUID id, long no, UUID employeeId, UUID typeId, int year, String kind, BigDecimal days, BigDecimal rate, BigDecimal amount, String status,
			OffsetDateTime createdAt, OffsetDateTime decidedAt, OffsetDateTime paidAt, String note) {
	}

	private static final String ENC_SQL = "select id, no, employee_id, type_id, year, kind, days, rate, amount, status, created_at, decided_at, paid_at, note from leave_encashment";

	private static Enc enc(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new Enc(rs.getObject(1, UUID.class), rs.getLong(2), rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getInt(5), rs.getString(6), rs.getBigDecimal(7),
				rs.getBigDecimal(8), rs.getBigDecimal(9), rs.getString(10), rs.getObject(11, OffsetDateTime.class), rs.getObject(12, OffsetDateTime.class),
				rs.getObject(13, OffsetDateTime.class), rs.getString(14));
	}

	private Enc load(UUID id) {
		return jdbc.sql(ENC_SQL + " where id = :id and org_id = :org").param("id", id).param("org", currentUser.orgId()).query((rs, i) -> enc(rs)).optional()
				.orElseThrow(() -> new NotFoundException("Encashment " + id + " not found"));
	}

	@Transactional(readOnly = true)
	public Map<String, Object> list(String status) {
		ledger.requireView();
		String s = status == null || status.isBlank() || "ALL".equalsIgnoreCase(status) ? null : status.toUpperCase();
		List<Enc> rows = jdbc.sql(ENC_SQL + " where org_id = :org and (cast(:s as text) is null or status = :s) order by case status when 'PENDING' then 0 when 'APPROVED' then 1 else 2 end, created_at desc limit 500")
				.param("org", currentUser.orgId()).param("s", s).query((rs, i) -> enc(rs)).list();
		Map<UUID, Map<String, Object>> people = support.people(rows.stream().map(Enc::employeeId).toList());
		Map<UUID, Type> types = support.typesById();
		int year = LocalDate.now().getYear();
		BigDecimal pending = BigDecimal.ZERO;
		long pendingN = 0;
		BigDecimal paidYear = BigDecimal.ZERO;
		for (Enc e : jdbc.sql(ENC_SQL + " where org_id = :org").param("org", currentUser.orgId()).query((rs, i) -> enc(rs)).list()) {
			if ("PENDING".equals(e.status())) {
				pending = pending.add(e.amount());
				pendingN++;
			}
			if (("PAID".equals(e.status()) || "APPROVED".equals(e.status())) && e.createdAt().getYear() == year) {
				paidYear = paidYear.add(e.amount());
			}
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("items", rows.stream().map(e -> view(e, people.get(e.employeeId()), types.get(e.typeId()))).toList());
		out.put("pending", Map.of("count", pendingN, "amount", money(pending)));
		out.put("paidThisYear", money(paidYear));
		out.put("liability", money(liability(year)));
		out.put("canManage", access.has("leave", "ADMIN"));
		return out;
	}

	/** What everyone's encashable balances would be worth today. */
	private BigDecimal liability(int year) {
		List<Type> encashable = support.types().stream().filter(t -> t.active() && t.encashable()).toList();
		if (encashable.isEmpty()) {
			return BigDecimal.ZERO;
		}
		BigDecimal total = BigDecimal.ZERO;
		for (UUID e : jdbc.sql("select id from employee where org_id = :org and archived_at is null and status <> 'EXITED'").param("org", currentUser.orgId()).query(UUID.class)
				.list()) {
			BigDecimal rate = ledger.dailyRate(e);
			if (rate.signum() == 0) {
				continue;
			}
			for (Line l : ledger.lines(e, year)) {
				if (l.type().encashable() && l.available().signum() > 0) {
					total = total.add(l.available().multiply(rate));
				}
			}
		}
		return total;
	}

	private static Map<String, Object> view(Enc e, Map<String, Object> person, Type t) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", e.id());
		m.put("no", LeaveSupport.encashNo(e.no()));
		m.put("employee", person);
		m.put("type", t == null ? null : LeaveSupport.typeRef(t));
		m.put("year", e.year());
		m.put("kind", e.kind());
		m.put("days", days(e.days()));
		m.put("rate", e.rate());
		m.put("amount", e.amount());
		m.put("status", e.status());
		m.put("createdAt", e.createdAt());
		m.put("decidedAt", e.decidedAt());
		m.put("paidAt", e.paidAt());
		m.put("note", e.note());
		return m;
	}

	/** The new-encashment form: who can be picked (including people who have left), encashable types, and for a person the limits and rate. */
	@Transactional
	public Map<String, Object> options(UUID employeeId, String kind) {
		requireAdmin();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("people", ledger.people(null, true));
		out.put("types", support.types().stream().filter(t -> t.active() && t.encashable()).map(t -> {
			Map<String, Object> m = LeaveSupport.typeRef(t);
			m.put("encashMax", days(t.encashMax()));
			return m;
		}).toList());
		if (employeeId != null) {
			int year = LocalDate.now().getYear();
			ledger.ensureCredits(employeeId, year);
			BigDecimal rate = ledger.dailyRate(employeeId);
			out.put("rate", rate);
			List<Map<String, Object>> limits = new ArrayList<>();
			for (Line l : ledger.lines(employeeId, year)) {
				if (!l.type().encashable()) {
					continue;
				}
				BigDecimal avail = l.available().max(BigDecimal.ZERO);
				BigDecimal max = "EXIT".equals(kind) || l.type().encashMax().signum() == 0 ? avail : avail.min(l.type().encashMax());
				limits.add(Map.of("code", l.type().code(), "available", days(avail), "max", days(max)));
			}
			out.put("limits", limits);
		}
		return out;
	}

	/** {@code {employeeId, typeCode, kind: ON_REQUEST|EXIT, days}}; exit encashes the whole encashable balance. */
	@Transactional
	public Map<String, Object> create(Map<String, Object> b) {
		requireAdmin();
		UUID emp = b.get("employeeId") == null ? null : UUID.fromString(b.get("employeeId").toString());
		if (emp == null) {
			throw problem("employeeId", "REQUIRED", "Choose the person");
		}
		Type t = support.typeByCode(String.valueOf(b.get("typeCode")));
		if (!t.encashable()) {
			throw problem("type", "NOT_ENCASHABLE", t.name() + " can't be encashed");
		}
		String kind = "EXIT".equalsIgnoreCase(String.valueOf(b.get("kind"))) ? "EXIT" : "ON_REQUEST";
		int year = LocalDate.now().getYear();
		BigDecimal avail = ledger.available(emp, t.id(), year).max(BigDecimal.ZERO);
		BigDecimal max = "EXIT".equals(kind) || t.encashMax().signum() == 0 ? avail : avail.min(t.encashMax());
		BigDecimal d;
		if ("EXIT".equals(kind)) {
			d = max;
		}
		else {
			try {
				d = new BigDecimal(String.valueOf(b.get("days")).trim());
			}
			catch (NumberFormatException e) {
				throw problem("days", "INVALID", "Enter the number of days");
			}
		}
		if (d.signum() <= 0) {
			throw problem("days", "INVALID", "EXIT".equals(kind) ? "Nothing left to encash" : "Days must be more than 0");
		}
		if (d.compareTo(max) > 0) {
			throw problem("days", "OVER_LIMIT", "At most " + days(max).toPlainString() + " days can be encashed");
		}
		BigDecimal rate = ledger.dailyRate(emp);
		if (rate.signum() == 0) {
			throw problem("employeeId", "NO_RATE", "No CTC is recorded for this person, so there is no rate to encash at");
		}
		UUID id = jdbc.sql("""
				insert into leave_encashment (org_id, employee_id, type_id, year, kind, days, rate, amount, created_by)
				values (:org, :e, :t, :y, :k, :d, :r, :a, :by) returning id""").param("org", currentUser.orgId()).param("e", emp).param("t", t.id()).param("y", year)
				.param("k", kind).param("d", d).param("r", rate).param("a", money(d.multiply(rate))).param("by", currentUser.employeeId(), java.sql.Types.OTHER)
				.query(UUID.class).single();
		audit.created("leave", "LeaveEncashment", id, Map.of("type", t.code(), "days", d, "kind", kind));
		Enc e = load(id);
		return view(e, support.person(emp), t);
	}

	@Transactional
	public Map<String, Object> decide(UUID id, String action, Map<String, Object> b) {
		requireAdmin();
		Enc e = load(id);
		Type t = support.typesById().get(e.typeId());
		String note = LeaveSupport.text(b == null ? null : b.get("note"));
		switch (action) {
			case "approve" -> {
				if (!"PENDING".equals(e.status())) {
					throw problem("status", "NOT_PENDING", "Only pending encashments can be approved");
				}
				BigDecimal avail = ledger.available(e.employeeId(), e.typeId(), e.year());
				if (avail.compareTo(e.days()) < 0) {
					throw problem("days", "BALANCE", "Only " + days(avail).toPlainString() + " days are left to encash");
				}
				ledger.post(e.employeeId(), e.typeId(), e.year(), "ENCASH", e.days().negate(), null, e.id(), LeaveSupport.encashNo(e.no()) + " · " + kindLabel(e.kind()));
				jdbc.sql("update leave_encashment set status = 'APPROVED', decided_at = now(), decided_by = :by, note = coalesce(:n, note) where id = :id")
						.param("by", currentUser.employeeId(), java.sql.Types.OTHER).param("n", note).param("id", id).update();
				notifier.inApp(List.of(e.employeeId()), "LEAVE_ENCASH", "Leave encashment approved",
						days(e.days()).toPlainString() + " days of " + (t == null ? "leave" : t.name()) + " · paid with the next payroll", "/me/profile");
			}
			case "reject" -> {
				if (!"PENDING".equals(e.status())) {
					throw problem("status", "NOT_PENDING", "Only pending encashments can be rejected");
				}
				jdbc.sql("update leave_encashment set status = 'REJECTED', decided_at = now(), decided_by = :by, note = :n where id = :id")
						.param("by", currentUser.employeeId(), java.sql.Types.OTHER).param("n", note).param("id", id).update();
			}
			case "paid" -> {
				if (!"APPROVED".equals(e.status())) {
					throw problem("status", "NOT_APPROVED", "Only approved encashments can be marked paid");
				}
				jdbc.sql("update leave_encashment set status = 'PAID', paid_at = now() where id = :id").param("id", id).update();
			}
			default -> throw problem("action", "INVALID", "Unknown action " + action);
		}
		audit.publish("leave", "LeaveEncashment", id, action.toUpperCase(), null, note);
		return view(load(id), support.person(e.employeeId()), t);
	}

	private static String kindLabel(String kind) {
		return switch (kind) {
			case "EXIT" -> "Exit (F&F)";
			case "YEAR_END" -> "Year-end";
			default -> "On request";
		};
	}

	// ================================================================== year-end

	record Projection(Map<String, Object> person, BigDecimal closing, BigDecimal carry, BigDecimal encash, BigDecimal lapse, BigDecimal rate) {
	}

	private Projection project(Map<String, Object> person, Type t, int year, boolean withEncash) {
		UUID id = (UUID) person.get("id");
		Line l = ledger.lines(id, year).stream().filter(x -> x.type().id().equals(t.id())).findFirst().orElse(null);
		BigDecimal closing = l == null ? BigDecimal.ZERO : l.closing().subtract(l.pending()).max(BigDecimal.ZERO);
		BigDecimal carry = LeaveLedgerService.carryOf(t, closing);
		BigDecimal excess = closing.subtract(carry).max(BigDecimal.ZERO);
		BigDecimal encash = withEncash && t.encashable() ? (t.encashMax().signum() == 0 ? excess : excess.min(t.encashMax())) : BigDecimal.ZERO;
		return new Projection(person, closing, carry, encash, excess.subtract(encash), withEncash && encash.signum() > 0 ? ledger.dailyRate(id) : BigDecimal.ZERO);
	}

	private List<Map<String, Object>> staff(int year) {
		return jdbc.sql(LeaveSupport.PERSON_SQL + " where e.org_id = :org and e.archived_at is null and e.joining_date <= :end and (e.exit_date is null or e.exit_date > :end) order by lower(e.display_name)")
				.param("org", currentUser.orgId()).param("end", LocalDate.of(year, 12, 31)).query((rs, i) -> LeaveSupport.person(rs)).list();
	}

	/** The year-end screen for one leave type: projected (open year) or what was posted (closed year). */
	@Transactional
	public Map<String, Object> yearEnd(int year, String typeCode, int page, int size) {
		ledger.requireView();
		List<Type> types = support.types().stream().filter(t -> t.active() && t.quota() != null).toList();
		Type t = typeCode == null || typeCode.isBlank() ? types.stream().filter(x -> "EL".equals(x.code())).findFirst().orElse(types.isEmpty() ? null : types.get(0))
				: support.typeByCode(typeCode);
		Map<String, Object> close = jdbc.sql("select closed_at, closed_by, encashed, summary::text from leave_year_close where org_id = :org and year = :y").param("org", currentUser.orgId())
				.param("y", year).query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("closedAt", rs.getObject(1, OffsetDateTime.class));
					m.put("closedBy", rs.getObject(2, UUID.class));
					m.put("encashed", rs.getBoolean(3));
					return m;
				}).optional().orElse(null);
		List<Map<String, Object>> rows = new ArrayList<>();
		BigDecimal sClose = BigDecimal.ZERO, sCarry = BigDecimal.ZERO, sEnc = BigDecimal.ZERO, sLapse = BigDecimal.ZERO, sAmt = BigDecimal.ZERO;
		List<Map<String, Object>> people = staff(year);
		if (t != null) {
			for (Map<String, Object> p : people) {
				UUID id = (UUID) p.get("id");
				ledger.ensureCredits(id, year);
				BigDecimal closing, carry, enc, lapse, amt;
				if (close != null) {
					Line l = ledger.lines(id, year).stream().filter(x -> x.type().id().equals(t.id())).findFirst().orElse(null);
					if (l == null) {
						continue;
					}
					carry = l.carried();
					lapse = l.lapsed();
					enc = jdbc.sql("select coalesce(-sum(days), 0) from leave_ledger where employee_id = :e and type_id = :t and year = :y and kind = 'ENCASH' and note = 'Year-end encashment'")
							.param("e", id).param("t", t.id()).param("y", year).query(BigDecimal.class).single();
					closing = carry.add(lapse).add(enc);
					amt = jdbc.sql("select coalesce(sum(amount), 0) from leave_encashment where employee_id = :e and type_id = :t and year = :y and kind = 'YEAR_END'").param("e", id)
							.param("t", t.id()).param("y", year).query(BigDecimal.class).single();
				}
				else {
					Projection pr = project(p, t, year, true);
					closing = pr.closing();
					carry = pr.carry();
					enc = pr.encash();
					lapse = pr.lapse();
					amt = enc.multiply(pr.rate());
				}
				if (closing.signum() == 0 && carry.signum() == 0 && enc.signum() == 0 && lapse.signum() == 0) {
					continue;
				}
				Map<String, Object> r = new LinkedHashMap<>(p);
				r.put("closing", days(closing));
				r.put("carry", days(carry));
				r.put("encash", days(enc));
				r.put("lapse", days(lapse));
				r.put("encashAmount", money(amt));
				rows.add(r);
				sClose = sClose.add(closing);
				sCarry = sCarry.add(carry);
				sEnc = sEnc.add(enc);
				sLapse = sLapse.add(lapse);
				sAmt = sAmt.add(amt);
			}
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("year", year);
		out.put("years", List.of(LocalDate.now().getYear(), LocalDate.now().getYear() - 1, LocalDate.now().getYear() - 2));
		out.put("type", t == null ? null : Map.of("code", t.code(), "name", t.name(), "carry", t.carry(), "carryMax", days(t.carryMax()), "encashable", t.encashable(), "encashMax",
				days(t.encashMax())));
		out.put("types", types.stream().map(LeaveSupport::typeRef).toList());
		out.put("encashableTypes", types.stream().filter(Type::encashable).map(Type::code).toList());
		if (close != null) {
			close.put("closedByPerson", close.get("closedBy") == null ? null : support.person((UUID) close.get("closedBy")));
		}
		out.put("closed", close);
		out.put("people", people.size());
		out.put("summary", Map.of("closing", days(sClose), "carry", days(sCarry), "encash", days(sEnc), "lapse", days(sLapse), "encashAmount", money(sAmt)));
		int sz = Math.max(1, Math.min(size <= 0 ? 10 : size, 100));
		int a = Math.min(rows.size(), Math.max(0, page) * sz);
		out.put("items", rows.subList(a, Math.min(rows.size(), a + sz)));
		out.put("total", rows.size());
		out.put("page", page);
		out.put("size", sz);
		out.put("canManage", access.has("leave", "ADMIN"));
		out.put("canClose", year < LocalDate.now().getYear() || LocalDate.now().getMonthValue() == 12);
		return out;
	}

	/** {@code {encash: boolean}} Closes the year for every type with a quota. */
	@Transactional
	public Map<String, Object> runYearEnd(int year, Map<String, Object> b) {
		requireAdmin();
		if (ledger.yearClosed(year)) {
			throw problem("year", "CLOSED", year + " is already closed");
		}
		if (year > LocalDate.now().getYear() || year == LocalDate.now().getYear() && LocalDate.now().getMonthValue() < 12) {
			throw problem("year", "TOO_EARLY", "A year can be closed from December onwards");
		}
		boolean withEncash = Boolean.parseBoolean(String.valueOf(b == null ? null : b.get("encash")));
		UUID run = UUID.randomUUID();
		int people = 0;
		BigDecimal carried = BigDecimal.ZERO, lapsed = BigDecimal.ZERO, encashed = BigDecimal.ZERO;
		List<Type> types = support.types().stream().filter(t -> t.active() && t.quota() != null).toList();
		for (Map<String, Object> p : staff(year)) {
			UUID id = (UUID) p.get("id");
			ledger.ensureCredits(id, year);
			people++;
			for (Type t : types) {
				Projection pr = project(p, t, year, withEncash);
				if (pr.carry().signum() > 0) {
					ledger.post(id, t.id(), year, "CARRY_OUT", pr.carry().negate(), null, run, "Carried into " + (year + 1));
					jdbc.sql("""
							insert into leave_ledger (org_id, employee_id, type_id, year, kind, days, period, ref_id, note, by_id)
							values (:org, :e, :t, :y, 'OPENING', :d, 'OPENING', :r, :n, :by)""").param("org", currentUser.orgId()).param("e", id).param("t", t.id())
							.param("y", year + 1).param("d", pr.carry()).param("r", run).param("n", "Carried forward from " + year)
							.param("by", currentUser.employeeId(), java.sql.Types.OTHER).update();
					carried = carried.add(pr.carry());
				}
				if (pr.encash().signum() > 0) {
					jdbc.sql("""
							insert into leave_encashment (org_id, employee_id, type_id, year, kind, days, rate, amount, status, decided_at, decided_by, created_by, note)
							values (:org, :e, :t, :y, 'YEAR_END', :d, :r, :a, 'APPROVED', now(), :by, :by, :n)""").param("org", currentUser.orgId()).param("e", id)
							.param("t", t.id()).param("y", year).param("d", pr.encash()).param("r", pr.rate()).param("a", money(pr.encash().multiply(pr.rate())))
							.param("by", currentUser.employeeId(), java.sql.Types.OTHER).param("n", "Year-end " + year + " · " + run).update();
					ledger.post(id, t.id(), year, "ENCASH", pr.encash().negate(), null, run, "Year-end encashment");
					encashed = encashed.add(pr.encash());
				}
				if (pr.lapse().signum() > 0) {
					ledger.post(id, t.id(), year, "LAPSE", pr.lapse().negate(), null, run, "Lapsed at year-end");
					lapsed = lapsed.add(pr.lapse());
				}
			}
		}
		Map<String, Object> summary = Map.of("run", run.toString(), "people", people, "carried", days(carried), "lapsed", days(lapsed), "encashed", days(encashed));
		try {
			jdbc.sql("insert into leave_year_close (org_id, year, closed_by, encashed, summary) values (:org, :y, :by, :enc, cast(:s as jsonb))").param("org", currentUser.orgId())
					.param("y", year).param("by", currentUser.employeeId(), java.sql.Types.OTHER).param("enc", withEncash).param("s", json.writeValueAsString(summary)).update();
		}
		catch (com.fasterxml.jackson.core.JsonProcessingException e) {
			throw new IllegalStateException(e);
		}
		audit.publish("leave", "LeaveYear", year, "CLOSE", null, summary);
		return yearEnd(year, null, 0, 10);
	}

	/** Undoes a year-end run. Refused when any of its encashments has already been paid. */
	@Transactional
	public Map<String, Object> reopen(int year) {
		requireAdmin();
		String s = jdbc.sql("select summary ->> 'run' from leave_year_close where org_id = :org and year = :y").param("org", currentUser.orgId()).param("y", year).query(String.class)
				.optional().orElseThrow(() -> problem("year", "NOT_CLOSED", year + " isn't closed"));
		if (ledger.yearClosed(year + 1)) {
			throw problem("year", "LATER_CLOSED", "Reopen " + (year + 1) + " first");
		}
		UUID run = UUID.fromString(s);
		String tag = "%" + run;
		if (jdbc.sql("select count(*) from leave_encashment where org_id = :org and kind = 'YEAR_END' and note like :tag and status = 'PAID'").param("org", currentUser.orgId())
				.param("tag", tag).query(Long.class).single() > 0) {
			throw problem("year", "PAID", "Some year-end encashments are already paid, so the year can't be reopened");
		}
		jdbc.sql("delete from leave_ledger where ref_id = :r").param("r", run).update();
		jdbc.sql("delete from leave_encashment where org_id = :org and kind = 'YEAR_END' and note like :tag").param("org", currentUser.orgId()).param("tag", tag).update();
		jdbc.sql("delete from leave_year_close where org_id = :org and year = :y").param("org", currentUser.orgId()).param("y", year).update();
		audit.publish("leave", "LeaveYear", year, "REOPEN", null, null);
		return yearEnd(year, null, 0, 10);
	}

}
