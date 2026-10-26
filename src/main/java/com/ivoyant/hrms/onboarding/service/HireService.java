package com.ivoyant.hrms.onboarding.service;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.security.ModuleAccess;
import com.ivoyant.hrms.common.web.CodedConflictException;
import com.ivoyant.hrms.common.web.ConfirmRequiredException;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.common.web.StaleException;
import com.ivoyant.hrms.common.web.StatusException;
import com.ivoyant.hrms.common.web.UnprocessableException;
import com.ivoyant.hrms.config.FieldConfig;
import com.ivoyant.hrms.notification.Notifier;
import com.ivoyant.hrms.onboarding.OnboardingConfig;
import com.ivoyant.hrms.onboarding.domain.Hire;
import com.ivoyant.hrms.onboarding.domain.HireStatus;
import com.ivoyant.hrms.org.EmploymentTypes;
import com.ivoyant.hrms.org.OrgDirectory;
import com.ivoyant.hrms.org.TypeView;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Creating, editing and reading a single hire; the form the drawer renders; checklist and activity. */
@Service
public class HireService {

	private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
	private static final Map<String, String> HARD_REQUIRED = Map.of("first", "First name", "pemail", "Personal email", "title", "Job title",
			"dept", "Department", "mgr", "Reports to", "loc", "Work location", "etype", "Employment type", "doj", "Date of joining");
	private static final List<String> SYSTEM_KEYS = List.of("first", "last", "pemail", "phone", "gender", "dob", "title", "dept", "mgr", "loc",
			"etype", "doj", "prob", "ctc", "contractEnd");

	private final JdbcClient jdbc;
	private final HireRepo repo;
	private final OnboardingConfig config;
	private final FieldConfig fieldConfig;
	private final OrgDirectory org;
	private final EmploymentTypes types;
	private final CurrentUser currentUser;
	private final ModuleAccess access;
	private final Audit audit;
	private final EventLog events;
	private final HireStatusService status;
	private final InviteService invites;
	private final M365Service m365;
	private final HireDocumentService docs;
	private final JobQueue queue;
	private final Notifier notifier;
	private final PortalService portal;

	HireService(JdbcClient jdbc, HireRepo repo, OnboardingConfig config, FieldConfig fieldConfig, OrgDirectory org, EmploymentTypes types,
			CurrentUser currentUser, ModuleAccess access, Audit audit, EventLog events, HireStatusService status, InviteService invites,
			M365Service m365, HireDocumentService docs, JobQueue queue, Notifier notifier, PortalService portal) {
		this.portal = portal;
		this.jdbc = jdbc;
		this.repo = repo;
		this.config = config;
		this.fieldConfig = fieldConfig;
		this.org = org;
		this.types = types;
		this.currentUser = currentUser;
		this.access = access;
		this.audit = audit;
		this.events = events;
		this.status = status;
		this.invites = invites;
		this.m365 = m365;
		this.docs = docs;
		this.queue = queue;
		this.notifier = notifier;
	}

	// ================================================================== the form

	/** What the New hire drawer renders for an employment type. */
	@Transactional(readOnly = true)
	public Map<String, Object> form(UUID employmentTypeId) {
		Map<String, Object> out = new LinkedHashMap<>();
		List<Map<String, Object>> steps = new ArrayList<>();
		for (OnboardingConfig.StepView s : config.hireSteps()) {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("key", s.key());
			m.put("name", s.name());
			m.put("kind", s.kind());
			m.put("locked", s.locked());
			m.put("fields", s.fields());
			steps.add(m);
		}
		out.put("steps", steps);
		out.put("m365Rule", employmentTypeId == null ? null : config.m365For(employmentTypeId).orElse(null));
		List<String> licenses = new ArrayList<>(M365Service.LICENSES);
		jdbc.sql("select distinct license_sku from m365_rule where org_id = :org and active").param("org", currentUser.orgId()).query(String.class).list()
				.forEach(l -> {
					if (!licenses.contains(l)) {
						licenses.add(l);
					}
				});
		out.put("licenses", licenses);
		out.put("domain", m365.domain());
		out.put("documents", employmentTypeId == null ? List.of() : config.documentsFor(employmentTypeId));
		out.put("invite", employmentTypeId == null ? null : config.inviteFor(employmentTypeId).orElse(null));
		return out;
	}

	// ================================================================== create

	@Transactional
	@SuppressWarnings("unchecked")
	public Map<String, Object> create(Map<String, Object> body, boolean sendInvite) {
		Map<String, Object> v = body.get("values") instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
		Map<String, Object> extra = body.get("extra") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
		Parsed p = parse(v, null);
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				insert into onboarding_hire (id, org_id, first_name, last_name, personal_email, phone, gender, dob, title, title_id, department_id, manager_id,
				  location_id, employment_type_id, doj, probation_months, ctc_lakh, contract_end_date, invite_template_id, created_by, updated_by)
				values (:id, :org, :first, :last, :pemail, :phone, :gender, :dob, :title, :titleId, :dept, :mgr, :loc, :etype, :doj, :prob, :ctc, :cend, :tpl, :by, :by)""")
				.param("id", id).param("org", currentUser.orgId()).param("first", p.first).param("last", p.last).param("pemail", p.pemail)
				.param("phone", p.phone).param("gender", p.gender).param("dob", p.dob).param("title", p.title).param("titleId", p.titleId, java.sql.Types.OTHER)
				.param("dept", p.dept).param("mgr", p.mgr).param("loc", p.loc).param("etype", p.etype).param("doj", p.doj)
				.param("prob", p.prob, java.sql.Types.SMALLINT).param("ctc", canWriteCtc() ? p.ctc : null, java.sql.Types.NUMERIC)
				.param("cend", p.contractEnd).param("tpl", uuid(body.get("inviteTemplateId")), java.sql.Types.OTHER)
				.param("by", currentUser.employeeId(), java.sql.Types.OTHER).update();
		if (!extra.isEmpty()) {
			fieldConfig.write("onboarding", id, null, extra, false);
		}
		snapshotDocuments(id, p.etype, body.get("documents"));
		copyChecklist(id);
		if (body.get("m365") instanceof Map<?, ?> mm) {
			Map<String, Object> m365Body = (Map<String, Object>) mm;
			Object upn = m365Body.get("upnUser");
			if (upn != null && !upn.toString().isBlank()) {
				if (Boolean.TRUE.equals(m365Body.get("provisionNow"))) {
					m365.create(id, m365Body);
				}
				else {
					saveM365Plan(id, m365Body, p);
				}
			}
		}
		events.add(id, "GEN", "Hire created", "By HR");
		Hire created = repo.require(id);
		audit.created("onboarding", "Hire", id, Map.of("name", created.name(), "doj", created.doj().toString()));
		status.recompute(id);
		if (sendInvite) {
			invites.send(id);
		}
		return detail(id);
	}

	/** An account planned at create time but not started: kept as state NONE so edit/preview can show it. */
	private void saveM365Plan(UUID hireId, Map<String, Object> b, Parsed p) {
		String user = b.get("upnUser").toString().trim().toLowerCase();
		if (!m365.free(user, hireId)) {
			throw new StatusException(HttpStatus.CONFLICT, "UPN_TAKEN",
					"That work email is already taken", Map.of("suggestion", String.valueOf(Upn.firstFree(user, u -> m365.free(u, hireId)))));
		}
		OnboardingConfig.M365Rule rule = config.m365For(p.etype).orElse(null);
		String license = b.get("license") == null ? (rule == null ? M365Service.LICENSES.get(1) : rule.licenseSku()) : b.get("license").toString();
		List<String> groups = b.get("groups") instanceof List<?> l ? l.stream().map(Object::toString).toList() : List.of("All staff");
		jdbc.sql("""
				insert into onboarding_m365 (hire_id, upn, license_sku, groups, force_reset, send_sms, state)
				values (:h, :upn, :lic, :g::jsonb, :fr, :sms, 'NONE')""").param("h", hireId).param("upn", user + "@" + m365.domain()).param("lic", license)
				.param("g", "[" + String.join(",", groups.stream().map(s -> "\"" + s.replace("\"", "\\\"") + "\"").toList()) + "]")
				.param("fr", !(b.get("forceReset") instanceof Boolean f) || f).param("sms", !(b.get("sendSms") instanceof Boolean s) || s).update();
	}

	private void snapshotDocuments(UUID hireId, UUID etype, Object requested) {
		Map<String, OnboardingConfig.DocumentView> lib = new LinkedHashMap<>();
		config.documentsFor(etype).forEach(d -> lib.put(d.key(), d));
		int sort = 10;
		if (requested instanceof List<?> list) {
			for (Object o : list) {
				if (o instanceof Map<?, ?> dm && dm.get("key") != null && lib.containsKey(dm.get("key").toString())) {
					OnboardingConfig.DocumentView d = lib.remove(dm.get("key").toString());
					boolean required = dm.get("required") instanceof Boolean b ? b : d.required();
					insertDoc(hireId, d, required, sort);
					sort += 10;
				}
			}
		}
		else {
			for (OnboardingConfig.DocumentView d : lib.values()) {
				insertDoc(hireId, d, d.required(), sort);
				sort += 10;
			}
		}
	}

	private void insertDoc(UUID hireId, OnboardingConfig.DocumentView d, boolean required, int sort) {
		jdbc.sql("insert into onboarding_hire_document (hire_id, document_id, doc_key, required, sort, requested_by) values (:h, :d, :k, :r, :s, :by)")
				.param("h", hireId).param("d", d.id()).param("k", d.key()).param("r", required).param("s", sort)
				.param("by", currentUser.employeeId(), java.sql.Types.OTHER).update();
	}

	private void copyChecklist(UUID hireId) {
		int sort = 10;
		for (OnboardingConfig.TaskTemplate t : config.checklist()) {
			jdbc.sql("insert into onboarding_task (hire_id, template_id, name, phase, owner, sort) values (:h, :t, :n, :p, :o, :s)")
					.param("h", hireId).param("t", t.id()).param("n", t.name()).param("p", t.phase()).param("o", t.owner()).param("s", sort).update();
			sort += 10;
		}
	}

	// ================================================================== parsing and validation

	/** The values of the create/edit body, checked. {@code existing} is the hire being edited (its own email is allowed). */
	private static final class Parsed {
		String first;
		String last;
		String pemail;
		String phone;
		String gender;
		LocalDate dob;
		String title;
		UUID titleId;
		UUID dept;
		UUID mgr;
		UUID loc;
		UUID etype;
		LocalDate doj;
		Integer prob;
		BigDecimal ctc;
		LocalDate contractEnd;
	}

	private Parsed parse(Map<String, Object> v, Hire existing) {
		List<ErrorItem> errors = new ArrayList<>();
		Set<String> requiredKeys = new java.util.LinkedHashSet<>(HARD_REQUIRED.keySet());
		Map<String, String> labels = new LinkedHashMap<>(HARD_REQUIRED);
		for (OnboardingConfig.StepView s : config.hireSteps()) {
			for (OnboardingConfig.FieldView f : s.fields()) {
				if (f.required() && !"BOOLEAN".equals(f.type()) && SYSTEM_KEYS.contains(f.key())) {
					requiredKeys.add(f.key());
					labels.put(f.key(), f.label());
				}
			}
		}
		for (String k : requiredKeys) {
			if (blank(v.get(k))) {
				errors.add(ErrorItem.field(k, "REQUIRED", labels.get(k) + " is required"));
			}
		}
		Parsed p = new Parsed();
		p.first = text(v.get("first"));
		p.last = text(v.get("last"));
		p.pemail = text(v.get("pemail"));
		p.phone = text(v.get("phone"));
		p.gender = text(v.get("gender"));
		p.title = text(v.get("title"));
		if (p.pemail != null) {
			p.pemail = p.pemail.toLowerCase();
			if (!EMAIL.matcher(p.pemail).matches()) {
				errors.add(ErrorItem.field("pemail", "INVALID_FORMAT", "Enter a valid email address"));
			}
		}
		p.dob = date(v.get("dob"), "dob", errors);
		p.doj = date(v.get("doj"), "doj", errors);
		p.contractEnd = date(v.get("contractEnd"), "contractEnd", errors);
		if (p.doj != null && p.doj.isBefore(status.today().minusDays(30)) && (existing == null || !p.doj.equals(existing.doj()))) {
			errors.add(ErrorItem.field("doj", "INVALID", "The joining date cannot be more than 30 days in the past"));
		}
		p.dept = id(v.get("dept"), "dept", errors);
		p.mgr = id(v.get("mgr"), "mgr", errors);
		p.loc = id(v.get("loc"), "loc", errors);
		p.etype = id(v.get("etype"), "etype", errors);
		try {
			p.prob = blank(v.get("prob")) ? null : Integer.valueOf(v.get("prob").toString().trim());
		}
		catch (NumberFormatException ex) {
			errors.add(ErrorItem.field("prob", "INVALID_FORMAT", "Probation is a number of months"));
		}
		try {
			p.ctc = blank(v.get("ctc")) ? null : new BigDecimal(v.get("ctc").toString().trim());
		}
		catch (NumberFormatException ex) {
			errors.add(ErrorItem.field("ctc", "INVALID_FORMAT", "CTC is a number"));
		}
		if (p.last == null) {
			p.last = "";
		}
		if (errors.isEmpty()) {
			if (p.dept != null && org.activeDepartmentNames(List.of(p.dept)).isEmpty()) {
				errors.add(ErrorItem.field("dept", "INVALID", "Choose an active department"));
			}
			if (p.loc != null && org.activeLocationNames(List.of(p.loc)).isEmpty()) {
				errors.add(ErrorItem.field("loc", "INVALID", "Choose an active location"));
			}
			if (p.etype != null) {
				TypeView t = types.get(p.etype).orElse(null);
				if (t == null || !t.active()) {
					errors.add(ErrorItem.field("etype", "INVALID", "Choose an active employment type"));
				}
				else if (t.fixedTerm() && (p.contractEnd == null || !p.contractEnd.isAfter(p.doj))) {
					errors.add(ErrorItem.field("contractEnd", "CONTRACT_END_REQUIRED", t.name() + " needs a contract end date after the joining date"));
				}
			}
			if (p.mgr != null && jdbc.sql("select count(*) from employee where id = :id and org_id = :org and status <> 'EXITED'")
					.param("id", p.mgr).param("org", currentUser.orgId()).query(Long.class).single() == 0) {
				errors.add(ErrorItem.field("mgr", "INVALID", "Reports to must be an active employee"));
			}
		}
		if (!errors.isEmpty()) {
			throw new UnprocessableException(errors);
		}
		if (existing == null || !p.pemail.equalsIgnoreCase(existing.personalEmail())) {
			if (jdbc.sql("select count(*) from onboarding_hire where org_id = :org and lower(personal_email) = :e and joined_at is null and cancelled_at is null and id <> :self")
					.param("org", currentUser.orgId()).param("e", p.pemail).param("self", existing == null ? new UUID(0, 0) : existing.id()).query(Long.class).single() > 0) {
				throw new CodedConflictException("EMAIL_TAKEN", "Another new hire already uses that personal email");
			}
			if (jdbc.sql("select count(*) from employee where org_id = :org and lower(personal_email) = :e").param("org", currentUser.orgId()).param("e", p.pemail)
					.query(Long.class).single() > 0) {
				throw new CodedConflictException("ALREADY_EMPLOYEE", "That personal email belongs to an existing employee");
			}
		}
		p.titleId = jdbc.sql("select id from job_title where org_id = :org and active and lower(name) = lower(:t) limit 1")
				.param("org", currentUser.orgId()).param("t", p.title).query(UUID.class).optional().orElse(null);
		return p;
	}

	private LocalDate date(Object o, String key, List<ErrorItem> errors) {
		if (blank(o)) {
			return null;
		}
		try {
			return LocalDate.parse(o.toString().trim());
		}
		catch (DateTimeParseException ex) {
			errors.add(ErrorItem.field(key, "INVALID_FORMAT", "Use the date format yyyy-mm-dd"));
			return null;
		}
	}

	private UUID id(Object o, String key, List<ErrorItem> errors) {
		if (blank(o)) {
			return null;
		}
		try {
			return UUID.fromString(o.toString().trim());
		}
		catch (IllegalArgumentException ex) {
			errors.add(ErrorItem.field(key, "INVALID_FORMAT", "Choose a value from the list"));
			return null;
		}
	}

	private boolean canWriteCtc() {
		return access.has("payroll", "EDIT");
	}

	private boolean canViewCtc() {
		return access.has("payroll", "VIEW");
	}

	// ================================================================== detail

	@Transactional(readOnly = true)
	public Map<String, Object> detail(UUID id) {
		Hire h = repo.require(id);
		Map<String, Object> out = new LinkedHashMap<>(HireQueryService.row(jdbc, h, status.today(), docs.counts(id), m365State(id), invites.invite(id)));
		Map<String, Object> job = new LinkedHashMap<>();
		job.put("employmentType", out.get("employmentType"));
		job.put("department", out.get("department"));
		job.put("location", out.get("location"));
		job.put("reportsTo", Map.of("id", h.managerId(), "name", jdbc.sql("select display_name from employee where id = :id").param("id", h.managerId()).query(String.class).optional().orElse("")));
		job.put("doj", h.doj());
		job.put("probationMonths", h.probationMonths());
		job.put("contractEnd", h.contractEnd());
		job.put("ctcLakh", canViewCtc() ? h.ctcLakh() : null);
		job.put("personalEmail", h.personalEmail());
		job.put("phone", h.phone());
		job.put("gender", h.gender());
		job.put("dob", h.dob());
		out.put("job", job);
		out.put("version", h.version());
		out.put("extraPanel", fieldConfig.render("onboarding", id, null));
		out.put("m365", m365.view(id));
		out.put("invite", invites.invite(id));
		out.put("stagesDetail", stagesDetail(h));
		out.put("employeeId", h.employeeId());
		out.put("cancelledAt", h.cancelledAt());
		out.put("permissions", Map.of("canEdit", access.has("recruiting", "EDIT"), "canJoin", access.has("recruiting", "ADMIN"),
				"canCancel", access.has("recruiting", "ADMIN"), "canViewCtc", canViewCtc()));
		return out;
	}

	private String m365State(UUID id) {
		return jdbc.sql("select state from onboarding_m365 where hire_id = :h").param("h", id).query(String.class).optional().orElse("NONE");
	}

	private List<Map<String, Object>> stagesDetail(Hire h) {
		Map<String, Object> docCounts = docs.counts(h.id());
		Map<String, Object> inv = invites.invite(h.id());
		String m = m365State(h.id());
		List<Map<String, Object>> out = new ArrayList<>();
		out.add(stage("Profile", "Created by HR"));
		out.add(stage("Microsoft 365", switch (m) {
			case "ACTIVE" -> "Account ready";
			case "RUNNING" -> "Creating…";
			case "FAILED" -> "Failed";
			default -> "Not created";
		}));
		out.add(stage("Invite", inv == null ? "Not sent" : "Sent " + ((OffsetDateTime) inv.get("sentAt")).toLocalDate()));
		out.add(stage("Details & docs", h.detailsSubmittedAt() != null ? "Submitted" : "Waiting"));
		out.add(stage("HR verification", docCounts.get("verified") + " of " + docCounts.get("required") + " verified"));
		out.add(stage("Day 1", h.doj().toString()));
		return out;
	}

	private static Map<String, Object> stage(String label, String sub) {
		return Map.of("label", label, "sub", sub);
	}

	// ================================================================== edit

	@Transactional
	public Map<String, Object> update(UUID id, Map<String, Object> patch, String ifMatch, boolean force) {
		Hire h = repo.lock(id);
		if (!h.open()) {
			throw new CodedConflictException("ALREADY_JOINED", "This hire is no longer in the pipeline");
		}
		if (ifMatch != null && !ifMatch.isBlank() && !ifMatch.replace("W/", "").replace("\"", "").trim().equals(String.valueOf(h.version()))) {
			throw new StaleException("This hire was changed by someone else. Reload and try again.");
		}
		Map<String, Object> merged = new LinkedHashMap<>();
		merged.put("first", h.first());
		merged.put("last", h.last());
		merged.put("pemail", h.personalEmail());
		merged.put("phone", h.phone());
		merged.put("gender", h.gender());
		merged.put("dob", h.dob() == null ? null : h.dob().toString());
		merged.put("title", h.title());
		merged.put("dept", h.departmentId().toString());
		merged.put("mgr", h.managerId().toString());
		merged.put("loc", h.locationId().toString());
		merged.put("etype", h.employmentTypeId().toString());
		merged.put("doj", h.doj().toString());
		merged.put("prob", h.probationMonths());
		merged.put("ctc", h.ctcLakh());
		merged.put("contractEnd", h.contractEnd() == null ? null : h.contractEnd().toString());
		@SuppressWarnings("unchecked")
		Map<String, Object> values = patch.get("values") instanceof Map<?, ?> m ? (Map<String, Object>) m : patch;
		for (String k : SYSTEM_KEYS) {
			if (values.containsKey(k)) {
				merged.put(k, values.get(k));
			}
		}
		Parsed p = parse(merged, h);
		boolean emailChanged = !p.pemail.equalsIgnoreCase(h.personalEmail());
		boolean invited = invites.hasInvite(id);
		if (emailChanged && invited && !force) {
			throw new ConfirmRequiredException("The invite went to " + h.personalEmail() + ". Changing the email sends a new invite there.",
					List.of("The old link stops working", "A new invite is sent to " + p.pemail));
		}
		BigDecimal ctc = canWriteCtc() ? p.ctc : h.ctcLakh();
		jdbc.sql("""
				update onboarding_hire set first_name = :first, last_name = :last, personal_email = :pemail, phone = :phone, gender = :gender, dob = :dob,
				  title = :title, title_id = :titleId, department_id = :dept, manager_id = :mgr, location_id = :loc, employment_type_id = :etype, doj = :doj,
				  probation_months = :prob, ctc_lakh = :ctc, contract_end_date = :cend, version = version + 1, updated_at = now(), updated_by = :by
				where id = :id""").param("first", p.first).param("last", p.last).param("pemail", p.pemail).param("phone", p.phone).param("gender", p.gender)
				.param("dob", p.dob).param("title", p.title).param("titleId", p.titleId, java.sql.Types.OTHER).param("dept", p.dept).param("mgr", p.mgr)
				.param("loc", p.loc).param("etype", p.etype).param("doj", p.doj).param("prob", p.prob, java.sql.Types.SMALLINT)
				.param("ctc", ctc, java.sql.Types.NUMERIC).param("cend", p.contractEnd).param("by", currentUser.employeeId(), java.sql.Types.OTHER)
				.param("id", id).update();
		if (patch.get("extra") instanceof Map<?, ?> ex && !ex.isEmpty()) {
			@SuppressWarnings("unchecked")
			Map<String, Object> extra = (Map<String, Object>) ex;
			fieldConfig.write("onboarding", id, null, extra, false);
		}
		Map<String, Object> diff = new LinkedHashMap<>();
		if (!h.name().equals((p.first + " " + p.last).trim())) {
			diff.put("name", p.first + " " + p.last);
		}
		if (!h.doj().equals(p.doj)) {
			diff.put("doj", p.doj.toString());
		}
		if (emailChanged) {
			diff.put("personalEmail", p.pemail);
		}
		events.add(id, "GEN", "Details updated", diff.isEmpty() ? null : String.join(", ", diff.keySet()));
		audit.updated("onboarding", "Hire", id, Map.of("doj", h.doj().toString(), "email", h.personalEmail()), diff);
		if (!h.doj().equals(p.doj) && invited) {
			notifier.email(p.pemail, null, "Your joining date is now " + p.doj, "<p>Hi " + p.first + ", your joining date changed to " + p.doj + ".</p>");
			jdbc.sql("update onboarding_invite set due_date = due_date + (:d::date - :o::date) where hire_id = :h").param("d", p.doj).param("o", h.doj()).param("h", id).update();
		}
		if (emailChanged && invited) {
			invites.resend(id);
		}
		status.recompute(id);
		return detail(id);
	}

	// ================================================================== cancel

	@Transactional
	public void cancel(UUID id, String reason) {
		Hire h = repo.lock(id);
		if (!h.open()) {
			throw new CodedConflictException("ALREADY_JOINED", "This hire is no longer in the pipeline");
		}
		if (reason == null || reason.isBlank()) {
			throw new UnprocessableException(List.of(ErrorItem.field("reason", "REASON_REQUIRED", "Add a reason")));
		}
		jdbc.sql("update onboarding_hire set cancelled_at = now(), cancel_reason = :r, version = version + 1, updated_at = now() where id = :id")
				.param("r", reason.trim()).param("id", id).update();
		invites.revoke(id);
		m365.disable(id);
		jdbc.sql("update onboarding_task set done = false where hire_id = :h and not done").param("h", id).update();
		events.add(id, "BAD", "Hire cancelled", reason.trim());
		audit.publish("onboarding", "Hire", id, "CANCEL", null, reason.trim());
	}

	// ================================================================== checklist

	@Transactional(readOnly = true)
	public List<Map<String, Object>> tasks(UUID hireId) {
		repo.require(hireId);
		return jdbc.sql("""
				select t.id, t.name, t.phase, t.owner, t.done, t.done_at, t.assignee_id, e.display_name
				from onboarding_task t left join employee e on e.id = t.assignee_id where t.hire_id = :h
				order by case t.phase when 'BEFORE_DAY_1' then 1 when 'DAY_1' then 2 else 3 end, t.sort""").param("h", hireId).query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("id", rs.getObject(1, UUID.class));
					m.put("name", rs.getString(2));
					m.put("phase", rs.getString(3));
					m.put("owner", rs.getString(4));
					m.put("done", rs.getBoolean(5));
					m.put("doneAt", rs.getObject(6, OffsetDateTime.class));
					m.put("assignee", rs.getObject(7) == null ? null : Map.of("id", rs.getObject(7, UUID.class), "name", rs.getString(8)));
					return m;
				}).list();
	}

	@Transactional
	public Map<String, Object> setTask(UUID hireId, UUID taskId, boolean done) {
		repo.require(hireId);
		int n = jdbc.sql("""
				update onboarding_task set done = :d, done_at = case when :d then now() else null end, done_by = case when :d then :by else null end
				where id = :id and hire_id = :h""").param("d", done).param("by", currentUser.employeeId(), java.sql.Types.OTHER).param("id", taskId).param("h", hireId).update();
		if (n == 0) {
			throw new NotFoundException("Task " + taskId + " not found");
		}
		String name = jdbc.sql("select name from onboarding_task where id = :id").param("id", taskId).query(String.class).single();
		events.add(hireId, done ? "OK" : "GEN", name + (done ? " done" : " reopened"), null);
		return Map.of("id", taskId, "done", done);
	}

	/** Adds the templates this hire does not have yet. */
	@Transactional
	public Map<String, Object> syncTasks(UUID hireId) {
		repo.require(hireId);
		int added = 0;
		int sort = jdbc.sql("select coalesce(max(sort), 0) + 10 from onboarding_task where hire_id = :h").param("h", hireId).query(Integer.class).single();
		for (OnboardingConfig.TaskTemplate t : config.checklist()) {
			if (jdbc.sql("select count(*) from onboarding_task where hire_id = :h and template_id = :t").param("h", hireId).param("t", t.id()).query(Long.class).single() == 0) {
				jdbc.sql("insert into onboarding_task (hire_id, template_id, name, phase, owner, sort) values (:h, :t, :n, :p, :o, :s)")
						.param("h", hireId).param("t", t.id()).param("n", t.name()).param("p", t.phase()).param("o", t.owner()).param("s", sort).update();
				sort += 10;
				added++;
			}
		}
		if (added > 0) {
			events.add(hireId, "GEN", added + " checklist tasks added", null);
		}
		return Map.of("added", added);
	}

	// ================================================================== activity

	@Transactional(readOnly = true)
	public Map<String, Object> events(UUID hireId, Long before) {
		repo.require(hireId);
		List<Map<String, Object>> items = jdbc.sql("""
				select ev.id, ev.kind, ev.title, ev.description, ev.at, ev.actor_is_hire, e.display_name
				from onboarding_event ev left join employee e on e.id = ev.actor_id
				where ev.hire_id = :h and (:before::bigint is null or ev.id < :before) order by ev.id desc limit 50""")
				.param("h", hireId).param("before", before, java.sql.Types.BIGINT).query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("id", rs.getLong(1));
					m.put("kind", rs.getString(2));
					m.put("title", rs.getString(3));
					m.put("description", rs.getString(4));
					m.put("at", rs.getObject(5, OffsetDateTime.class));
					m.put("by", rs.getBoolean(6) ? "Hire" : rs.getString(7));
					return m;
				}).list();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("items", items);
		out.put("next", items.size() == 50 ? items.get(49).get("id") : null);
		return out;
	}

	/** What the new hire sees, for HR to look at (read-only, masked where the caller may not see the field). */
	@Transactional(readOnly = true)
	public Map<String, Object> portalPreview(UUID hireId) {
		return portal.preview(hireId, this::unmasked);
	}

	/** Portal values for HR, masked where the caller may not see the target profile field. */
	@Transactional
	public List<Map<String, Object>> details(UUID hireId) {
		repo.require(hireId);
		return portal.hrDetails(hireId, this::unmasked);
	}

	private boolean unmasked(String key) {
		try {
			return fieldConfig.levelFor("employee_profile", key, null).atLeast(com.ivoyant.hrms.config.FieldAccess.VIEW);
		}
		catch (RuntimeException ex) {
			return false;
		}
	}

	// ================================================================== helpers

	private static boolean blank(Object o) {
		return o == null || o.toString().isBlank();
	}

	private static String text(Object o) {
		return blank(o) ? null : o.toString().trim();
	}

	private static UUID uuid(Object o) {
		try {
			return blank(o) ? null : UUID.fromString(o.toString());
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	/** Used by the status helper for the stage names in responses. */
	public static List<String> stageNames() {
		return HireStatus.STAGES;
	}
}
