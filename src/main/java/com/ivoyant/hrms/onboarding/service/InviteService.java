package com.ivoyant.hrms.onboarding.service;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.CodedConflictException;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.StatusException;
import com.ivoyant.hrms.common.web.UnprocessableException;
import com.ivoyant.hrms.notification.Notifier;
import com.ivoyant.hrms.onboarding.OnboardingConfig;
import com.ivoyant.hrms.onboarding.domain.Hire;
import com.ivoyant.hrms.org.OrgDirectory;
import com.ivoyant.hrms.org.OrgProfile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

/** The invite email: first send, resend (new link), reminders (manual, bulk, automatic) and the preview. */
@Service
public class InviteService {

	private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
	private static final SecureRandom RANDOM = new SecureRandom();

	private final JdbcClient jdbc;
	private final HireRepo repo;
	private final OnboardingConfig config;
	private final Notifier notifier;
	private final EventLog events;
	private final Audit audit;
	private final HireStatusService status;
	private final OnboardingSettings settings;
	private final OrgProfile orgProfile;
	private final OrgDirectory org;
	private final CurrentUser currentUser;

	InviteService(JdbcClient jdbc, HireRepo repo, OnboardingConfig config, Notifier notifier, EventLog events, Audit audit,
			HireStatusService status, OnboardingSettings settings, OrgProfile orgProfile, OrgDirectory org,
			CurrentUser currentUser) {
		this.jdbc = jdbc;
		this.repo = repo;
		this.config = config;
		this.notifier = notifier;
		this.events = events;
		this.audit = audit;
		this.status = status;
		this.settings = settings;
		this.orgProfile = orgProfile;
		this.org = org;
		this.currentUser = currentUser;
	}

	// ------------------------------------------------------------------ tokens

	public static byte[] hash(String token) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
		}
		catch (java.security.NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static String newToken() {
		byte[] b = new byte[32];
		RANDOM.nextBytes(b);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
	}

	// ------------------------------------------------------------------ send / resend

	@Transactional
	public Map<String, Object> send(UUID hireId) {
		Hire h = repo.lock(hireId);
		requireOpen(h);
		if (hasInvite(hireId)) {
			throw new CodedConflictException("ALREADY_INVITED", "The invite was already sent");
		}
		requireInvitable(h);
		String token = issue(h, true);
		events.add(hireId, "MAIL", "Invite sent", "To " + h.personalEmail());
		audit.publish("onboarding", "Hire", hireId, "INVITE", null, h.personalEmail());
		status.recompute(hireId);
		return Map.of("sentTo", h.personalEmail(), "link", linkOf(token));
	}

	/** Rotates the link and sends it again; the reminder count stays. */
	@Transactional
	public Map<String, Object> resend(UUID hireId) {
		Hire h = repo.lock(hireId);
		requireOpen(h);
		if (!hasInvite(hireId)) {
			throw new CodedConflictException("NOT_INVITED", "Send the first invite instead");
		}
		requireInvitable(h);
		String token = issue(h, false);
		events.add(hireId, "MAIL", "Invite resent", "To " + h.personalEmail() + " with a new link");
		audit.publish("onboarding", "Hire", hireId, "INVITE_RESEND", null, h.personalEmail());
		status.recompute(hireId);
		return Map.of("sentTo", h.personalEmail(), "link", linkOf(token));
	}

	/** Stores a new token and emails it. {@code first} resets the send counters. */
	private String issue(Hire h, boolean first) {
		OnboardingConfig.InviteTemplate t = template(h);
		String token = newToken();
		ZoneId zone = orgProfile.zone();
		OffsetDateTime expires = h.doj().plusDays(8).atStartOfDay(zone).toOffsetDateTime();
		int dueDays = t == null ? 3 : t.dueDaysBefore();
		LocalDate due = h.doj().minusDays(dueDays);
		Integer every = t == null ? null : t.remindEveryDays();
		jdbc.sql("""
				insert into onboarding_invite (hire_id, sent_to, token_hash, expires_at, first_sent_at, last_sent_at, due_date, remind_every_days)
				values (:h, :to, :th, :ex, now(), now(), :due, :ev)
				on conflict (hire_id) do update set sent_to = :to, token_hash = :th, expires_at = :ex, last_sent_at = now(),
				  revoked_at = null, due_date = :due, remind_every_days = :ev,
				  first_sent_at = case when :first then now() else onboarding_invite.first_sent_at end,
				  reminders = case when :first then 0 else onboarding_invite.reminders end""")
				.param("h", h.id()).param("to", h.personalEmail()).param("th", hash(token)).param("ex", expires).param("due", due)
				.param("ev", every).param("first", first).update();
		Rendered mail = render(h, t, token, docNames(h.id()), due);
		String cc = t != null && t.ccManager() ? managerEmail(h.managerId()) : null;
		notifier.email(h.personalEmail(), cc, mail.subject(), mail.html());
		return token;
	}

	private void requireInvitable(Hire h) {
		if (h.personalEmail() == null || !EMAIL.matcher(h.personalEmail()).matches()) {
			throw new UnprocessableException(List.of(ErrorItem.field("pemail", "REQUIRED", "Add a valid personal email first")));
		}
		long docs = jdbc.sql("select count(*) from onboarding_hire_document where hire_id = :h").param("h", h.id()).query(Long.class).single();
		long fields = portalFieldCount();
		if (docs == 0 && fields == 0) {
			throw new UnprocessableException(List.of(ErrorItem.field(null, "NOTHING_TO_COLLECT", "Choose at least one document or add portal fields before inviting")));
		}
	}

	long portalFieldCount() {
		return jdbc.sql("""
				select count(*) from config_field f join config_section s on s.id = f.section_id
				where f.org_id = :org and f.area_key = 'employee_profile' and s.show_in_portal and s.active and s.deleted_at is null
				  and f.active and f.deleted_at is null and f.type <> 'HEADING'""").param("org", currentUser.orgId()).query(Long.class).single();
	}

	// ------------------------------------------------------------------ reminders

	/** One reminder email listing what is still outstanding. */
	@Transactional
	public Map<String, Object> remind(UUID hireId, String docKey) {
		Hire h = repo.lock(hireId);
		requireOpen(h);
		Map<String, Object> inv = invite(hireId);
		if (inv == null) {
			throw new CodedConflictException("NOT_INVITED", "Send the invite first");
		}
		OffsetDateTime last = (OffsetDateTime) inv.get("lastReminderAt");
		if (last != null && last.isAfter(OffsetDateTime.now().minusMinutes(10))) {
			throw new StatusException(HttpStatus.TOO_MANY_REQUESTS, "TOO_SOON", "A reminder was sent a moment ago. Try again in a few minutes.");
		}
		List<String> outstanding = docKey == null ? outstanding(h) : List.of(docLabel(hireId, docKey));
		if (outstanding.isEmpty()) {
			throw new CodedConflictException("NOTHING_OUTSTANDING", h.first() + " has nothing outstanding");
		}
		sendReminder(h, outstanding, (String) null);
		jdbc.sql("update onboarding_invite set reminders = reminders + 1, last_reminder_at = now() where hire_id = :h").param("h", hireId).update();
		events.add(hireId, "MAIL", "Reminder sent", outstanding.size() + (outstanding.size() == 1 ? " item" : " items") + " outstanding");
		return Map.of("outstanding", outstanding.size());
	}

	/** The scheduled reminder: same email, no rate limit, counted. */
	@Transactional
	public boolean autoRemind(UUID hireId) {
		Hire h = repo.find(hireId).orElse(null);
		if (h == null || !h.open() || !hasInvite(hireId)) {
			return false;
		}
		List<String> outstanding = outstanding(h);
		if (outstanding.isEmpty()) {
			return false;
		}
		sendReminder(h, outstanding, null);
		jdbc.sql("update onboarding_invite set reminders = reminders + 1, last_reminder_at = now() where hire_id = :h").param("h", hireId).update();
		events.add(hireId, "MAIL", "Reminder sent", "Automatic · " + outstanding.size() + " outstanding");
		return true;
	}

	private void sendReminder(Hire h, List<String> outstanding, String unused) {
		String token = newToken();
		jdbc.sql("update onboarding_invite set token_hash = :t where hire_id = :h").param("t", hash(token)).param("h", h.id()).update();
		StringBuilder list = new StringBuilder("<ul>");
		outstanding.forEach(o -> list.append("<li>").append(HtmlUtils.htmlEscape(o)).append("</li>"));
		list.append("</ul>");
		LocalDate due = (LocalDate) invite(h.id()).get("dueDate");
		notifier.email(h.personalEmail(), null, outstanding.size() + (outstanding.size() == 1 ? " item" : " items") + " outstanding for your onboarding",
				"<p>Hi " + HtmlUtils.htmlEscape(h.first()) + ", a few things are still needed before you join on " + h.doj().format(DAY) + ":</p>"
						+ list + "<p>Please finish by " + due.format(DAY) + ".</p>" + button(linkOf(token)));
	}

	List<String> outstanding(Hire h) {
		List<String> out = new ArrayList<>();
		if (h.detailsSubmittedAt() == null) {
			out.add("Your personal details");
		}
		out.addAll(jdbc.sql("""
				select od.name || case when d.status = 'REJECTED' then ' (upload again)' else '' end
				from onboarding_hire_document d join onboarding_document od on od.id = d.document_id
				where d.hire_id = :h and d.required and d.status in ('MISSING', 'REJECTED') order by d.sort""").param("h", h.id()).query(String.class).list());
		return out;
	}

	private String docLabel(UUID hireId, String key) {
		return jdbc.sql("select od.name from onboarding_hire_document d join onboarding_document od on od.id = d.document_id where d.hire_id = :h and d.doc_key = :k")
				.param("h", hireId).param("k", key).query(String.class).optional().orElse(key);
	}

	@Transactional
	public Map<String, Object> bulkRemind(List<UUID> ids) {
		int sent = 0;
		int drafts = 0;
		List<Map<String, Object>> skipped = new ArrayList<>();
		for (UUID id : ids) {
			Hire h = repo.find(id).orElse(null);
			if (h == null || !h.open()) {
				continue;
			}
			if (!hasInvite(id)) {
				drafts++;
				continue;
			}
			try {
				remind(id, null);
				sent++;
			}
			catch (StatusException | CodedConflictException ex) {
				skipped.add(Map.of("id", id, "reason", ex instanceof StatusException s ? s.getCode() : "NOTHING_OUTSTANDING"));
			}
		}
		return Map.of("sent", sent, "skippedDrafts", drafts, "skipped", skipped);
	}

	@Transactional
	public Map<String, Object> bulkInvite(List<UUID> ids) {
		int sent = 0;
		List<Map<String, Object>> skipped = new ArrayList<>();
		for (UUID id : ids) {
			Hire h = repo.find(id).orElse(null);
			if (h == null || !h.open() || hasInvite(id)) {
				continue;
			}
			try {
				send(id);
				sent++;
			}
			catch (UnprocessableException ex) {
				String code = ex.getErrors().isEmpty() ? "INVALID" : ex.getErrors().get(0).code();
				skipped.add(Map.of("id", id, "reason", "REQUIRED".equals(code) ? "NO_EMAIL" : code));
			}
		}
		return Map.of("sent", sent, "skipped", skipped);
	}

	// ------------------------------------------------------------------ preview

	/** Renders the invite for a (possibly partial) create body, exactly as it would be sent. */
	@Transactional(readOnly = true)
	@SuppressWarnings("unchecked")
	public Map<String, Object> preview(Map<String, Object> body) {
		Map<String, Object> v = body.get("values") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
		UUID etype = uuid(v.get("etype"));
		OnboardingConfig.InviteTemplate t = null;
		if (body.get("inviteTemplateId") != null) {
			UUID id = uuid(body.get("inviteTemplateId"));
			t = id == null ? null : jdbc.sql("select id, name, employment_type_id, subject, body, due_days_before, remind_every_days, cc_manager from invite_template where id = :id and org_id = :org")
					.param("id", id).param("org", currentUser.orgId()).query((rs, i) -> new OnboardingConfig.InviteTemplate(rs.getObject(1, UUID.class), rs.getString(2),
							rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5), rs.getInt(6), (Integer) rs.getObject(7), rs.getBoolean(8))).optional().orElse(null);
		}
		if (t == null && etype != null) {
			t = config.inviteFor(etype).orElse(null);
		}
		LocalDate doj = v.get("doj") == null ? null : LocalDate.parse(v.get("doj").toString());
		List<String> docs = new ArrayList<>();
		if (body.get("documents") instanceof List<?> list) {
			for (Object o : list) {
				if (o instanceof Map<?, ?> dm && dm.get("key") != null) {
					docs.add(jdbc.sql("select name from onboarding_document where org_id = :org and key = :k").param("org", currentUser.orgId())
							.param("k", dm.get("key").toString()).query(String.class).optional().orElse(dm.get("key").toString()));
				}
			}
		}
		else if (etype != null) {
			config.documentsFor(etype).stream().filter(OnboardingConfig.DocumentView::required).forEach(d -> docs.add(d.name()));
		}
		LocalDate due = doj == null ? null : doj.minusDays(t == null ? 3 : t.dueDaysBefore());
		UUID dept = uuid(v.get("dept"));
		UUID loc = uuid(v.get("loc"));
		UUID mgr = uuid(v.get("mgr"));
		Map<String, String> vals = facts(str(v.get("first")), str(v.get("title")), doj, dept == null ? "" : org.departmentNames(List.of(dept)).getOrDefault(dept, ""),
				loc == null ? "" : org.locationNames(List.of(loc)).getOrDefault(loc, ""), mgr == null ? "" : employeeName(mgr), "", docs, due, linkOf("preview"));
		String subject = t == null ? "Welcome to " + vals.get("company") + ", " + vals.get("first") + "!" : config.renderInvite(t, vals).subject();
		String inner = t == null ? defaultBody(vals) : config.renderInvite(t, vals).body();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("to", str(v.get("pemail")));
		out.put("cc", t != null && t.ccManager() && mgr != null ? managerEmail(mgr) : null);
		out.put("subject", subject);
		out.put("html", wrap(inner, vals, docs, due) + button(linkOf("preview")));
		out.put("facts", factList(vals));
		out.put("dueDate", due);
		return out;
	}

	// ------------------------------------------------------------------ rendering

	record Rendered(String subject, String html) {
	}

	private Rendered render(Hire h, OnboardingConfig.InviteTemplate t, String token, List<String> docs, LocalDate due) {
		String upn = jdbc.sql("select upn from onboarding_m365 where hire_id = :h and state <> 'DISABLED'").param("h", h.id()).query(String.class).optional().orElse("");
		Map<String, String> vals = facts(h.first(), h.title(), h.doj(), org.departmentNames(List.of(h.departmentId())).getOrDefault(h.departmentId(), ""),
				org.locationNames(List.of(h.locationId())).getOrDefault(h.locationId(), ""), employeeName(h.managerId()), upn, docs, due, linkOf(token));
		String subject = t == null ? "Welcome to " + vals.get("company") + ", " + h.first() + "!" : config.renderInvite(t, vals).subject();
		String inner = t == null ? defaultBody(vals) : config.renderInvite(t, vals).body();
		return new Rendered(subject, wrap(inner, vals, docs, due) + button(linkOf(token)));
	}

	private Map<String, String> facts(String first, String title, LocalDate doj, String dept, String loc, String manager, String upn,
			List<String> docs, LocalDate due, String link) {
		Map<String, String> m = new LinkedHashMap<>();
		m.put("company", orgProfile.current().name());
		m.put("first", first == null ? "" : first);
		m.put("title", title == null ? "" : title);
		m.put("doj", doj == null ? "" : doj.format(DAY));
		m.put("department", dept);
		m.put("location", loc);
		m.put("manager", manager);
		m.put("upn", upn);
		m.put("docs", String.join(", ", docs));
		m.put("due", due == null ? "" : due.format(DAY));
		m.put("link", link);
		return m;
	}

	private static String defaultBody(Map<String, String> v) {
		return "Hi " + HtmlUtils.htmlEscape(v.get("first")) + ", we are glad you are joining as " + HtmlUtils.htmlEscape(v.get("title")) + " on "
				+ HtmlUtils.htmlEscape(v.get("doj")) + ". Please share your details and documents before you start.";
	}

	private static List<Map<String, String>> factList(Map<String, String> v) {
		List<Map<String, String>> out = new ArrayList<>();
		for (String[] f : new String[][] {{"Role", "title"}, {"Joining date", "doj"}, {"Location", "location"}, {"Department", "department"},
				{"Reports to", "manager"}, {"Work email", "upn"}}) {
			if (!v.get(f[1]).isBlank()) {
				out.add(Map.of("label", f[0], "value", v.get(f[1])));
			}
		}
		return out;
	}

	private static String wrap(String inner, Map<String, String> v, List<String> docs, LocalDate due) {
		StringBuilder sb = new StringBuilder("<div style=\"font-family:sans-serif;max-width:560px\"><p>").append(inner.replace("\n", "<br>")).append("</p>");
		sb.append("<table style=\"background:#f6f6f6;padding:12px;border-radius:8px\">");
		factList(v).forEach(f -> sb.append("<tr><td style=\"color:#666;padding-right:16px\">").append(HtmlUtils.htmlEscape(f.get("label")))
				.append("</td><td>").append(HtmlUtils.htmlEscape(f.get("value"))).append("</td></tr>"));
		sb.append("</table>");
		if (!docs.isEmpty()) {
			sb.append("<p>Documents to upload:</p><ul>");
			docs.forEach(d -> sb.append("<li>").append(HtmlUtils.htmlEscape(d)).append("</li>"));
			sb.append("</ul>");
		}
		if (due != null) {
			sb.append("<p>Please finish by <b>").append(due.format(DAY)).append("</b>.</p>");
		}
		return sb.append("</div>").toString();
	}

	private static String button(String link) {
		return "<p><a href=\"" + HtmlUtils.htmlEscape(link) + "\" style=\"background:#2563eb;color:#fff;padding:10px 18px;border-radius:6px;text-decoration:none\">Start onboarding</a></p>";
	}

	String linkOf(String token) {
		return settings.portalUrl() + "?token=" + token;
	}

	// ------------------------------------------------------------------ lookups

	private OnboardingConfig.InviteTemplate template(Hire h) {
		if (h.inviteTemplateId() != null) {
			OnboardingConfig.InviteTemplate t = jdbc.sql("select id, name, employment_type_id, subject, body, due_days_before, remind_every_days, cc_manager from invite_template where id = :id and org_id = :org")
					.param("id", h.inviteTemplateId()).param("org", currentUser.orgId())
					.query((rs, i) -> new OnboardingConfig.InviteTemplate(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class), rs.getString(4),
							rs.getString(5), rs.getInt(6), (Integer) rs.getObject(7), rs.getBoolean(8))).optional().orElse(null);
			if (t != null) {
				return t;
			}
		}
		return config.inviteFor(h.employmentTypeId()).orElse(null);
	}

	private List<String> docNames(UUID hireId) {
		return jdbc.sql("select od.name from onboarding_hire_document d join onboarding_document od on od.id = d.document_id where d.hire_id = :h and d.required order by d.sort")
				.param("h", hireId).query(String.class).list();
	}

	private String managerEmail(UUID managerId) {
		return jdbc.sql("select work_email from employee where id = :id").param("id", managerId).query(String.class).optional().orElse(null);
	}

	private String employeeName(UUID id) {
		return jdbc.sql("select display_name from employee where id = :id").param("id", id).query(String.class).optional().orElse("");
	}

	boolean hasInvite(UUID hireId) {
		return jdbc.sql("select count(*) from onboarding_invite where hire_id = :h and revoked_at is null").param("h", hireId).query(Long.class).single() > 0;
	}

	Map<String, Object> invite(UUID hireId) {
		return jdbc.sql("select sent_to, first_sent_at, last_sent_at, reminders, last_reminder_at, due_date, expires_at, remind_every_days from onboarding_invite where hire_id = :h and revoked_at is null")
				.param("h", hireId).query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("to", rs.getString(1));
					m.put("sentAt", rs.getObject(2, OffsetDateTime.class));
					m.put("lastSentAt", rs.getObject(3, OffsetDateTime.class));
					m.put("reminders", rs.getInt(4));
					m.put("lastReminderAt", rs.getObject(5, OffsetDateTime.class));
					m.put("dueDate", rs.getObject(6, LocalDate.class));
					m.put("expiresAt", rs.getObject(7, OffsetDateTime.class));
					m.put("remindEveryDays", rs.getObject(8));
					return m;
				}).optional().orElse(null);
	}

	/** Revokes the link (cancel, join). */
	public void revoke(UUID hireId) {
		jdbc.sql("update onboarding_invite set revoked_at = now() where hire_id = :h and revoked_at is null").param("h", hireId).update();
	}

	private static void requireOpen(Hire h) {
		if (!h.open()) {
			throw new CodedConflictException("ALREADY_JOINED", "This hire is no longer in the pipeline");
		}
	}

	private static UUID uuid(Object o) {
		try {
			return o == null || o.toString().isBlank() ? null : UUID.fromString(o.toString());
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private static String str(Object o) {
		return o == null ? "" : o.toString();
	}
}
