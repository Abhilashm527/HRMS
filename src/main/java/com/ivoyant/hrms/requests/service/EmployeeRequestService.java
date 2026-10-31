package com.ivoyant.hrms.requests.service;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.security.ModuleAccess;
import com.ivoyant.hrms.common.storage.FileStore;
import com.ivoyant.hrms.common.storage.VirusScanner;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.ForbiddenException;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.common.web.StatusException;
import com.ivoyant.hrms.common.web.UnprocessableException;
import com.ivoyant.hrms.notification.Notifier;
import com.ivoyant.hrms.requests.RequestTypeUsage;
import com.ivoyant.hrms.requests.service.LetterPdf.Line;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Letters, reimbursements and helpdesk tickets raised from self-service, and the queues that act on them:
 * <ul>
 * <li>a letter waits for HR, who issue it (a standard letter generated here, or their own signed PDF) or decline it;</li>
 * <li>a reimbursement goes to the employee's manager, then to Finance, who approve the payout (or anyone along the way rejects it);</li>
 * <li>a helpdesk ticket goes to the team set on its type (HR can always help), who mark it resolved.</li>
 * </ul>
 * HR means the requests module's Approve level (HR and super admins always have it); Finance means a Finance role or payroll Approve.
 * Nobody acts on their own request.
 */
@Service
public class EmployeeRequestService implements RequestTypeUsage {

	static final int MAX_BYTES = 10 * 1024 * 1024;
	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
	private static final DateTimeFormatter DMY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
	private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

	/** Who is acting and which queues they work. */
	record Actor(UUID org, UUID id, String name, String title, boolean hr, boolean finance, Set<UUID> roleIds) {
	}

	record Req(UUID id, long no, UUID employeeId, String kind, UUID typeId, String typeName, UUID teamRole, String title, String note, BigDecimal amount,
			LocalDate expenseDate, String receiptKey, String receiptName, String receiptMime, String letterKey, String letterName, String letterMime,
			String stage, String status, UUID managerId, String managerName, UUID decidedBy, String decidedByName, OffsetDateTime decidedAt,
			String decisionNote, OffsetDateTime createdAt, Map<String, Object> employee) {

		String ref() {
			return "RQ-" + no;
		}

		boolean open() {
			return "PENDING".equals(status) || "OPEN".equals(status);
		}
	}

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final ModuleAccess access;
	private final FileStore store;
	private final VirusScanner scanner;
	private final Notifier notifier;
	private final Audit audit;

	public EmployeeRequestService(JdbcClient jdbc, CurrentUser currentUser, ModuleAccess access, FileStore store, VirusScanner scanner, Notifier notifier,
			Audit audit) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.access = access;
		this.store = store;
		this.scanner = scanner;
		this.notifier = notifier;
		this.audit = audit;
	}

	@Override
	public long requestsOfType(UUID typeId) {
		return jdbc.sql("select count(*) from employee_request where type_id = :t").param("t", typeId).query(Long.class).single();
	}

	// ---------------------------------------------------------------------------------------------------------------- who

	Actor actor() {
		UUID me = currentUser.requireEmployeeId();
		boolean hr = access.has("requests", "APPROVE");
		Map<String, Object> row = jdbc.sql("""
				select e.display_name, jt.name as title,
				       array(select distinct ur.role_id::text from app_user u join user_role ur on ur.user_id = u.id where u.employee_id = e.id and u.status = 'ACTIVE') as roles,
				       exists (select 1 from app_user u join user_role ur on ur.user_id = u.id join role r on r.id = ur.role_id
				                where u.employee_id = e.id and u.status = 'ACTIVE' and r.key = 'finance') as fin
				  from employee e left join job_title jt on jt.id = e.title_id where e.id = :me""").param("me", me).query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("name", rs.getString(1));
					m.put("title", rs.getString(2));
					Set<UUID> ids = new HashSet<>();
					for (String s : (String[]) rs.getArray(3).getArray()) {
						ids.add(UUID.fromString(s));
					}
					m.put("roles", ids);
					m.put("fin", rs.getBoolean(4));
					return m;
				}).optional().orElseThrow(() -> new NotFoundException("Employee not found"));
		@SuppressWarnings("unchecked")
		Set<UUID> roles = (Set<UUID>) row.get("roles");
		boolean finance = hr || Boolean.TRUE.equals(row.get("fin")) || access.has("payroll", "APPROVE");
		return new Actor(currentUser.orgId(), me, (String) row.get("name"), (String) row.get("title"), hr, finance, roles);
	}

	/** Can this person move the request on (approve, pay, issue, resolve or reject)? */
	static boolean canAct(Req r, Actor a) {
		if (!r.open() || r.employeeId().equals(a.id())) {
			return false;
		}
		return switch (r.stage()) {
			case "MANAGER" -> a.id().equals(r.managerId()) || a.hr();
			case "FINANCE" -> a.finance();
			case "HR" -> a.hr();
			case "TEAM" -> a.hr() || (r.teamRole() != null && a.roleIds().contains(r.teamRole()));
			default -> false;
		};
	}

	static boolean canSee(Req r, Actor a) {
		return r.employeeId().equals(a.id()) || a.hr() || a.id().equals(r.managerId()) || a.id().equals(r.decidedBy())
				|| ("REIMBURSEMENT".equals(r.kind()) && a.finance()) || (r.teamRole() != null && a.roleIds().contains(r.teamRole()));
	}

	// ---------------------------------------------------------------------------------------------------------------- reading

	private static final String SQL = """
			select r.*, t.name as type_name, t.helpdesk_team_role_id as team_role,
			       e.display_name, e.first_name, e.last_name, e.employee_code, jt.name as emp_title, d.name as dept,
			       m.display_name as manager_name, db.display_name as decided_by_name
			  from employee_request r
			  join request_type t on t.id = r.type_id
			  join employee e on e.id = r.employee_id
			  left join job_title jt on jt.id = e.title_id
			  left join department d on d.id = e.department_id
			  left join employee m on m.id = r.manager_id
			  left join employee db on db.id = r.decided_by
			""";

	private static Req req(ResultSet rs) throws SQLException {
		Map<String, Object> emp = new LinkedHashMap<>();
		emp.put("id", rs.getObject("employee_id", UUID.class));
		emp.put("name", rs.getString("display_name"));
		emp.put("first", rs.getString("first_name"));
		String f = rs.getString("first_name");
		String l = rs.getString("last_name");
		emp.put("initials", ((f == null || f.isEmpty() ? "" : f.substring(0, 1)) + (l == null || l.isEmpty() ? "" : l.substring(0, 1))).toUpperCase());
		emp.put("code", rs.getString("employee_code"));
		emp.put("title", rs.getString("emp_title"));
		emp.put("department", rs.getString("dept"));
		return new Req(rs.getObject("id", UUID.class), rs.getLong("no"), rs.getObject("employee_id", UUID.class), rs.getString("kind"),
				rs.getObject("type_id", UUID.class), rs.getString("type_name"), rs.getObject("team_role", UUID.class), rs.getString("title"),
				rs.getString("note"), rs.getBigDecimal("amount"), rs.getObject("expense_date", LocalDate.class), rs.getString("receipt_key"),
				rs.getString("receipt_name"), rs.getString("receipt_mime"), rs.getString("letter_key"), rs.getString("letter_name"),
				rs.getString("letter_mime"), rs.getString("stage"), rs.getString("status"), rs.getObject("manager_id", UUID.class),
				rs.getString("manager_name"), rs.getObject("decided_by", UUID.class), rs.getString("decided_by_name"),
				rs.getObject("decided_at", OffsetDateTime.class), rs.getString("decision_note"), rs.getObject("created_at", OffsetDateTime.class), emp);
	}

	private Req load(UUID org, UUID id) {
		return jdbc.sql(SQL + " where r.id = :id and r.org_id = :org").param("id", id).param("org", org).query((rs, i) -> req(rs)).optional()
				.orElseThrow(() -> new NotFoundException("Request not found"));
	}

	String stageLabel(Req r) {
		if (!r.open()) {
			return "";
		}
		return switch (r.stage()) {
			case "MANAGER" -> "With " + (r.managerName() == null ? "manager" : r.managerName());
			case "FINANCE" -> "With finance";
			case "TEAM" -> "With " + r.typeName() + " team";
			default -> "With HR";
		};
	}

	Map<String, Object> view(Req r, Actor a) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", r.id());
		m.put("ref", r.ref());
		m.put("kind", r.kind());
		m.put("type", Map.of("id", r.typeId(), "name", r.typeName()));
		m.put("title", "HELPDESK".equals(r.kind()) ? r.title() : r.typeName());
		m.put("subject", r.title());
		m.put("note", r.note());
		m.put("amount", r.amount());
		m.put("expenseDate", r.expenseDate());
		m.put("receipt", r.receiptName());
		m.put("letter", r.letterName());
		m.put("stage", r.stage());
		m.put("stageLabel", stageLabel(r));
		m.put("status", r.status());
		m.put("createdAt", r.createdAt());
		m.put("decidedAt", r.decidedAt());
		m.put("decidedBy", r.decidedByName());
		m.put("decisionNote", r.decisionNote());
		m.put("employee", r.employee());
		m.put("manager", r.managerName());
		boolean mine = r.employeeId().equals(a.id());
		m.put("mine", mine);
		m.put("canAct", canAct(r, a));
		m.put("canWithdraw", mine && r.open() && !"FINANCE".equals(r.stage()));
		m.put("action", !canAct(r, a) ? null : switch (r.kind()) {
			case "LETTER" -> "ISSUE";
			case "HELPDESK" -> "RESOLVE";
			default -> "FINANCE".equals(r.stage()) ? "PAY" : "APPROVE";
		});
		return m;
	}

	/** The caller's own requests of one kind (all kinds when blank), newest first, with open counts per kind. */
	@Transactional(readOnly = true)
	public Map<String, Object> mine(String kind) {
		Actor a = actor();
		String k = kind == null || kind.isBlank() ? null : kind.toUpperCase();
		List<Map<String, Object>> items = jdbc.sql(SQL + " where r.org_id = :org and r.employee_id = :me" + (k == null ? "" : " and r.kind = :k")
				+ " order by r.created_at desc").param("org", a.org()).param("me", a.id()).param("k", k == null ? "" : k).query((rs, i) -> req(rs)).list()
				.stream().map(r -> view(r, a)).toList();
		Map<String, Long> open = new LinkedHashMap<>(Map.of("LETTER", 0L, "REIMBURSEMENT", 0L, "HELPDESK", 0L));
		jdbc.sql("select kind, count(*) from employee_request where employee_id = :me and status in ('PENDING', 'OPEN') group by kind").param("me", a.id())
				.query((rs, i) -> open.put(rs.getString(1), rs.getLong(2))).list();
		return Map.of("items", items, "open", open);
	}

	/**
	 * A queue for the caller: {@code LETTER, REIMBURSEMENT, HELPDESK} — open requests they can act on — or, with {@code closed}, the
	 * last 100 decided ones they may see. Counts cover every queue (tax declarations included) so the tabs can show them.
	 */
	@Transactional(readOnly = true)
	public Map<String, Object> inbox(String queue, boolean closed) {
		Actor a = actor();
		String k = queue == null ? "LETTER" : queue.toUpperCase();
		List<Req> open = jdbc.sql(SQL + " where r.org_id = :org and r.employee_id <> :me and r.status in ('PENDING', 'OPEN') order by r.created_at")
				.param("org", a.org()).param("me", a.id()).query((rs, i) -> req(rs)).list();
		Map<String, Long> counts = new LinkedHashMap<>(Map.of("LETTER", 0L, "REIMBURSEMENT", 0L, "HELPDESK", 0L));
		List<Map<String, Object>> items = new ArrayList<>();
		for (Req r : open) {
			if (canAct(r, a)) {
				counts.merge(r.kind(), 1L, Long::sum);
				if (!closed && r.kind().equals(k) && items.size() < 200) {
					items.add(view(r, a));
				}
			}
		}
		if (closed) {
			for (Req r : jdbc.sql(SQL + """
					 where r.org_id = :org and r.employee_id <> :me and r.kind = :k and r.status not in ('PENDING', 'OPEN')
					 order by coalesce(r.decided_at, r.updated_at) desc limit 500""").param("org", a.org()).param("me", a.id()).param("k", k)
					.query((rs, i) -> req(rs)).list()) {
				if (canSee(r, a) && items.size() < 100) {
					items.add(view(r, a));
				}
			}
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("items", items);
		out.put("counts", counts);
		out.put("hr", a.hr());
		out.put("finance", a.finance());
		return out;
	}

	@Transactional(readOnly = true)
	public Map<String, Object> detail(UUID id) {
		Actor a = actor();
		Req r = load(a.org(), id);
		if (!canSee(r, a)) {
			throw new NotFoundException("Request not found");
		}
		Map<String, Object> m = view(r, a);
		m.put("events", jdbc.sql("""
				select v.at, v.action, v.note, coalesce(e.display_name, 'System') from employee_request_event v left join employee e on e.id = v.by
				 where v.request_id = :id order by v.at""").param("id", id)
				.query((rs, i) -> {
					Map<String, Object> e = new LinkedHashMap<>();
					e.put("at", rs.getObject(1, OffsetDateTime.class));
					e.put("action", rs.getString(2));
					e.put("note", rs.getString(3));
					e.put("by", rs.getString(4));
					return e;
				}).list());
		return m;
	}

	// ---------------------------------------------------------------------------------------------------------------- submit

	/** A new request. Reimbursements need an amount, the expense date and a receipt (PDF, JPG or PNG up to 10 MB). */
	@Transactional
	public Map<String, Object> submit(String kind, UUID typeId, String title, String note, String amount, String expenseDate, String fileName, byte[] file) {
		Actor a = actor();
		String k = kind == null ? "" : kind.trim().toUpperCase();
		List<ErrorItem> errors = new ArrayList<>();
		if (!Set.of("LETTER", "REIMBURSEMENT", "HELPDESK").contains(k)) {
			throw new UnprocessableException(List.of(ErrorItem.field("kind", "INVALID", "Pick letter, reimbursement or helpdesk")));
		}
		String typeName = typeId == null ? null : jdbc.sql("select name from request_type where id = :t and org_id = :org and active and category = :c")
				.param("t", typeId).param("org", a.org()).param("c", k).query(String.class).optional().orElse(null);
		if (typeName == null) {
			errors.add(ErrorItem.field("typeId", "TYPE", "Pick a type"));
		}
		String n = note == null ? "" : note.trim();
		String t = title == null ? null : title.trim();
		BigDecimal amt = null;
		LocalDate date = null;
		switch (k) {
			case "LETTER" -> {
				if (n.length() < 3) {
					errors.add(ErrorItem.field("note", "REQUIRED", "Add the purpose of the letter"));
				}
			}
			case "HELPDESK" -> {
				if (t == null || t.length() < 3) {
					errors.add(ErrorItem.field("title", "REQUIRED", "Add a subject"));
				}
			}
			default -> {
				try {
					amt = new BigDecimal(amount == null ? "" : amount.replace(",", "").trim());
					if (amt.signum() <= 0 || amt.compareTo(new BigDecimal("10000000")) >= 0) {
						throw new NumberFormatException();
					}
				}
				catch (NumberFormatException e) {
					errors.add(ErrorItem.field("amount", "REQUIRED", "Enter the amount"));
				}
				try {
					date = LocalDate.parse(expenseDate == null ? "" : expenseDate.trim());
					if (date.isAfter(LocalDate.now(IST))) {
						errors.add(ErrorItem.field("expenseDate", "FUTURE", "The expense date can’t be in the future"));
					}
				}
				catch (RuntimeException e) {
					errors.add(ErrorItem.field("expenseDate", "REQUIRED", "Choose the expense date"));
				}
				if (file == null || file.length == 0) {
					errors.add(ErrorItem.field("file", "REQUIRED", "Attach a receipt"));
				}
			}
		}
		if (t != null && t.length() > 200) {
			errors.add(ErrorItem.field("title", "TOO_LONG", "Keep the subject under 200 characters"));
		}
		if (n.length() > 4000) {
			errors.add(ErrorItem.field("note", "TOO_LONG", "Keep the details under 4,000 characters"));
		}
		if (!errors.isEmpty()) {
			throw new UnprocessableException(errors);
		}
		UUID manager = null;
		String stage;
		if ("REIMBURSEMENT".equals(k)) {
			manager = jdbc.sql("""
					select m.id from employee e join employee m on m.id = e.manager_id
					 where e.id = :me and m.status <> 'EXITED' and m.id <> e.id""").param("me", a.id()).query(UUID.class).optional().orElse(null);
			stage = manager != null ? "MANAGER" : "FINANCE";
		}
		else {
			stage = "LETTER".equals(k) ? "HR" : "TEAM";
		}
		UUID id = UUID.randomUUID();
		String key = null;
		String mime = null;
		if (file != null && file.length > 0) {
			mime = checkFile(file);
			key = a.org() + "/requests/" + id + "/receipt";
			put(key, file);
		}
		long no = jdbc.sql("""
				insert into employee_request (id, org_id, employee_id, kind, type_id, title, note, amount, expense_date, receipt_key, receipt_name, receipt_mime,
				                              stage, status, manager_id)
				values (:id, :org, :me, :k, :t, :title, :note, :amt, :d, :key, :fname, :mime, :stage, :status, :mgr) returning no""")
				.param("id", id).param("org", a.org()).param("me", a.id()).param("k", k).param("t", typeId).param("title", t, java.sql.Types.VARCHAR)
				.param("note", n).param("amt", amt, java.sql.Types.NUMERIC).param("d", date, java.sql.Types.DATE).param("key", key, java.sql.Types.VARCHAR)
				.param("fname", key == null ? null : safeName(fileName, "receipt"), java.sql.Types.VARCHAR).param("mime", mime, java.sql.Types.VARCHAR)
				.param("stage", stage).param("status", "HELPDESK".equals(k) ? "OPEN" : "PENDING").param("mgr", manager, java.sql.Types.OTHER)
				.query(Long.class).single();
		event(id, a.id(), "HELPDESK".equals(k) ? "Opened" : "Submitted", null);
		audit.publish("requests", "EmployeeRequest", id, "SUBMITTED", null, Map.of("detail", "RQ-" + no + " · " + typeName));
		Req r = load(a.org(), id);
		String what = switch (k) {
			case "REIMBURSEMENT" -> "a " + typeName.toLowerCase() + " claim of " + inr(amt);
			case "LETTER" -> "a request for " + typeName.toLowerCase();
			default -> "a " + typeName + " ticket: " + t;
		};
		notifier.inApp(actorsFor(r), "REQUEST", a.name() + " submitted " + what, r.ref() + (n.isEmpty() ? "" : " · " + n), "/inbox?tab=" + tab(k));
		return view(r, a);
	}

	private static String tab(String kind) {
		return switch (kind) {
			case "LETTER" -> "letters";
			case "REIMBURSEMENT" -> "reimb";
			default -> "tickets";
		};
	}

	/** Everyone who can act on the request now. */
	private List<UUID> actorsFor(Req r) {
		String who = switch (r.stage()) {
			case "MANAGER" -> "select cast(:mgr as uuid)";
			case "FINANCE" -> "select u.employee_id from app_user u join user_role ur on ur.user_id = u.id join role ro on ro.id = ur.role_id "
					+ "where u.org_id = :org and u.status = 'ACTIVE' and ro.key in ('finance', 'hr_admin', 'super_admin')";
			case "TEAM" -> "select u.employee_id from app_user u join user_role ur on ur.user_id = u.id join role ro on ro.id = ur.role_id "
					+ "where u.org_id = :org and u.status = 'ACTIVE' and (ro.id = :team or ro.key in ('hr_admin', 'super_admin'))";
			default -> "select u.employee_id from app_user u join user_role ur on ur.user_id = u.id join role ro on ro.id = ur.role_id "
					+ "where u.org_id = :org and u.status = 'ACTIVE' and ro.key in ('hr_admin', 'super_admin')";
		};
		return jdbc.sql("select distinct x from (" + who + ") s(x) where x is not null and x <> :emp").param("org", jdbc.sql("select org_id from employee where id = :e")
				.param("e", r.employeeId()).query(UUID.class).single()).param("mgr", r.managerId(), java.sql.Types.OTHER).param("team", r.teamRole(), java.sql.Types.OTHER)
				.param("emp", r.employeeId()).query(UUID.class).list();
	}

	@Transactional
	public Map<String, Object> withdraw(UUID id) {
		Actor a = actor();
		Req r = load(a.org(), id);
		if (!r.employeeId().equals(a.id())) {
			throw new ForbiddenException("Only the person who raised it can withdraw a request");
		}
		if (!r.open() || "FINANCE".equals(r.stage())) {
			throw new UnprocessableException(List.of(ErrorItem.field(null, "NOT_WITHDRAWABLE",
					"FINANCE".equals(r.stage()) ? "Finance is already processing this claim" : "This request is already closed")));
		}
		close(r, a, "WITHDRAWN", "DONE", "Withdrawn", null);
		return view(load(a.org(), id), a);
	}

	// ---------------------------------------------------------------------------------------------------------------- act

	private Req actable(UUID id, Actor a) {
		Req r = load(a.org(), id);
		if (!canSee(r, a)) {
			throw new NotFoundException("Request not found");
		}
		if (!r.open()) {
			throw new UnprocessableException(List.of(ErrorItem.field(null, "CLOSED", "This request was already " + r.status().toLowerCase())));
		}
		if (!canAct(r, a)) {
			throw new ForbiddenException(r.employeeId().equals(a.id()) ? "You can’t decide your own request" : "This request is waiting on someone else");
		}
		return r;
	}

	/** Manager approval moves a claim to Finance; Finance approval approves the payout with the coming payroll. */
	@Transactional
	public Map<String, Object> approve(UUID id, String note) {
		Actor a = actor();
		Req r = actable(id, a);
		if (!"REIMBURSEMENT".equals(r.kind())) {
			throw new UnprocessableException(List.of(ErrorItem.field(null, "WRONG_ACTION", "Letters are issued and tickets resolved, not approved")));
		}
		if ("MANAGER".equals(r.stage())) {
			jdbc.sql("update employee_request set stage = 'FINANCE', updated_at = now(), version = version + 1 where id = :id").param("id", id).update();
			event(id, a.id(), "Approved by " + a.name(), clean(note));
			Req moved = load(a.org(), id);
			notifier.inApp(List.of(r.employeeId()), "REQUEST", "Your " + r.typeName().toLowerCase() + " claim moved to finance",
					r.ref() + " · " + inr(r.amount()) + " · approved by " + a.name(), "/me/requests?tab=reimb");
			notifier.inApp(actorsFor(moved), "REQUEST", "Claim ready for payout", r.ref() + " · " + r.employee().get("name") + " · " + inr(r.amount()),
					"/inbox?tab=reimb");
		}
		else {
			LocalDate today = LocalDate.now(IST);
			String month = MONTH.format(today.getDayOfMonth() <= 20 ? today : today.plusMonths(1));
			close(r, a, "PAID", "DONE", "Payout approved · paid with " + month + " payroll", clean(note));
			notifier.inApp(List.of(r.employeeId()), "REQUEST", "Your " + r.typeName().toLowerCase() + " claim was approved",
					r.ref() + " · " + inr(r.amount()) + " will be paid with " + month + " payroll", "/me/requests?tab=reimb");
		}
		audit.publish("requests", "EmployeeRequest", id, "APPROVED", null, Map.of("detail", r.ref()));
		return view(load(a.org(), id), a);
	}

	@Transactional
	public Map<String, Object> reject(UUID id, String note) {
		Actor a = actor();
		Req r = actable(id, a);
		String n = clean(note);
		if ("HELPDESK".equals(r.kind())) {
			throw new UnprocessableException(List.of(ErrorItem.field(null, "WRONG_ACTION", "Tickets are resolved, not rejected")));
		}
		close(r, a, "REJECTED", "DONE", ("LETTER".equals(r.kind()) ? "Declined by " : "Rejected by ") + a.name(), n);
		notifier.inApp(List.of(r.employeeId()), "REQUEST",
				"LETTER".equals(r.kind()) ? "Your " + r.typeName().toLowerCase() + " request was declined" : "Your " + r.typeName().toLowerCase() + " claim was rejected",
				r.ref() + (n == null ? "" : " · " + n), "/me/requests?tab=" + tab(r.kind()));
		audit.publish("requests", "EmployeeRequest", id, "REJECTED", null, Map.of("detail", r.ref()));
		return view(load(a.org(), id), a);
	}

	@Transactional
	public Map<String, Object> resolve(UUID id, String note) {
		Actor a = actor();
		Req r = actable(id, a);
		if (!"HELPDESK".equals(r.kind())) {
			throw new UnprocessableException(List.of(ErrorItem.field(null, "WRONG_ACTION", "Only tickets are resolved")));
		}
		String n = clean(note);
		close(r, a, "RESOLVED", "DONE", "Resolved by " + a.name(), n);
		notifier.inApp(List.of(r.employeeId()), "REQUEST", "Your ticket “" + r.title() + "” was resolved", r.ref() + (n == null ? "" : " · " + n),
				"/me/requests?tab=tickets");
		audit.publish("requests", "EmployeeRequest", id, "RESOLVED", null, Map.of("detail", r.ref()));
		return view(load(a.org(), id), a);
	}

	/** Issues a letter: the signed PDF HR uploads, or a standard letter generated from the employee's record. */
	@Transactional
	public Map<String, Object> issue(UUID id, String note, String fileName, byte[] file) {
		Actor a = actor();
		Req r = actable(id, a);
		if (!"LETTER".equals(r.kind())) {
			throw new UnprocessableException(List.of(ErrorItem.field(null, "WRONG_ACTION", "Only letters are issued")));
		}
		byte[] pdf;
		String name;
		String mime;
		if (file != null && file.length > 0) {
			mime = checkFile(file);
			pdf = file;
			name = safeName(fileName, r.typeName());
		}
		else {
			pdf = generate(r, a);
			mime = "application/pdf";
			name = r.typeName().replaceAll("[^A-Za-z0-9]+", "-").replaceAll("^-|-$", "") + "-" + r.employee().get("code") + ".pdf";
		}
		String key = a.org() + "/requests/" + id + "/letter";
		put(key, pdf);
		jdbc.sql("update employee_request set letter_key = :k, letter_name = :n, letter_mime = :m where id = :id").param("k", key).param("n", name)
				.param("m", mime).param("id", id).update();
		String n = clean(note);
		close(r, a, "ISSUED", "DONE", "Issued by " + a.name(), n);
		notifier.inApp(List.of(r.employeeId()), "REQUEST", "Your " + r.typeName().toLowerCase() + " is ready to download", r.ref() + (n == null ? "" : " · " + n),
				"/me/requests?tab=letters");
		audit.publish("requests", "EmployeeRequest", id, "ISSUED", null, Map.of("detail", r.ref()));
		return view(load(a.org(), id), a);
	}

	/** A preview of the standard letter, so HR can read it before issuing. */
	@Transactional(readOnly = true)
	public byte[] preview(UUID id) {
		Actor a = actor();
		Req r = load(a.org(), id);
		if (!"LETTER".equals(r.kind()) || !a.hr()) {
			throw new ForbiddenException("Only HR can preview letters");
		}
		return generate(r, a);
	}

	private byte[] generate(Req r, Actor a) {
		Map<String, Object> e = jdbc.sql("""
				select e.display_name, e.employee_code, e.joining_date, e.exit_date, e.status, jt.name as title, d.name as dept, l.name as loc,
				       coalesce(o.legal_name, o.name) as legal
				  from employee e join org o on o.id = e.org_id left join job_title jt on jt.id = e.title_id
				  left join department d on d.id = e.department_id left join location l on l.id = e.location_id where e.id = :e""")
				.param("e", r.employeeId()).query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("name", rs.getString("display_name"));
					m.put("code", rs.getString("employee_code"));
					m.put("joined", rs.getObject("joining_date", LocalDate.class));
					m.put("exit", rs.getObject("exit_date", LocalDate.class));
					m.put("exited", "EXITED".equals(rs.getString("status")));
					m.put("title", rs.getString("title") == null ? "a member of staff" : rs.getString("title"));
					m.put("dept", rs.getString("dept"));
					m.put("loc", rs.getString("loc"));
					m.put("legal", rs.getString("legal"));
					return m;
				}).single();
		String legal = (String) e.get("legal");
		String who = e.get("name") + " (Employee ID " + e.get("code") + ")";
		String joined = DMY.format((LocalDate) e.get("joined"));
		String role = e.get("title") + (e.get("dept") != null ? " in the " + e.get("dept") + " department" : "");
		String type = r.typeName().toLowerCase();
		List<Line> lines = new ArrayList<>();
		lines.add(Line.b(legal));
		if (e.get("loc") != null) {
			lines.add(Line.p(String.valueOf(e.get("loc"))));
		}
		lines.add(Line.gap());
		lines.add(Line.p("Date: " + DMY.format(LocalDate.now(IST))));
		lines.add(Line.p("Ref: " + r.ref()));
		lines.add(Line.gap());
		lines.add(Line.h(r.typeName().toUpperCase()));
		lines.add(Line.gap());
		lines.add(Line.p("To whom it may concern,"));
		lines.add(Line.gap());
		if (Boolean.TRUE.equals(e.get("exited")) || type.contains("experience")) {
			LocalDate end = (LocalDate) e.get("exit");
			lines.add(Line.p("This is to certify that " + who + " worked with " + legal + " as " + role + " from " + joined
					+ (end != null ? " to " + DMY.format(end) : " to date") + "."));
		}
		else {
			lines.add(Line.p("This is to certify that " + who + " has been employed with " + legal + " since " + joined + " and currently works as "
					+ role + "."));
		}
		if (type.contains("salary")) {
			lines.add(Line.p("Salary details are as per the company's payroll records and are available to the addressee on request through HR."));
		}
		else if (type.contains("address")) {
			lines.add(Line.p("The address on record with us is as declared by the employee in their profile."));
		}
		else if (type.contains("visa") || type.contains("travel")) {
			lines.add(Line.p("We confirm that the employee's travel is approved and that they will resume their duties on return."));
		}
		if (!r.note().isBlank()) {
			lines.add(Line.p("This letter is issued at the request of the employee for: " + r.note()));
		}
		lines.add(Line.gap());
		lines.add(Line.p("For " + legal + ","));
		lines.add(Line.gap());
		lines.add(Line.gap());
		lines.add(Line.b(a.name()));
		lines.add(Line.p(a.title() == null ? "Human Resources" : a.title()));
		lines.add(Line.gap());
		lines.add(Line.p("This is a system-generated letter from iHRMS."));
		return LetterPdf.render(lines);
	}

	// ---------------------------------------------------------------------------------------------------------------- files

	public record FileContent(byte[] bytes, String mime, String name) {
	}

	@Transactional(readOnly = true)
	public FileContent file(UUID id, String which) {
		Actor a = actor();
		Req r = load(a.org(), id);
		if (!canSee(r, a)) {
			throw new NotFoundException("Request not found");
		}
		boolean letter = "letter".equals(which);
		String key = letter ? r.letterKey() : r.receiptKey();
		if (key == null) {
			throw new NotFoundException(letter ? "No letter has been issued" : "No receipt was attached");
		}
		try {
			return new FileContent(store.get(key), letter ? r.letterMime() : r.receiptMime(), letter ? r.letterName() : r.receiptName());
		}
		catch (IOException ex) {
			throw new StatusException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_ERROR", "The file could not be read. Try again.");
		}
	}

	private String checkFile(byte[] bytes) {
		if (bytes.length > MAX_BYTES) {
			throw new StatusException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "File is larger than 10 MB");
		}
		String mime = sniff(bytes);
		if (mime == null) {
			throw new StatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "BAD_TYPE", "Upload a PDF, JPG or PNG");
		}
		if (!scanner.clean(bytes)) {
			throw new StatusException(HttpStatus.UNPROCESSABLE_ENTITY, "INFECTED", "That file did not pass the virus scan");
		}
		return mime;
	}

	private void put(String key, byte[] bytes) {
		try {
			store.put(key, bytes);
		}
		catch (IOException ex) {
			throw new StatusException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_ERROR", "The file could not be saved. Try again.");
		}
	}

	static String sniff(byte[] b) {
		if (b.length > 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F') {
			return "application/pdf";
		}
		if (b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
			return "image/jpeg";
		}
		if (b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
			return "image/png";
		}
		return null;
	}

	private static String safeName(String name, String fallback) {
		String n = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\r\\n]+", "_").trim();
		return n.isEmpty() ? fallback : n.length() > 120 ? n.substring(n.length() - 120) : n;
	}

	// ---------------------------------------------------------------------------------------------------------------- helpers

	private void close(Req r, Actor a, String status, String stage, String action, String note) {
		jdbc.sql("""
				update employee_request set status = :s, stage = :st, decided_by = :by, decided_at = now(), decision_note = :n, updated_at = now(),
				       version = version + 1 where id = :id""").param("s", status).param("st", stage).param("by", a.id()).param("n", note, java.sql.Types.VARCHAR)
				.param("id", r.id()).update();
		event(r.id(), a.id(), action, note);
	}

	private void event(UUID id, UUID by, String action, String note) {
		jdbc.sql("insert into employee_request_event (request_id, by, action, note) values (:id, :by, :a, :n)").param("id", id).param("by", by)
				.param("a", action).param("n", note, java.sql.Types.VARCHAR).update();
	}

	private static String clean(String note) {
		if (note == null || note.isBlank()) {
			return null;
		}
		String n = note.trim();
		return n.length() > 1000 ? n.substring(0, 1000) : n;
	}

	/** ₹1,50,000 style (Indian digit grouping). */
	static String inr(BigDecimal amount) {
		if (amount == null) {
			return "";
		}
		BigDecimal a = amount.setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros();
		String plain = a.abs().toPlainString();
		String whole = plain.contains(".") ? plain.substring(0, plain.indexOf('.')) : plain;
		String frac = plain.contains(".") ? plain.substring(plain.indexOf('.')) : "";
		StringBuilder b = new StringBuilder();
		int n = whole.length();
		for (int i = 0; i < n; i++) {
			b.append(whole.charAt(i));
			int left = n - 1 - i;
			if (left > 0 && (left == 3 || (left > 3 && (left - 3) % 2 == 0))) {
				b.append(',');
			}
		}
		return (a.signum() < 0 ? "-₹" : "₹") + b + frac;
	}
}
