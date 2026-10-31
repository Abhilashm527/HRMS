package com.ivoyant.hrms.leave.service;

import static com.ivoyant.hrms.leave.service.LeaveSupport.days;
import static com.ivoyant.hrms.leave.service.LeaveSupport.problem;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.security.ModuleAccess;
import com.ivoyant.hrms.common.web.ForbiddenException;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.leave.service.LeaveLedgerService.Line;
import com.ivoyant.hrms.leave.service.LeaveSupport.Type;
import com.ivoyant.hrms.notification.Notifier;

/**
 * Leave notifications: HR reminds people to plan leave for a period. Each message is personalised per person ({first}, {period}, {to},
 * {deadline}, {manager}, balances by leave-type code such as {EL}, and {lapse}: EL that would lapse at year-end) and goes to the in-app bell,
 * and by email when asked. A notice can be sent now or scheduled, once or weekly until the period ends.
 *
 * Audience: departments and locations (none picked means everyone), narrowed to everyone, people whose EL will lapse, or people with at least
 * a given balance of a type.
 */
@Service
public class LeaveNoticeService {

	private static final Logger log = LoggerFactory.getLogger(LeaveNoticeService.class);
	private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
	private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final ModuleAccess access;
	private final LeaveSupport support;
	private final LeaveLedgerService ledger;
	private final Notifier notifier;
	private final Audit audit;

	LeaveNoticeService(JdbcClient jdbc, CurrentUser currentUser, ModuleAccess access, LeaveSupport support, LeaveLedgerService ledger, Notifier notifier, Audit audit) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.access = access;
		this.support = support;
		this.ledger = ledger;
		this.notifier = notifier;
		this.audit = audit;
	}

	record Notice(UUID id, String title, String body, LocalDate from, LocalDate to, LocalDate deadline, List<UUID> departments, List<UUID> locations, String who,
			UUID balType, BigDecimal balMin, boolean email, OffsetDateTime sendAt, String repeat, String status, OffsetDateTime lastSentAt, int sentCount, int recipients,
			OffsetDateTime createdAt) {
	}

	private static final String SQL = """
			select id, title, body, period_from, period_to, deadline, departments, locations, who, bal_type_id, bal_min, email, send_at, repeat, status, last_sent_at,
			       sent_count, recipients, created_at from leave_notice""";

	private static List<UUID> arr(java.sql.Array a) throws java.sql.SQLException {
		if (a == null) {
			return List.of();
		}
		List<UUID> out = new ArrayList<>();
		for (Object o : (Object[]) a.getArray()) {
			out.add(o instanceof UUID u ? u : UUID.fromString(o.toString()));
		}
		return out;
	}

	private static Notice notice(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new Notice(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getObject(4, LocalDate.class), rs.getObject(5, LocalDate.class),
				rs.getObject(6, LocalDate.class), arr(rs.getArray(7)), arr(rs.getArray(8)), rs.getString(9), rs.getObject(10, UUID.class), rs.getBigDecimal(11), rs.getBoolean(12),
				rs.getObject(13, OffsetDateTime.class), rs.getString(14), rs.getString(15), rs.getObject(16, OffsetDateTime.class), rs.getInt(17), rs.getInt(18),
				rs.getObject(19, OffsetDateTime.class));
	}

	private Notice load(UUID id) {
		return jdbc.sql(SQL + " where id = :id and org_id = :org").param("id", id).param("org", currentUser.orgId()).query((rs, i) -> notice(rs)).optional()
				.orElseThrow(() -> new NotFoundException("Notification " + id + " not found"));
	}

	private void requireAdmin() {
		if (!access.has("leave", "ADMIN")) {
			throw new ForbiddenException("Only leave admins can send leave notifications");
		}
	}

	// ================================================================== screen

	@Transactional(readOnly = true)
	public Map<String, Object> list() {
		ledger.requireView();
		Map<UUID, Type> types = support.typesById();
		Map<UUID, String> deptNames = names("department");
		Map<UUID, String> locNames = names("location");
		List<Map<String, Object>> items = jdbc.sql(SQL + " where org_id = :org order by case status when 'SCHEDULED' then 0 else 1 end, coalesce(last_sent_at, send_at) desc")
				.param("org", currentUser.orgId()).query((rs, i) -> notice(rs)).list().stream().map(n -> view(n, types, deptNames, locNames)).toList();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("items", items);
		out.put("canManage", access.has("leave", "ADMIN"));
		return out;
	}

	private Map<UUID, String> names(String table) {
		Map<UUID, String> m = new HashMap<>();
		jdbc.sql("select id, name from " + table + " where org_id = :org").param("org", currentUser.orgId()).query((rs, i) -> m.put(rs.getObject(1, UUID.class), rs.getString(2)))
				.list();
		return m;
	}

	private static Map<String, Object> view(Notice n, Map<UUID, Type> types, Map<UUID, String> depts, Map<UUID, String> locs) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", n.id());
		m.put("title", n.title());
		m.put("body", n.body());
		m.put("from", n.from());
		m.put("to", n.to());
		m.put("deadline", n.deadline());
		m.put("departments", n.departments());
		m.put("locations", n.locations());
		m.put("who", n.who());
		Type bt = n.balType() == null ? null : types.get(n.balType());
		m.put("balType", bt == null ? null : bt.code());
		m.put("balMin", n.balMin() == null ? null : days(n.balMin()));
		m.put("email", n.email());
		m.put("sendAt", n.sendAt());
		m.put("repeat", n.repeat());
		m.put("status", n.status());
		m.put("lastSentAt", n.lastSentAt());
		m.put("sentCount", n.sentCount());
		m.put("recipients", n.recipients());
		m.put("createdAt", n.createdAt());
		List<String> aud = new ArrayList<>();
		aud.add(n.departments().isEmpty() ? "All departments" : String.join(", ", n.departments().stream().map(d -> depts.getOrDefault(d, "?")).toList()));
		if (!n.locations().isEmpty()) {
			aud.add(String.join(", ", n.locations().stream().map(l -> locs.getOrDefault(l, "?")).toList()));
		}
		if ("LAPSE".equals(n.who())) {
			aud.add("EL will lapse");
		}
		if ("BALANCE".equals(n.who()) && bt != null) {
			aud.add(bt.code() + " ≥ " + days(n.balMin()).toPlainString() + " days");
		}
		m.put("audience", String.join(" · ", aud));
		return m;
	}

	/** Who would get it and what the first few would read. Same body as create, nothing saved. */
	@Transactional
	public Map<String, Object> preview(Map<String, Object> b) {
		requireAdmin();
		Notice n = fromBody(null, b);
		List<Map<String, Object>> people = audience(n);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("count", people.size());
		out.put("names", people.stream().limit(6).map(p -> (String) p.get("name")).toList());
		out.put("samples", people.stream().limit(5).map(p -> Map.of("name", p.get("name"), "title", fill(n.title(), p, n), "body", fill(n.body(), p, n))).toList());
		return out;
	}

	@Transactional
	public Map<String, Object> save(UUID id, Map<String, Object> b) {
		requireAdmin();
		if (id != null && !"SCHEDULED".equals(load(id).status())) {
			throw problem("status", "SENT", "Only scheduled notifications can be edited");
		}
		Notice n = fromBody(id, b);
		boolean now = !"later".equalsIgnoreCase(String.valueOf(b.get("when")));
		var sql = id == null ? jdbc.sql("""
				insert into leave_notice (org_id, title, body, period_from, period_to, deadline, departments, locations, who, bal_type_id, bal_min, email, send_at, repeat, created_by)
				values (:org, :t, :b, :f, :to, :dl, cast(:deps as uuid[]), cast(:locs as uuid[]), :who, :bt, :bm, :em, :at, :rep, :by) returning id""")
				: jdbc.sql("""
						update leave_notice set title = :t, body = :b, period_from = :f, period_to = :to, deadline = :dl, departments = cast(:deps as uuid[]), locations = cast(:locs as uuid[]), who = :who,
						       bal_type_id = :bt, bal_min = :bm, email = :em, send_at = :at, repeat = :rep where id = :id and org_id = :org returning id""").param("id", id);
		UUID saved = sql.param("org", currentUser.orgId()).param("t", n.title()).param("b", n.body()).param("f", n.from()).param("to", n.to()).param("dl", n.deadline())
				.param("deps", pgArray(n.departments())).param("locs", pgArray(n.locations())).param("who", n.who())
				.param("bt", n.balType(), java.sql.Types.OTHER).param("bm", n.balMin()).param("em", n.email()).param("at", now ? OffsetDateTime.now() : n.sendAt())
				.param("rep", n.repeat()).param("by", currentUser.employeeId(), java.sql.Types.OTHER).query(UUID.class).single();
		audit.publish("leave", "LeaveNotice", saved, id == null ? "CREATE" : "UPDATE", null, n.title());
		if (now) {
			send(load(saved));
		}
		return view(load(saved), support.typesById(), names("department"), names("location"));
	}

	@Transactional
	public Map<String, Object> cancel(UUID id) {
		requireAdmin();
		Notice n = load(id);
		if (!"SCHEDULED".equals(n.status())) {
			throw problem("status", "SENT", "Only scheduled notifications can be cancelled");
		}
		jdbc.sql("update leave_notice set status = 'CANCELLED' where id = :id").param("id", id).update();
		audit.publish("leave", "LeaveNotice", id, "CANCEL", null, null);
		return view(load(id), support.typesById(), names("department"), names("location"));
	}

	@Transactional
	public Map<String, Object> sendNow(UUID id) {
		requireAdmin();
		Notice n = load(id);
		if ("CANCELLED".equals(n.status())) {
			throw problem("status", "CANCELLED", "This notification was cancelled");
		}
		send(n);
		return view(load(id), support.typesById(), names("department"), names("location"));
	}

	/** Copies a sent notification as a new draft body (the screen's Duplicate). */
	@Transactional(readOnly = true)
	public Map<String, Object> get(UUID id) {
		ledger.requireView();
		return view(load(id), support.typesById(), names("department"), names("location"));
	}

	// ================================================================== sending

	/** Every few minutes: sends scheduled notifications that are due, and repeats weekly ones until their period ends. */
	@Scheduled(fixedDelay = 300_000, initialDelay = 120_000)
	@Transactional
	public void sendDue() {
		List<Notice> due = jdbc.sql(SQL + " where status = 'SCHEDULED' and send_at <= now()").query((rs, i) -> notice(rs)).list();
		for (Notice n : due) {
			try {
				send(n);
			}
			catch (RuntimeException e) {
				log.warn("Leave notification {} could not be sent: {}", n.id(), e.getMessage());
			}
		}
	}

	private void send(Notice n) {
		List<Map<String, Object>> people = audience(n);
		for (Map<String, Object> p : people) {
			String title = fill(n.title(), p, n);
			String body = fill(n.body(), p, n);
			notifier.inApp(List.of((UUID) p.get("id")), "LEAVE_NOTICE", title, body, "/me/profile");
			if (n.email() && p.get("email") != null) {
				notifier.email((String) p.get("email"), null, title, "<p>" + escape(body).replace("\n", "<br>") + "</p>");
			}
		}
		LocalDate today = LocalDate.now(IST);
		boolean again = "WEEKLY".equals(n.repeat()) && n.to() != null && !today.plusDays(7).isAfter(n.to());
		jdbc.sql("update leave_notice set status = :st, send_at = :next, last_sent_at = now(), sent_count = sent_count + 1, recipients = :n where id = :id")
				.param("st", again ? "SCHEDULED" : "SENT").param("next", again ? OffsetDateTime.now().plusDays(7) : n.sendAt()).param("n", people.size()).param("id", n.id())
				.update();
	}

	private static String escape(String s) {
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private List<Map<String, Object>> audience(Notice n) {
		StringBuilder w = new StringBuilder(" where e.org_id = :org and e.archived_at is null and e.status <> 'EXITED'");
		Map<String, Object> p = new HashMap<>();
		p.put("org", currentUser.orgId());
		if (!n.departments().isEmpty()) {
			w.append(" and e.department_id in (:deps)");
			p.put("deps", n.departments());
		}
		if (!n.locations().isEmpty()) {
			w.append(" and e.location_id in (:locs)");
			p.put("locs", n.locations());
		}
		List<Map<String, Object>> people = jdbc.sql("""
				select e.id, e.display_name, e.first_name, e.work_email, m.display_name as manager from employee e left join employee m on m.id = e.manager_id""" + w
				+ " order by lower(e.display_name)").params(p).query((rs, i) -> {
					Map<String, Object> m = new HashMap<>();
					m.put("id", rs.getObject(1, UUID.class));
					m.put("name", rs.getString(2));
					m.put("first", rs.getString(3));
					m.put("email", rs.getString(4));
					m.put("manager", rs.getString(5));
					return m;
				}).list();
		int year = n.to() != null ? n.to().getYear() : LocalDate.now(IST).getYear();
		Map<UUID, Type> types = support.typesById();
		List<Map<String, Object>> out = new ArrayList<>();
		for (Map<String, Object> person : people) {
			UUID id = (UUID) person.get("id");
			ledger.ensureCredits(id, year);
			Map<String, BigDecimal> bal = new HashMap<>();
			BigDecimal lapse = BigDecimal.ZERO;
			for (Line l : ledger.lines(id, year)) {
				bal.put(l.type().code(), l.available());
				if ("EL".equals(l.type().code())) {
					lapse = l.available().subtract(LeaveLedgerService.carryOf(l.type(), l.available())).max(BigDecimal.ZERO);
				}
			}
			if ("LAPSE".equals(n.who()) && lapse.signum() <= 0) {
				continue;
			}
			if ("BALANCE".equals(n.who())) {
				Type bt = types.get(n.balType());
				if (bt == null || bal.getOrDefault(bt.code(), BigDecimal.ZERO).compareTo(n.balMin() == null ? BigDecimal.ZERO : n.balMin()) < 0) {
					continue;
				}
			}
			person.put("balances", bal);
			person.put("lapse", lapse);
			out.add(person);
		}
		return out;
	}

	@SuppressWarnings("unchecked")
	private static String fill(String text, Map<String, Object> p, Notice n) {
		String out = text.replace("{first}", String.valueOf(p.getOrDefault("first", ""))).replace("{name}", String.valueOf(p.getOrDefault("name", "")))
				.replace("{manager}", p.get("manager") == null ? "your manager" : p.get("manager").toString())
				.replace("{period}", n.from() == null ? "" : n.to() == null || n.to().equals(n.from()) ? D.format(n.from()) : D.format(n.from()) + " – " + D.format(n.to()))
				.replace("{to}", n.to() == null ? "" : D.format(n.to())).replace("{from}", n.from() == null ? "" : D.format(n.from()))
				.replace("{deadline}", n.deadline() == null ? "" : D.format(n.deadline()))
				.replace("{lapse}", days((BigDecimal) p.getOrDefault("lapse", BigDecimal.ZERO)).toPlainString());
		Map<String, BigDecimal> bal = (Map<String, BigDecimal>) p.getOrDefault("balances", Map.of());
		for (Map.Entry<String, BigDecimal> e : bal.entrySet()) {
			out = out.replace("{" + e.getKey() + "}", days(e.getValue()).toPlainString());
		}
		return out.replaceAll("\\{[A-Z]{2,4}}", "0");
	}

	private Notice fromBody(UUID id, Map<String, Object> b) {
		String title = LeaveSupport.text(b.get("title"));
		String body = LeaveSupport.text(b.get("body"));
		if (title == null) {
			throw problem("title", "REQUIRED", "Add a title");
		}
		if (body == null) {
			throw problem("body", "REQUIRED", "Write the message");
		}
		LocalDate from = date(b.get("from"));
		LocalDate to = date(b.get("to"));
		LocalDate deadline = date(b.get("deadline"));
		if (from != null && to != null && to.isBefore(from)) {
			throw problem("to", "DATES_ORDER", "The period must end on or after it starts");
		}
		String who = switch (String.valueOf(b.getOrDefault("who", "ALL")).toUpperCase(Locale.ROOT)) {
			case "LAPSE" -> "LAPSE";
			case "BALANCE" -> "BALANCE";
			default -> "ALL";
		};
		UUID balType = null;
		BigDecimal balMin = null;
		if ("BALANCE".equals(who)) {
			balType = support.typeByCode(String.valueOf(b.get("balType"))).id();
			try {
				balMin = new BigDecimal(String.valueOf(b.get("balMin")).trim());
			}
			catch (NumberFormatException e) {
				throw problem("balMin", "INVALID", "Enter the minimum balance in days");
			}
		}
		OffsetDateTime at = OffsetDateTime.now();
		if ("later".equalsIgnoreCase(String.valueOf(b.get("when")))) {
			if (b.get("sendAt") == null || b.get("sendAt").toString().isBlank()) {
				throw problem("sendAt", "REQUIRED", "Choose when to send");
			}
			at = LocalDateTime.parse(b.get("sendAt").toString()).atZone(IST).toOffsetDateTime();
			if (at.isBefore(OffsetDateTime.now())) {
				throw problem("sendAt", "PAST", "Pick a time in the future");
			}
		}
		String repeat = "WEEKLY".equalsIgnoreCase(String.valueOf(b.get("repeat"))) ? "WEEKLY" : "NONE";
		if ("WEEKLY".equals(repeat) && to == null) {
			throw problem("to", "REQUIRED", "A weekly reminder needs the period's end date");
		}
		return new Notice(id, title, body, from, to, deadline, LeaveSupport.uuids(b.get("departments")), LeaveSupport.uuids(b.get("locations")), who, balType, balMin,
				!Boolean.FALSE.equals(b.get("email")), at, repeat, "SCHEDULED", null, 0, 0, null);
	}

	private static String pgArray(List<UUID> ids) {
		return "{" + String.join(",", ids.stream().map(UUID::toString).toList()) + "}";
	}

	private static LocalDate date(Object o) {
		return o == null || o.toString().isBlank() ? null : LocalDate.parse(o.toString().substring(0, 10));
	}
}
