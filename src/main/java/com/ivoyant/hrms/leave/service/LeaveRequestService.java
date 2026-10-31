package com.ivoyant.hrms.leave.service;

import static com.ivoyant.hrms.leave.service.LeaveSupport.days;
import static com.ivoyant.hrms.leave.service.LeaveSupport.problem;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.security.ModuleAccess;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.ForbiddenException;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.common.web.UnprocessableException;
import com.ivoyant.hrms.leave.ApplyError;
import com.ivoyant.hrms.leave.ApplyForm;
import com.ivoyant.hrms.leave.LeavePolicy;
import com.ivoyant.hrms.leave.LeaveTypeView;
import com.ivoyant.hrms.leave.ResolvedStep;
import com.ivoyant.hrms.leave.service.LeaveSupport.Type;
import com.ivoyant.hrms.notification.Notifier;

/**
 * Leave requests. Applying checks the policy and the balance, works out the approval chain for the person and the number of days, and
 * snapshots it onto the request; nobody to approve means it is approved at once. Each approver acts in turn; the last approval posts the
 * days to the ledger. A rejection ends it. Cancelling an approved request gives the days back.
 */
@Service
public class LeaveRequestService implements com.ivoyant.hrms.leave.LeaveSummary {

	private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
	private static final String LINK = "/leave";

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final ModuleAccess access;
	private final LeavePolicy policy;
	private final LeaveSupport support;
	private final LeaveLedgerService ledger;
	private final Notifier notifier;
	private final Audit audit;
	private final LeaveFieldStore fields;

	LeaveRequestService(JdbcClient jdbc, CurrentUser currentUser, ModuleAccess access, LeavePolicy policy, LeaveSupport support, LeaveLedgerService ledger, Notifier notifier,
			Audit audit, LeaveFieldStore fields) {
		this.fields = fields;
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.access = access;
		this.policy = policy;
		this.support = support;
		this.ledger = ledger;
		this.notifier = notifier;
		this.audit = audit;
	}

	record Req(UUID id, long no, UUID employeeId, UUID typeId, LocalDate from, LocalDate to, boolean half, BigDecimal days, String reason, boolean hasDocument, String status,
			OffsetDateTime appliedAt, UUID appliedBy, OffsetDateTime decidedAt, String note) {
	}

	record Step(UUID id, int sort, String approver, UUID approverId, UUID delegatedFrom, String state, OffsetDateTime actedAt, String note) {
	}

	private static final String REQ_SQL = "select id, no, employee_id, type_id, from_date, to_date, half_day, days, reason, has_document, status, applied_at, applied_by, decided_at, decision_note from leave_request";

	private static Req req(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new Req(rs.getObject(1, UUID.class), rs.getLong(2), rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getObject(5, LocalDate.class),
				rs.getObject(6, LocalDate.class), rs.getBoolean(7), rs.getBigDecimal(8), rs.getString(9), rs.getBoolean(10), rs.getString(11), rs.getObject(12, OffsetDateTime.class),
				rs.getObject(13, UUID.class), rs.getObject(14, OffsetDateTime.class), rs.getString(15));
	}

	Req load(UUID id) {
		return jdbc.sql(REQ_SQL + " where id = :id and org_id = :org").param("id", id).param("org", currentUser.orgId()).query((rs, i) -> req(rs)).optional()
				.orElseThrow(() -> new NotFoundException("Leave request " + id + " not found"));
	}

	List<Step> steps(UUID requestId) {
		return jdbc.sql("select id, sort, approver, approver_emp_id, delegated_from, state, acted_at, note from leave_request_step where request_id = :r order by sort")
				.param("r", requestId).query((rs, i) -> new Step(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3), rs.getObject(4, UUID.class), rs.getObject(5, UUID.class),
						rs.getString(6), rs.getObject(7, OffsetDateTime.class), rs.getString(8)))
				.list();
	}

	private void event(UUID requestId, String kind, String text) {
		jdbc.sql("insert into leave_request_event (request_id, actor_id, kind, text) values (:r, :a, :k, :t)").param("r", requestId)
				.param("a", currentUser.employeeId(), java.sql.Types.OTHER).param("k", kind).param("t", text).update();
	}

	private boolean admin() {
		return access.has("leave", "ADMIN");
	}

	private static String range(LocalDate from, LocalDate to) {
		return from.equals(to) ? D.format(from) + " " + from.getYear() : D.format(from) + " – " + D.format(to) + " " + to.getYear();
	}

	private static String dd(BigDecimal d) {
		String s = days(d).toPlainString();
		return s + (d.compareTo(BigDecimal.ONE) == 0 ? " day" : " days");
	}

	// ================================================================== apply

	/** The apply drawer: types the person can take with their balances. */
	@Transactional
	public Map<String, Object> applyOptions(UUID employeeId) {
		UUID emp = target(employeeId);
		int year = LocalDate.now().getYear();
		ledger.ensureCredits(emp, year);
		Map<UUID, LeaveLedgerService.Line> byId = new HashMap<>();
		ledger.lines(emp, year).forEach(l -> byId.put(l.type().id(), l));
		List<Map<String, Object>> types = new ArrayList<>();
		for (LeaveTypeView t : policy.activeTypes()) {
			if (!policy.eligible(emp, t, LocalDate.now())) {
				continue;
			}
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("code", t.code());
			m.put("name", t.name());
			m.put("short", t.shortLabel());
			m.put("color", t.colorIndex());
			m.put("halfDay", t.halfDay());
			m.put("noticeDays", t.noticeDays());
			m.put("docAfterDays", t.docAfterDays());
			m.put("maxConsecutive", t.maxConsecutive());
			m.put("countAs", t.countAs());
			LeaveLedgerService.Line l = byId.get(t.id());
			m.put("available", l == null ? BigDecimal.ZERO : days(l.available()));
			m.put("quota", t.quota() == null ? null : days(t.quota()));
			types.add(m);
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("person", support.person(emp));
		out.put("types", types);
		return out;
	}

	private UUID target(UUID employeeId) {
		UUID me = currentUser.requireEmployeeId();
		if (employeeId == null || employeeId.equals(me)) {
			return me;
		}
		if (!access.has("leave", "EDIT")) {
			throw new ForbiddenException("Only HR can apply leave for someone else");
		}
		return employeeId;
	}

	/** {@code {employeeId?, typeCode, from, to, half, reason, hasDocument}} */
	@Transactional
	public Map<String, Object> apply(Map<String, Object> b) {
		UUID emp = target(b.get("employeeId") == null || b.get("employeeId").toString().isBlank() ? null : UUID.fromString(b.get("employeeId").toString()));
		String code = LeaveSupport.text(b.get("typeCode"));
		if (code == null) {
			throw problem("type", "REQUIRED", "Choose a leave type");
		}
		LeaveTypeView t = policy.type(code).orElseThrow(() -> problem("type", "UNKNOWN_TYPE", "There is no active leave type " + code));
		if (b.get("from") == null || b.get("from").toString().isBlank()) {
			throw problem("from", "REQUIRED", "Choose the start date");
		}
		LocalDate from = LocalDate.parse(b.get("from").toString());
		LocalDate to = b.get("to") == null || b.get("to").toString().isBlank() ? from : LocalDate.parse(b.get("to").toString());
		boolean half = Boolean.parseBoolean(String.valueOf(b.get("half")));
		String reason = LeaveSupport.text(b.get("reason"));
		boolean doc = Boolean.parseBoolean(String.valueOf(b.get("hasDocument")));
		// Unpaid leave (loss of pay) has no balance to check.
		BigDecimal avail = t.paid() ? ledger.available(emp, t.id(), from.getYear()) : null;
		List<ApplyError> errors = new ArrayList<>(policy.validate(emp, t, new ApplyForm(from, to, half, doc, avail)));
		if (reason == null) {
			errors.add(new ApplyError("reason", "REQUIRED", "Add a reason"));
		}
		Long overlap = jdbc.sql("select no from leave_request where employee_id = :e and status in ('PENDING', 'APPROVED') and from_date <= :to and to_date >= :from limit 1")
				.param("e", emp).param("from", from).param("to", to).query(Long.class).optional().orElse(null);
		if (overlap != null) {
			errors.add(new ApplyError("from", "OVERLAP", "These dates overlap " + LeaveSupport.requestNo(overlap)));
		}
		if (!errors.isEmpty()) {
			throw new UnprocessableException(errors.stream().map(e -> ErrorItem.field(e.field(), e.code(), e.message())).toList());
		}
		@SuppressWarnings("unchecked")
		Map<String, Object> extra = b.get("fields") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
		BigDecimal days = policy.countDays(emp, t, from, to, half);
		List<ResolvedStep> chain = policy.chainFor(emp, days);
		UUID id = jdbc.sql("""
				insert into leave_request (org_id, employee_id, type_id, from_date, to_date, half_day, days, reason, has_document, applied_by)
				values (:org, :e, :t, :f, :to, :h, :d, :r, :doc, :by) returning id""").param("org", currentUser.orgId()).param("e", emp).param("t", t.id()).param("f", from)
				.param("to", to).param("h", half).param("d", days).param("r", reason).param("doc", doc).param("by", currentUser.requireEmployeeId()).query(UUID.class).single();
		for (ResolvedStep s : chain) {
			jdbc.sql("insert into leave_request_step (request_id, sort, approver, approver_emp_id, delegated_from, state) values (:r, :s, :a, :e, :df, :st)").param("r", id)
					.param("s", s.sort()).param("a", s.approver()).param("e", s.employeeId()).param("df", s.delegatedFrom(), java.sql.Types.OTHER)
					.param("st", s.sort() == chain.get(0).sort() ? "PENDING" : "WAITING").update();
		}
		fields.write("leave", id, null, extra, false);
		String who = emp.equals(currentUser.employeeId()) ? null : "Applied by " + support.person(currentUser.requireEmployeeId()).get("name");
		event(id, "APPLIED", who);
		Req r = load(id);
		if (chain.isEmpty()) {
			finishApproval(r, "No approval needed under the approval chain");
			event(id, "AUTO_APPROVED", "No approval needed");
		}
		else {
			notifyApprover(r, chain.get(0).employeeId());
		}
		audit.created("leave", "LeaveRequest", id, Map.of("type", t.code(), "from", from, "to", to, "days", days));
		return detail(id);
	}

	private void notifyApprover(Req r, UUID approver) {
		Map<String, Object> p = support.person(r.employeeId());
		Type t = support.typesById().get(r.typeId());
		notifier.inApp(List.of(approver), "LEAVE_REQUEST", p.get("name") + " applied for " + dd(r.days()) + " of " + (t == null ? "leave" : t.name().toLowerCase(Locale.ROOT)),
				range(r.from(), r.to()) + " · " + r.reason(), LINK + "?request=" + r.id());
	}

	// ================================================================== decide

	private Step current(UUID requestId) {
		return steps(requestId).stream().filter(s -> "PENDING".equals(s.state())).findFirst().orElse(null);
	}

	private boolean canDecide(Req r, Step cur) {
		if (!"PENDING".equals(r.status()) || cur == null) {
			return false;
		}
		UUID me = currentUser.employeeId();
		if (me != null && me.equals(r.employeeId())) {
			return false;
		}
		return me != null && me.equals(cur.approverId()) || admin();
	}

	@Transactional
	public Map<String, Object> approve(UUID id, Map<String, Object> b) {
		Req r = load(id);
		Step cur = current(id);
		if (!canDecide(r, cur)) {
			throw new ForbiddenException("This request isn't waiting for you");
		}
		String note = LeaveSupport.text(b == null ? null : b.get("note"));
		Map<String, Object> me = support.person(currentUser.requireEmployeeId());
		jdbc.sql("update leave_request_step set state = 'APPROVED', acted_at = now(), note = :n where id = :id").param("n", onBehalf(cur, me, note)).param("id", cur.id()).update();
		Step next = steps(id).stream().filter(s -> "WAITING".equals(s.state())).findFirst().orElse(null);
		if (next != null) {
			jdbc.sql("update leave_request_step set state = 'PENDING' where id = :id").param("id", next.id()).update();
			jdbc.sql("update leave_request set version = version + 1 where id = :id").param("id", id).update();
			event(id, "STEP_APPROVED", me.get("name") + " approved" + (note == null ? "" : " · " + note));
			notifyApprover(r, next.approverId());
		}
		else {
			finishApproval(r, note);
			event(id, "APPROVED", me.get("name") + " approved" + (note == null ? "" : " · " + note));
		}
		audit.publish("leave", "LeaveRequest", id, "APPROVE", null, note);
		return detail(id);
	}

	/** When HR acts on a step that is someone else's, the step says so. */
	private String onBehalf(Step cur, Map<String, Object> me, String note) {
		if (cur.approverId().equals(currentUser.employeeId())) {
			return note;
		}
		String by = "By " + me.get("name") + " on their behalf";
		return note == null ? by : by + " · " + note;
	}

	/** Marks the request approved and posts the days to the ledger, split by year when it spans two. */
	private void finishApproval(Req r, String note) {
		jdbc.sql("update leave_request set status = 'APPROVED', decided_at = now(), decided_by = :by, decision_note = :n, version = version + 1 where id = :id")
				.param("by", currentUser.employeeId(), java.sql.Types.OTHER).param("n", note).param("id", r.id()).update();
		LeaveTypeView t = policy.activeTypes().stream().filter(x -> x.id().equals(r.typeId())).findFirst().orElse(null);
		String label = LeaveSupport.requestNo(r.no()) + " · " + range(r.from(), r.to());
		if (r.from().getYear() == r.to().getYear() || t == null) {
			ledger.post(r.employeeId(), r.typeId(), r.from().getYear(), "USED", r.days().negate(), null, r.id(), label);
		}
		else {
			for (int y = r.from().getYear(); y <= r.to().getYear(); y++) {
				LocalDate a = y == r.from().getYear() ? r.from() : LocalDate.of(y, 1, 1);
				LocalDate z = y == r.to().getYear() ? r.to() : LocalDate.of(y, 12, 31);
				BigDecimal d = policy.countDays(r.employeeId(), t, a, z, false);
				if (d.signum() > 0) {
					ledger.post(r.employeeId(), r.typeId(), y, "USED", d.negate(), null, r.id(), label);
				}
			}
		}
		Type type = support.typesById().get(r.typeId());
		notifier.inApp(List.of(r.employeeId()), "LEAVE_REQUEST", "Your " + (type == null ? "leave" : type.name().toLowerCase(Locale.ROOT)) + " was approved",
				range(r.from(), r.to()) + " · " + dd(r.days()), "/me/profile");
	}

	@Transactional
	public Map<String, Object> reject(UUID id, Map<String, Object> b) {
		Req r = load(id);
		Step cur = current(id);
		if (!canDecide(r, cur)) {
			throw new ForbiddenException("This request isn't waiting for you");
		}
		String note = LeaveSupport.text(b == null ? null : b.get("note"));
		if (note == null) {
			throw problem("note", "REASON_REQUIRED", "Add a reason so they know why");
		}
		jdbc.sql("update leave_request_step set state = 'REJECTED', acted_at = now(), note = :n where id = :id")
				.param("n", onBehalf(cur, support.person(currentUser.requireEmployeeId()), note)).param("id", cur.id()).update();
		jdbc.sql("update leave_request_step set state = 'SKIPPED' where request_id = :r and state = 'WAITING'").param("r", id).update();
		jdbc.sql("update leave_request set status = 'REJECTED', decided_at = now(), decided_by = :by, decision_note = :n, version = version + 1 where id = :id")
				.param("by", currentUser.employeeId(), java.sql.Types.OTHER).param("n", note).param("id", id).update();
		Map<String, Object> me = support.person(currentUser.requireEmployeeId());
		event(id, "REJECTED", me.get("name") + " rejected · " + note);
		notifier.inApp(List.of(r.employeeId()), "LEAVE_REQUEST", "Your leave request was declined", range(r.from(), r.to()) + " · " + note, "/me/profile");
		audit.publish("leave", "LeaveRequest", id, "REJECT", null, note);
		return detail(id);
	}

	/** The person withdraws their own request, or HR cancels one. Approved leave gives the days back. */
	@Transactional
	public Map<String, Object> cancel(UUID id, Map<String, Object> b) {
		Req r = load(id);
		UUID me = currentUser.requireEmployeeId();
		boolean own = me.equals(r.employeeId());
		if (!own && !admin()) {
			throw new ForbiddenException("You can only cancel your own leave");
		}
		if (!"PENDING".equals(r.status()) && !"APPROVED".equals(r.status())) {
			throw problem("status", "NOT_CANCELLABLE", "Only pending or approved leave can be cancelled");
		}
		if (own && !admin() && "APPROVED".equals(r.status()) && r.from().isBefore(LocalDate.now())) {
			throw problem("status", "STARTED", "Leave that has started can only be cancelled by HR");
		}
		String note = LeaveSupport.text(b == null ? null : b.get("note"));
		if ("APPROVED".equals(r.status())) {
			jdbc.sql("select year, sum(days) from leave_ledger where ref_id = :r and kind = 'USED' group by year").param("r", id)
					.query((rs, i) -> Map.entry(rs.getInt(1), rs.getBigDecimal(2))).list()
					.forEach(e -> ledger.post(r.employeeId(), r.typeId(), e.getKey(), "REVERSAL", e.getValue().negate(), null, id,
							LeaveSupport.requestNo(r.no()) + " cancelled"));
		}
		jdbc.sql("update leave_request_step set state = 'SKIPPED' where request_id = :r and state in ('WAITING', 'PENDING')").param("r", id).update();
		jdbc.sql("update leave_request set status = 'CANCELLED', decided_at = now(), decided_by = :by, decision_note = :n, version = version + 1 where id = :id")
				.param("by", me).param("n", note).param("id", id).update();
		event(id, "CANCELLED", (own ? "Withdrawn" : "Cancelled by " + support.person(me).get("name")) + (note == null ? "" : " · " + note));
		if (!own) {
			notifier.inApp(List.of(r.employeeId()), "LEAVE_REQUEST", "Your leave was cancelled by HR", range(r.from(), r.to()) + (note == null ? "" : " · " + note), "/me/profile");
		}
		audit.publish("leave", "LeaveRequest", id, "CANCEL", null, note);
		return detail(id);
	}

	@Transactional
	public Map<String, Object> note(UUID id, Map<String, Object> b) {
		load(id);
		if (!admin()) {
			throw new ForbiddenException("Only HR can add internal notes");
		}
		String text = LeaveSupport.text(b == null ? null : b.get("text"));
		if (text == null) {
			throw problem("text", "REQUIRED", "Write the note");
		}
		event(id, "NOTE", text);
		return detail(id);
	}

	@Transactional
	public Map<String, Object> remind(UUID id) {
		Req r = load(id);
		Step cur = current(id);
		if (cur == null || !"PENDING".equals(r.status())) {
			throw problem("status", "NOT_PENDING", "Nothing is waiting for approval");
		}
		if (!admin() && !currentUser.requireEmployeeId().equals(r.employeeId())) {
			throw new ForbiddenException("You can't send reminders for this request");
		}
		notifyApprover(r, cur.approverId());
		event(id, "REMINDED", "Reminder sent to " + support.person(cur.approverId()).get("name"));
		return detail(id);
	}

	// ================================================================== reading

	private boolean canSee(Req r) {
		UUID me = currentUser.employeeId();
		return me != null && me.equals(r.employeeId()) || access.has("leave", "VIEW") || me != null && steps(r.id()).stream().anyMatch(s -> me.equals(s.approverId()));
	}

	@Transactional
	public Map<String, Object> detail(UUID id) {
		Req r = load(id);
		if (!canSee(r)) {
			throw new ForbiddenException("You can't see this request");
		}
		List<Step> steps = steps(id);
		Step cur = steps.stream().filter(s -> "PENDING".equals(s.state())).findFirst().orElse(null);
		Set<UUID> ids = new HashSet<>();
		ids.add(r.employeeId());
		steps.forEach(s -> ids.add(s.approverId()));
		Map<UUID, Map<String, Object>> people = support.people(ids);
		Map<String, Object> out = row(r, steps, people);
		Map<String, Object> person = people.get(r.employeeId());
		out.put("reason", r.reason());
		out.put("fields", fields.read("leave", id, null));
		out.put("hasDocument", r.hasDocument());
		out.put("decisionNote", r.note());
		out.put("decidedAt", r.decidedAt());
		out.put("steps", steps.stream().map(s -> {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("sort", s.sort());
			m.put("role", s.approver());
			m.put("person", people.get(s.approverId()));
			m.put("state", s.state());
			m.put("actedAt", s.actedAt());
			m.put("note", s.note());
			m.put("delegated", s.delegatedFrom() != null);
			return m;
		}).toList());
		List<Map<String, Object>> events = jdbc.sql("select at, actor_id, kind, text from leave_request_event where request_id = :r order by at").param("r", id)
				.query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("at", rs.getObject(1, OffsetDateTime.class));
					m.put("actorId", rs.getObject(2, UUID.class));
					m.put("kind", rs.getString(3));
					m.put("text", rs.getString(4));
					return m;
				}).list();
		Map<UUID, Map<String, Object>> actors = support.people(events.stream().map(e -> (UUID) e.get("actorId")).toList());
		events.forEach(e -> e.put("actor", actors.get(e.get("actorId"))));
		boolean seeNotes = admin();
		out.put("events", events.stream().filter(e -> seeNotes || !"NOTE".equals(e.get("kind"))).toList());
		// Overlaps with teammates (same department) who are off at the same time.
		Object dept = person == null ? null : ((Map<?, ?>) person.getOrDefault("department", Map.of())).get("id");
		List<String> overlap = dept == null ? List.of()
				: jdbc.sql("""
						select e.display_name from leave_request r join employee e on e.id = r.employee_id
						 where r.org_id = :org and r.status in ('PENDING', 'APPROVED') and r.id <> :id and e.department_id = :d
						   and r.from_date <= :to and r.to_date >= :from order by e.display_name limit 5""").param("org", currentUser.orgId()).param("id", id)
						.param("d", UUID.fromString(dept.toString())).param("from", r.from()).param("to", r.to()).query(String.class).list();
		out.put("overlap", overlap);
		int year = r.from().getYear();
		ledger.ensureCredits(r.employeeId(), year);
		out.put("balances", ledger.lines(r.employeeId(), year).stream().filter(l -> l.type().active()).map(LeaveLedgerService::balanceView).toList());
		out.put("past", jdbc.sql(REQ_SQL + " where employee_id = :e and id <> :id order by from_date desc limit 12").param("e", r.employeeId()).param("id", id)
				.query((rs, i) -> req(rs)).list().stream().map(p -> row(p, List.of(), Map.of())).toList());
		UUID me = currentUser.employeeId();
		Map<String, Object> can = new LinkedHashMap<>();
		can.put("decide", canDecide(r, cur));
		can.put("cancel", ("PENDING".equals(r.status()) || "APPROVED".equals(r.status())) && (admin() || me != null && me.equals(r.employeeId())));
		can.put("note", admin());
		can.put("remind", cur != null && (admin() || me != null && me.equals(r.employeeId())));
		out.put("can", can);
		return out;
	}

	private Map<String, Object> row(Req r, List<Step> steps, Map<UUID, Map<String, Object>> people) {
		Map<String, Object> m = new LinkedHashMap<>();
		Type t = support.typesById().get(r.typeId());
		m.put("id", r.id());
		m.put("no", LeaveSupport.requestNo(r.no()));
		m.put("employee", people.get(r.employeeId()));
		m.put("type", t == null ? null : LeaveSupport.typeRef(t));
		m.put("from", r.from());
		m.put("to", r.to());
		m.put("half", r.half());
		m.put("days", days(r.days()));
		m.put("status", r.status());
		m.put("appliedAt", r.appliedAt());
		if (!steps.isEmpty()) {
			long done = steps.stream().filter(s -> "APPROVED".equals(s.state())).count();
			m.put("progress", Map.of("done", done, "total", steps.size(), "states", steps.stream().map(Step::state).toList()));
			Step cur = steps.stream().filter(s -> "PENDING".equals(s.state())).findFirst().orElse(null);
			if (cur != null) {
				m.put("waitingOn", people.get(cur.approverId()));
				m.put("waitingRole", cur.approver());
			}
		}
		return m;
	}

	/**
	 * The Requests tab. {@code view}: pending (waiting for approval; for an approver only theirs, for HR everyone's) or history (decided).
	 * {@code act} narrows history to approved or rejected.
	 */
	@Transactional(readOnly = true)
	public Map<String, Object> list(String view, String act, String q, List<String> types, UUID dept, LocalDate from, LocalDate to, String sort, int page, int size) {
		boolean history = "history".equalsIgnoreCase(view);
		boolean hr = access.has("leave", "VIEW");
		UUID me = currentUser.requireEmployeeId();
		StringBuilder w = new StringBuilder(" where r.org_id = :org");
		Map<String, Object> p = new HashMap<>();
		p.put("org", currentUser.orgId());
		p.put("me", me);
		if (!hr) {
			w.append(" and exists (select 1 from leave_request_step s where s.request_id = r.id and s.approver_emp_id = :me")
					.append(history ? ")" : " and s.state = 'PENDING')");
		}
		String statusFilter = history ? (act == null || act.isBlank() || "ALL".equalsIgnoreCase(act) ? " and r.status in ('APPROVED', 'REJECTED')"
				: " and r.status = '" + ("REJECTED".equalsIgnoreCase(act) ? "REJECTED" : "APPROVED") + "'") : " and r.status = 'PENDING'";
		StringBuilder f = new StringBuilder();
		if (q != null && !q.isBlank()) {
			f.append(" and (lower(e.display_name) like :q or lower(e.employee_code) like :q or lower(coalesce(dep.name,'')) like :q or lower(r.reason) like :q or 'lr-' || lpad(r.no::text, 4, '0') like :q or lower(t.name) like :q)");
			p.put("q", "%" + q.trim().toLowerCase() + "%");
		}
		if (types != null && !types.isEmpty()) {
			f.append(" and t.code in (:types)");
			p.put("types", types);
		}
		if (dept != null) {
			f.append(" and e.department_id = :dept");
			p.put("dept", dept);
		}
		if (history) {
			if (from != null) {
				f.append(" and r.decided_at >= :from");
				p.put("from", from.atStartOfDay().atOffset(java.time.ZoneOffset.UTC));
			}
			if (to != null) {
				f.append(" and r.decided_at < :to");
				p.put("to", to.plusDays(1).atStartOfDay().atOffset(java.time.ZoneOffset.UTC));
			}
		}
		else {
			if (from != null) {
				f.append(" and r.to_date >= :from");
				p.put("from", from);
			}
			if (to != null) {
				f.append(" and r.from_date <= :to");
				p.put("to", to);
			}
		}
		String order = history ? " order by r.decided_at desc nulls last" : switch (sort == null ? "" : sort) {
			case "start" -> " order by r.from_date, r.applied_at";
			case "days" -> " order by r.days desc, r.applied_at desc";
			case "name" -> " order by lower(e.display_name)";
			default -> " order by r.applied_at desc";
		};
		String from_ = " from leave_request r join employee e on e.id = r.employee_id left join department dep on dep.id = e.department_id join leave_type t on t.id = r.type_id";
		long total = jdbc.sql("select count(*)" + from_ + w + statusFilter + f).params(p).query(Long.class).single();
		int sz = Math.max(1, Math.min(size <= 0 ? 10 : size, 100));
		List<Req> reqs = jdbc.sql("select r.id, r.no, r.employee_id, r.type_id, r.from_date, r.to_date, r.half_day, r.days, r.reason, r.has_document, r.status, r.applied_at, r.applied_by, r.decided_at, r.decision_note"
				+ from_ + w + statusFilter + f + order + " limit " + sz + " offset " + (long) Math.max(0, page) * sz).params(p).query((rs, i) -> req(rs)).list();
		Map<UUID, List<Step>> stepsBy = new HashMap<>();
		Set<UUID> ids = new HashSet<>();
		for (Req r : reqs) {
			List<Step> s = steps(r.id());
			stepsBy.put(r.id(), s);
			ids.add(r.employeeId());
			s.forEach(x -> ids.add(x.approverId()));
		}
		Map<UUID, Map<String, Object>> people = support.people(ids);
		List<Map<String, Object>> items = new ArrayList<>();
		for (Req r : reqs) {
			Map<String, Object> m = row(r, stepsBy.get(r.id()), people);
			m.put("reason", r.reason());
			if (history) {
				List<Step> s = stepsBy.get(r.id());
				Step last = "REJECTED".equals(r.status()) ? s.stream().filter(x -> "REJECTED".equals(x.state())).findFirst().orElse(null)
						: s.isEmpty() ? null : s.get(s.size() - 1);
				m.put("decidedAt", r.decidedAt());
				m.put("decidedBy", last == null ? null : people.get(last.approverId()));
				m.put("decisionNote", r.note());
				m.put("approvedBy", s.stream().filter(x -> "APPROVED".equals(x.state())).map(x -> people.get(x.approverId())).toList());
			}
			items.add(m);
		}
		// Counts for the view switch and the history chips.
		String base = "select count(*)" + from_ + w;
		Map<String, Object> counts = new LinkedHashMap<>();
		if (!hr) {
			counts.put("pending", jdbc.sql("select count(*)" + from_ + " where r.org_id = :org and r.status = 'PENDING' and exists (select 1 from leave_request_step s where s.request_id = r.id and s.approver_emp_id = :me and s.state = 'PENDING')")
					.param("org", currentUser.orgId()).param("me", me).query(Long.class).single());
		}
		else {
			counts.put("pending", jdbc.sql(base + " and r.status = 'PENDING'").params(Map.of("org", currentUser.orgId(), "me", me)).query(Long.class).single());
		}
		Map<String, Object> hp = new HashMap<>(Map.of("org", currentUser.orgId(), "me", me));
		String hw = hr ? " where r.org_id = :org" : " where r.org_id = :org and exists (select 1 from leave_request_step s where s.request_id = r.id and s.approver_emp_id = :me)";
		counts.put("approved", jdbc.sql("select count(*)" + from_ + hw + " and r.status = 'APPROVED'").params(hp).query(Long.class).single());
		counts.put("rejected", jdbc.sql("select count(*)" + from_ + hw + " and r.status = 'REJECTED'").params(hp).query(Long.class).single());
		counts.put("mine", jdbc.sql("select count(*) from leave_request r where r.org_id = :org and r.status = 'PENDING' and exists (select 1 from leave_request_step s where s.request_id = r.id and s.approver_emp_id = :me and s.state = 'PENDING')")
				.param("org", currentUser.orgId()).param("me", me).query(Long.class).single());
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("items", items);
		out.put("total", total);
		out.put("page", page);
		out.put("size", sz);
		out.put("counts", counts);
		out.put("types", support.types().stream().filter(Type::active).map(LeaveSupport::typeRef).toList());
		out.put("canApplyForOthers", access.has("leave", "EDIT"));
		out.put("canManage", admin());
		return out;
	}

	@Override
	@Transactional
	public Map<String, Object> forEmployee(UUID employeeId, int year, boolean canApply) {
		Map<String, Object> out = new LinkedHashMap<>(mine(employeeId, year));
		out.put("canApply", canApply);
		return out;
	}

	/** The person's own requests and balances (the employee view, and the profile's Leave tab). */
	@Transactional
	public Map<String, Object> mine(UUID employeeId, int year) {
		UUID me = currentUser.requireEmployeeId();
		UUID emp = employeeId == null ? me : employeeId;
		if (!emp.equals(me) && !access.has("leave", "VIEW")) {
			throw new ForbiddenException("You can't see this person's leave");
		}
		ledger.ensureCredits(emp, year);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("year", year);
		out.put("balances", ledger.lines(emp, year).stream().filter(l -> l.type().active()).map(LeaveLedgerService::balanceView).toList());
		List<Req> reqs = jdbc.sql(REQ_SQL + " where employee_id = :e and (extract(year from from_date) = :y or status = 'PENDING') order by from_date desc").param("e", emp)
				.param("y", year).query((rs, i) -> req(rs)).list();
		Set<UUID> ids = new HashSet<>();
		Map<UUID, List<Step>> st = new HashMap<>();
		for (Req r : reqs) {
			st.put(r.id(), steps(r.id()));
			ids.add(r.employeeId());
			st.get(r.id()).forEach(s -> ids.add(s.approverId()));
		}
		Map<UUID, Map<String, Object>> people = support.people(ids);
		out.put("requests", reqs.stream().map(r -> {
			Map<String, Object> m = row(r, st.get(r.id()), people);
			m.put("reason", r.reason());
			return m;
		}).toList());
		return out;
	}
}
