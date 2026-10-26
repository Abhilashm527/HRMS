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
import com.ivoyant.hrms.onboarding.m365.M365Client;
import com.ivoyant.hrms.org.OrgDirectory;
import com.ivoyant.hrms.org.OrgProfile;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * The work account of a hire in Microsoft 365: suggest and check the sign-in name, create it asynchronously in four
 * visible steps, reset and resend credentials (by SMS only; the temporary password is never stored), disable on cancel.
 */
@Service
public class M365Service {

	private static final Logger log = LoggerFactory.getLogger(M365Service.class);
	static final List<String> STEPS = List.of("Creating user in Entra ID", "Assigning license", "Adding to groups",
			"Setting up mailbox & Teams");
	static final List<String> LICENSES = List.of("Microsoft 365 Business Basic", "Microsoft 365 Business Standard",
			"Microsoft 365 Business Premium", "Microsoft 365 E3");
	private static final String ALL_STAFF = "All staff";
	private static final SecureRandom RANDOM = new SecureRandom();

	private final JdbcClient jdbc;
	private final HireRepo repo;
	private final M365Client client;
	private final OnboardingConfig config;
	private final OrgProfile orgProfile;
	private final OrgDirectory org;
	private final CurrentUser currentUser;
	private final Notifier notifier;
	private final EventLog events;
	private final Audit audit;
	private final JobQueue queue;
	private final HireStatusService status;
	private final HrOwners owners;
	private final TransactionTemplate tx;
	private final ObjectMapper json;

	M365Service(JdbcClient jdbc, HireRepo repo, M365Client client, OnboardingConfig config, OrgProfile orgProfile,
			OrgDirectory org, CurrentUser currentUser, Notifier notifier, EventLog events, Audit audit, JobQueue queue,
			HireStatusService status, HrOwners owners, PlatformTransactionManager txManager, ObjectMapper json) {
		this.jdbc = jdbc;
		this.repo = repo;
		this.client = client;
		this.config = config;
		this.orgProfile = orgProfile;
		this.org = org;
		this.currentUser = currentUser;
		this.notifier = notifier;
		this.events = events;
		this.audit = audit;
		this.queue = queue;
		this.status = status;
		this.owners = owners;
		this.tx = new TransactionTemplate(txManager);
		this.json = json;
	}

	// ------------------------------------------------------------------ names

	public String domain() {
		String d = orgProfile.current().primaryDomain();
		return d == null || d.isBlank() ? "ivoyant.com" : d;
	}

	/** Is the full sign-in name free: no employee, no other active hire account, nothing in the tenant. */
	boolean free(String upnUser, UUID exceptHire) {
		String upn = upnUser + "@" + domain();
		boolean employee = jdbc.sql("select count(*) from employee where lower(work_email) = lower(:u)").param("u", upn).query(Long.class).single() > 0;
		boolean hire = jdbc.sql("select count(*) from onboarding_m365 where lower(upn) = lower(:u) and state <> 'DISABLED' and hire_id <> :h")
				.param("u", upn).param("h", exceptHire == null ? new UUID(0, 0) : exceptHire).query(Long.class).single() > 0;
		return !employee && !hire && client.upnFree(upn);
	}

	public Map<String, Object> check(String user, UUID exceptHire) {
		String clean = user == null ? "" : user.trim().toLowerCase();
		boolean ok = !clean.isEmpty() && clean.matches("[a-z0-9._-]+") && free(clean, exceptHire);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("upn", clean + "@" + domain());
		out.put("available", ok);
		out.put("suggestion", ok ? null : Upn.firstFree(clean, u -> free(u, exceptHire)));
		return out;
	}

	public Map<String, Object> suggest(String first, String last, UUID employmentTypeId) {
		String format = config.m365For(employmentTypeId).map(OnboardingConfig.M365Rule::upnFormat).orElse("first.last");
		String user = Upn.user(first, last, format);
		String free = Upn.firstFree(user, u -> free(u, null));
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("user", user);
		out.put("available", user.equals(free));
		out.put("suggestion", free);
		return out;
	}

	// ------------------------------------------------------------------ create

	/** Stores the account request (state RUNNING) and queues provisioning. */
	@Transactional
	public void create(UUID hireId, Map<String, Object> body) {
		Hire h = repo.lock(hireId);
		if (!h.open()) {
			throw new CodedConflictException("ALREADY_JOINED", "This hire is no longer in the pipeline");
		}
		String state = jdbc.sql("select state from onboarding_m365 where hire_id = :h").param("h", hireId).query(String.class).optional().orElse("NONE");
		if (!state.equals("NONE") && !state.equals("DISABLED")) {
			throw new CodedConflictException("ALREADY_PROVISIONED", "This hire already has a work account");
		}
		Request r = Request.of(body, h, config, this);
		String upn = r.upnUser() + "@" + domain();
		if (!free(r.upnUser(), hireId)) {
			throw new StatusException(HttpStatus.CONFLICT, "UPN_TAKEN", "That work email is already taken",
					Map.of("suggestion", String.valueOf(Upn.firstFree(r.upnUser(), u -> free(u, hireId)))));
		}
		List<String> groups = resolveGroups(r.groups(), h);
		jdbc.sql("""
				insert into onboarding_m365 (hire_id, upn, license_sku, groups, force_reset, send_sms, state, step, error, attempts, entra_user_id, simulated, updated_at)
				values (:h, :upn, :lic, :g::jsonb, :fr, :sms, 'RUNNING', 0, null, 0, null, :sim, now())
				on conflict (hire_id) do update set upn = :upn, license_sku = :lic, groups = :g::jsonb, force_reset = :fr, send_sms = :sms,
				  state = 'RUNNING', step = 0, error = null, attempts = 0, entra_user_id = null, simulated = :sim, updated_at = now()""")
				.param("h", hireId).param("upn", upn).param("lic", r.license()).param("g", toJson(groups)).param("fr", r.forceReset())
				.param("sms", r.sendSms()).param("sim", client.simulated()).update();
		queue.enqueue("M365_PROVISION", hireId, OffsetDateTime.now());
		events.add(hireId, "M365", "Work account requested", upn);
		audit.publish("onboarding", "HireM365", hireId, "CREATE", null, Map.of("upn", upn, "license", r.license()));
	}

	record Request(String upnUser, String license, List<String> groups, boolean forceReset, boolean sendSms) {

		@SuppressWarnings("unchecked")
		static Request of(Map<String, Object> b, Hire h, OnboardingConfig config, M365Service svc) {
			OnboardingConfig.M365Rule rule = config.m365For(h.employmentTypeId()).orElse(null);
			String user = b.get("upnUser") == null ? "" : b.get("upnUser").toString().trim().toLowerCase();
			if (user.isEmpty()) {
				user = Upn.user(h.first(), h.last(), rule == null ? "first.last" : rule.upnFormat());
			}
			if (!user.matches("[a-z0-9._-]+")) {
				throw new UnprocessableException(List.of(ErrorItem.field("upnUser", "INVALID_FORMAT", "Use letters, digits, dots, dashes or underscores")));
			}
			String license = b.get("license") == null ? (rule == null ? LICENSES.get(1) : rule.licenseSku()) : b.get("license").toString();
			List<String> groups = b.get("groups") instanceof List<?> l ? new ArrayList<>((List<String>) l)
					: new ArrayList<>(rule == null ? List.of(ALL_STAFF) : rule.groups());
			boolean force = b.get("forceReset") instanceof Boolean f ? f : rule == null || rule.forceReset();
			boolean sms = !(b.get("sendSms") instanceof Boolean s) || s;
			return new Request(user, license, groups, force, sms);
		}
	}

	/** {department} and {location} (first word of the name) become real names; "All staff" is always in. */
	List<String> resolveGroups(List<String> groups, Hire h) {
		String dept = org.departmentNames(List.of(h.departmentId())).getOrDefault(h.departmentId(), "");
		String loc = org.locationNames(List.of(h.locationId())).getOrDefault(h.locationId(), "");
		String city = loc.isBlank() ? "" : loc.split("\\s+")[0];
		Set<String> out = new LinkedHashSet<>();
		out.add(ALL_STAFF);
		for (String g : groups) {
			String v = g.replace("{department}", dept).replace("{location}", city).trim();
			if (!v.isEmpty()) {
				out.add(v);
			}
		}
		return new ArrayList<>(out);
	}

	// ------------------------------------------------------------------ run (background)

	/** Runs the remaining steps; each step commits on its own so a poller sees progress. */
	public void run(UUID hireId, int attempt) {
		Hire h = repo.find(hireId).orElse(null);
		Row row = row(hireId);
		if (h == null || row == null || !"RUNNING".equals(row.state())) {
			return;
		}
		String password = password();
		try {
			String entra = row.entraUserId();
			boolean enabled = !h.doj().isAfter(status.today().plusDays(1));
			if (entra == null) {
				entra = client.createUser(row.upn(), h.name(), password, row.forceReset(), enabled);
				save(hireId, 1, entra);
			}
			else {
				client.resetPassword(entra, password, row.forceReset());
			}
			if (row.step() < 2) {
				client.assignLicense(entra, row.license());
				save(hireId, 2, entra);
			}
			if (row.step() < 3) {
				client.addToGroups(entra, row.groups());
				save(hireId, 3, entra);
			}
			if (row.step() < 4) {
				client.awaitMailbox(entra);
				save(hireId, 4, entra);
			}
			String finalEntra = entra;
			tx.executeWithoutResult(t -> {
				jdbc.sql("update onboarding_m365 set state = 'ACTIVE', step = 4, error = null, created_at = now(), updated_at = now() where hire_id = :h")
						.param("h", hireId).update();
				events.add(hireId, "M365", "Work account created", row.upn() + " · " + row.license());
				status.recompute(hireId);
				log.info("m365 account {} active for hire {} ({})", row.upn(), hireId, finalEntra);
			});
			if (row.sendSms() && h.phone() != null && !h.phone().isBlank()) {
				notifier.sms(h.phone(), "Your Ivoyant sign-in: " + row.upn() + " · temporary password " + password
						+ (row.forceReset() ? " (you will be asked to change it)" : ""));
				events.add(hireId, "M365", "Sign-in details sent by SMS", "To " + h.phone());
			}
		}
		catch (RuntimeException ex) {
			String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
			boolean retry = attempt + 1 < 3 && !(ex instanceof M365Client.GroupNotFound);
			tx.executeWithoutResult(t -> {
				jdbc.sql("update onboarding_m365 set state = :s, error = :e, attempts = attempts + 1, updated_at = now() where hire_id = :h")
						.param("s", retry ? "RUNNING" : "FAILED").param("e", message).param("h", hireId).update();
				if (!retry) {
					events.add(hireId, "BAD", "Work account failed", message);
					notifier.inApp(owners.owners(), "Work account for " + h.name() + " failed", message, "/onboarding/" + hireId);
				}
			});
			if (retry) {
				queue.enqueue("M365_PROVISION", hireId, OffsetDateTime.now().plusSeconds(30L * (attempt + 1)));
			}
		}
	}

	private void save(UUID hireId, int step, String entraId) {
		tx.executeWithoutResult(t -> jdbc.sql("update onboarding_m365 set step = :s, entra_user_id = :e, updated_at = now() where hire_id = :h")
				.param("s", step).param("e", entraId).param("h", hireId).update());
	}

	// ------------------------------------------------------------------ reads and actions

	record Row(String upn, String license, List<String> groups, boolean forceReset, boolean sendSms, String state, int step,
			String error, String entraUserId, OffsetDateTime createdAt, boolean simulated) {
	}

	Row row(UUID hireId) {
		return jdbc.sql("select upn, license_sku, groups::text, force_reset, send_sms, state, step, error, entra_user_id, created_at, simulated from onboarding_m365 where hire_id = :h")
				.param("h", hireId).query((rs, i) -> new Row(rs.getString(1), rs.getString(2), fromJson(rs.getString(3)), rs.getBoolean(4),
						rs.getBoolean(5), rs.getString(6), rs.getInt(7), rs.getString(8), rs.getString(9),
						rs.getObject(10, OffsetDateTime.class), rs.getBoolean(11))).optional().orElse(null);
	}

	@Transactional(readOnly = true)
	public Map<String, Object> view(UUID hireId) {
		Hire h = repo.require(hireId);
		Row r = row(hireId);
		Map<String, Object> out = new LinkedHashMap<>();
		if (r == null) {
			out.put("state", "NONE");
			out.put("steps", steps(0, "NONE"));
			return out;
		}
		out.put("state", r.state());
		out.put("upn", r.upn());
		out.put("license", r.license());
		out.put("groups", r.groups());
		out.put("step", r.step());
		out.put("steps", steps(r.step(), r.state()));
		out.put("createdAt", r.createdAt());
		out.put("error", r.error());
		out.put("smsTo", r.sendSms() ? h.phone() : null);
		out.put("simulated", r.simulated());
		return out;
	}

	static List<Map<String, Object>> steps(int step, String state) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (int i = 0; i < STEPS.size(); i++) {
			Map<String, Object> s = new LinkedHashMap<>();
			s.put("label", STEPS.get(i));
			s.put("done", state.equals("ACTIVE") || i < step);
			s.put("current", state.equals("RUNNING") && i == step);
			out.add(s);
		}
		return out;
	}

	@Transactional
	public Map<String, Object> resetPassword(UUID hireId, boolean resend) {
		Hire h = repo.require(hireId);
		Row r = row(hireId);
		if (r == null || !"ACTIVE".equals(r.state())) {
			throw new CodedConflictException("NOT_ACTIVE", "The work account is not active yet");
		}
		String pwd = password();
		client.resetPassword(r.entraUserId(), pwd, true);
		if (h.phone() == null || h.phone().isBlank()) {
			throw new UnprocessableException(List.of(ErrorItem.field("phone", "REQUIRED", "Add a mobile number first")));
		}
		notifier.sms(h.phone(), "Your Ivoyant sign-in: " + r.upn() + " · temporary password " + pwd);
		events.add(hireId, "M365", resend ? "Sign-in details resent" : "Password reset", "Sent by SMS to " + h.phone());
		audit.publish("onboarding", "HireM365", hireId, resend ? "RESEND" : "RESET_PASSWORD", null, null);
		return Map.of("sentTo", h.phone());
	}

	@Transactional
	public Map<String, Object> retry(UUID hireId) {
		Row r = row(hireId);
		if (r == null || !"FAILED".equals(r.state())) {
			throw new CodedConflictException("NOT_FAILED", "Only a failed account can be retried");
		}
		jdbc.sql("update onboarding_m365 set state = 'RUNNING', error = null, attempts = 0, updated_at = now() where hire_id = :h").param("h", hireId).update();
		queue.enqueue("M365_PROVISION", hireId, OffsetDateTime.now());
		return view(hireId);
	}

	/** Disables the account and marks it DISABLED (cancel). */
	@Transactional
	public void disable(UUID hireId) {
		Row r = row(hireId);
		if (r == null || "NONE".equals(r.state()) || "DISABLED".equals(r.state())) {
			return;
		}
		if (r.entraUserId() != null) {
			client.disable(r.entraUserId());
		}
		jdbc.sql("update onboarding_m365 set state = 'DISABLED', updated_at = now() where hire_id = :h").param("h", hireId).update();
		events.add(hireId, "M365", "Work account disabled", r.upn());
	}

	/** Turns on accounts that were created switched off, once the day before joining is reached. */
	public void enableDue() {
		jdbc.sql("""
				select m.hire_id, m.entra_user_id from onboarding_m365 m join onboarding_hire h on h.id = m.hire_id
				where m.state = 'ACTIVE' and m.entra_user_id is not null and h.joined_at is null and h.cancelled_at is null and h.doj <= :d""")
				.param("d", status.today().plusDays(1)).query((rs, i) -> rs.getString(2)).list().forEach(client::enable);
	}

	private static String password() {
		String upper = "ABCDEFGHJKLMNPQRSTUVWXYZ";
		String lower = "abcdefghijkmnopqrstuvwxyz";
		String digit = "23456789";
		String all = upper + lower + digit + "!@#$%";
		StringBuilder sb = new StringBuilder();
		sb.append(upper.charAt(RANDOM.nextInt(upper.length()))).append(lower.charAt(RANDOM.nextInt(lower.length())))
				.append(digit.charAt(RANDOM.nextInt(digit.length())));
		while (sb.length() < 14) {
			sb.append(all.charAt(RANDOM.nextInt(all.length())));
		}
		return sb.toString();
	}

	private String toJson(Object o) {
		return json.writeValueAsString(o);
	}

	@SuppressWarnings("unchecked")
	private List<String> fromJson(String s) {
		return s == null ? List.of() : json.readValue(s, List.class);
	}
}
