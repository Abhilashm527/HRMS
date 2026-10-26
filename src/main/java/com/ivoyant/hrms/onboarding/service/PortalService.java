package com.ivoyant.hrms.onboarding.service;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.StatusException;
import com.ivoyant.hrms.common.web.UnprocessableException;
import com.ivoyant.hrms.notification.Notifier;
import com.ivoyant.hrms.onboarding.domain.Hire;
import com.ivoyant.hrms.org.OrgDirectory;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** What the new hire sees and does from the invite link: details (saved as they go, submitted once) and uploads. */
@Service
public class PortalService {

	private final JdbcClient jdbc;
	private final HireRepo repo;
	private final HireStatusService status;
	private final HireDocumentService docs;
	private final EventLog events;
	private final Notifier notifier;
	private final HrOwners owners;
	private final Sensitive sensitive;
	private final OrgDirectory org;
	private final CurrentUser currentUser;
	private final Audit audit;
	private final ObjectMapper json;

	PortalService(JdbcClient jdbc, HireRepo repo, HireStatusService status, HireDocumentService docs, EventLog events, Notifier notifier,
			HrOwners owners, Sensitive sensitive, OrgDirectory org, CurrentUser currentUser, Audit audit, ObjectMapper json) {
		this.jdbc = jdbc;
		this.repo = repo;
		this.status = status;
		this.docs = docs;
		this.events = events;
		this.notifier = notifier;
		this.owners = owners;
		this.sensitive = sensitive;
		this.org = org;
		this.currentUser = currentUser;
		this.audit = audit;
		this.json = json;
	}

	// ------------------------------------------------------------------ token

	/** The hire behind a valid link; throws 401 INVITE_EXPIRED or 410 ALREADY_JOINED otherwise. */
	@Transactional(readOnly = true)
	public Hire authenticate(String token) {
		if (token == null || token.isBlank()) {
			throw expired();
		}
		Map<String, Object> inv = jdbc.sql("select hire_id, expires_at, revoked_at from onboarding_invite where token_hash = :h")
				.param("h", InviteService.hash(token.trim())).query((rs, i) -> {
					Map<String, Object> m = new HashMap<>();
					m.put("hire", rs.getObject(1, UUID.class));
					m.put("expires", rs.getObject(2, OffsetDateTime.class));
					m.put("revoked", rs.getObject(3, OffsetDateTime.class));
					return m;
				}).optional().orElseThrow(PortalService::expired);
		Hire h = repo.find((UUID) inv.get("hire")).orElseThrow(PortalService::expired);
		if (h.joinedAt() != null) {
			throw new StatusException(HttpStatus.GONE, "ALREADY_JOINED", "You have already joined. Sign in with your work account.");
		}
		if (h.cancelledAt() != null || inv.get("revoked") != null || ((OffsetDateTime) inv.get("expires")).isBefore(OffsetDateTime.now())) {
			throw expired();
		}
		return h;
	}

	private static StatusException expired() {
		return new StatusException(HttpStatus.UNAUTHORIZED, "INVITE_EXPIRED", "This link has expired — ask HR to resend your invite");
	}

	// ------------------------------------------------------------------ portal sections

	private record PField(UUID id, String key, String label, String type, boolean required, List<String> options, String help) {
	}

	private record PSection(String name, List<PField> fields) {
	}

	@SuppressWarnings("unchecked")
	private List<PSection> sections() {
		List<PSection> out = new ArrayList<>();
		for (Map<String, Object> s : jdbc.sql("""
				select id, name from config_section where org_id = :org and area_key = 'employee_profile' and show_in_portal and active and deleted_at is null
				order by sort, name""").param("org", currentUser.orgId()).query((rs, i) -> Map.<String, Object>of("id", rs.getObject(1, UUID.class), "name", rs.getString(2))).list()) {
			List<PField> fields = jdbc.sql("""
					select id, key, label, type, required, options::text, help_text from config_field
					where section_id = :s and active and deleted_at is null and type <> 'HEADING' order by sort, label""").param("s", s.get("id"))
					.query((rs, i) -> new PField(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5),
							rs.getString(6) == null ? List.of() : (List<String>) json.readValue(rs.getString(6), List.class), rs.getString(7))).list();
			if (!fields.isEmpty()) {
				out.add(new PSection((String) s.get("name"), fields));
			}
		}
		return out;
	}

	private Map<String, String> values(UUID hireId, Predicate<String> clear) {
		Map<String, String> out = new LinkedHashMap<>();
		jdbc.sql("select field_key, value, value_enc from onboarding_hire_detail where hire_id = :h").param("h", hireId).query((rs, i) -> {
			String key = rs.getString(1);
			String v = rs.getBytes(3) != null ? sensitive.decrypt(rs.getBytes(3)) : rs.getString(2);
			out.put(key, v == null || clear.test(key) ? v : Sensitive.mask(key, v));
			return 0;
		}).list();
		return out;
	}

	/** Every stored detail, decrypted (for copying to the employee on join). */
	Map<String, Object> decryptedValues(UUID hireId) {
		return new LinkedHashMap<>(values(hireId, k -> true));
	}

	// ------------------------------------------------------------------ view

	@Transactional(readOnly = true)
	public Map<String, Object> view(String token) {
		return payload(authenticate(token), k -> true);
	}

	/** The same screen for HR to look at: masked unless the caller may see the field, nothing editable. */
	@Transactional(readOnly = true)
	public Map<String, Object> preview(UUID hireId, Predicate<String> clear) {
		return payload(repo.require(hireId), clear);
	}

	/** What HR already entered, shown (and counted as filled) until the hire types something else. */
	private static Map<String, String> prefill(Hire h) {
		Map<String, String> m = new HashMap<>();
		put(m, "first_name", h.first());
		put(m, "last_name", h.last());
		put(m, "personal_email", h.personalEmail());
		put(m, "phone", h.phone());
		put(m, "gender", h.gender());
		put(m, "dob", h.dob() == null ? null : h.dob().toString());
		return m;
	}

	private static void put(Map<String, String> m, String k, String v) {
		if (v != null && !v.isBlank()) {
			m.put(k, v);
		}
	}

	private Map<String, Object> payload(Hire h, Predicate<String> clear) {
		Map<String, String> values = values(h.id(), clear);
		Map<String, String> prefill = prefill(h);
		List<Map<String, Object>> sections = new ArrayList<>();
		int req = 0;
		int filled = 0;
		for (PSection s : sections()) {
			List<Map<String, Object>> fields = new ArrayList<>();
			for (PField f : s.fields()) {
				String v = values.containsKey(f.key()) ? values.get(f.key()) : prefill.get(f.key());
				Map<String, Object> m = new LinkedHashMap<>();
				m.put("key", f.key());
				m.put("label", f.label());
				m.put("type", f.type());
				m.put("required", f.required());
				m.put("options", f.options());
				m.put("help", f.help());
				m.put("value", v);
				fields.add(m);
				if (f.required()) {
					req++;
					if (v != null && !v.isBlank()) {
						filled++;
					}
				}
			}
			sections.add(Map.of("name", s.name(), "fields", fields));
		}
		Map<String, Object> counts = docs.counts(h.id());
		int docsRequired = (int) counts.get("required");
		int docsDone = jdbc.sql("select count(*) from onboarding_hire_document where hire_id = :h and required and status in ('SUBMITTED', 'VERIFIED')")
				.param("h", h.id()).query(Integer.class).single();
		int total = req + docsRequired;
		Map<String, Object> progress = new LinkedHashMap<>();
		progress.put("pct", total == 0 ? 100 : Math.round(100f * (filled + docsDone) / total));
		progress.put("detailsFilled", filled);
		progress.put("detailsRequired", req);
		progress.put("docsDone", docsDone);
		progress.put("docsRequired", docsRequired);
		String state = h.detailsSubmittedAt() == null ? "NOT_SUBMITTED"
				: (int) counts.get("verified") == docsRequired && docsRequired > 0 ? "ALL_VERIFIED" : "SUBMITTED";
		Map<String, Object> hire = new LinkedHashMap<>();
		hire.put("first", h.first());
		hire.put("title", h.title());
		hire.put("doj", h.doj());
		hire.put("location", org.locationNames(List.of(h.locationId())).get(h.locationId()));
		hire.put("manager", jdbc.sql("select display_name from employee where id = :id").param("id", h.managerId()).query(String.class).optional().orElse(null));
		hire.put("upn", jdbc.sql("select upn from onboarding_m365 where hire_id = :h and state = 'ACTIVE'").param("h", h.id()).query(String.class).optional().orElse(null));
		hire.put("dueDate", jdbc.sql("select due_date from onboarding_invite where hire_id = :h").param("h", h.id()).query(LocalDate.class).optional().orElse(null));
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("hire", hire);
		out.put("sections", sections);
		out.put("documents", docs.rows(h.id()));
		out.put("progress", progress);
		out.put("state", state);
		out.put("submittedAt", h.detailsSubmittedAt());
		return out;
	}

	// ------------------------------------------------------------------ writes

	@Transactional
	public Map<String, Object> patchDetails(String token, Map<String, Object> body) {
		Hire h = authenticate(token);
		Map<String, PField> known = new HashMap<>();
		sections().forEach(s -> s.fields().forEach(f -> known.put(f.key(), f)));
		List<ErrorItem> unknown = new ArrayList<>();
		for (String k : body.keySet()) {
			if (!known.containsKey(k)) {
				unknown.add(ErrorItem.field(k, "UNKNOWN_FIELD", "'" + k + "' is not a field you can fill in"));
			}
		}
		if (!unknown.isEmpty()) {
			throw new UnprocessableException(unknown);
		}
		for (Map.Entry<String, Object> e : body.entrySet()) {
			String key = e.getKey();
			String value = e.getValue() == null ? null : Sensitive.normalize(key, e.getValue().toString());
			if (value == null || value.isBlank()) {
				jdbc.sql("delete from onboarding_hire_detail where hire_id = :h and field_key = :k").param("h", h.id()).param("k", key).update();
				continue;
			}
			boolean secret = Sensitive.is(key);
			jdbc.sql("""
					insert into onboarding_hire_detail (hire_id, field_key, value, value_enc, updated_by_hire) values (:h, :k, :v, :e, true)
					on conflict (hire_id, field_key) do update set value = :v, value_enc = :e, updated_at = now(), updated_by_hire = true""")
					.param("h", h.id()).param("k", key).param("v", secret ? null : value).param("e", secret ? sensitive.encrypt(value) : null, java.sql.Types.BINARY).update();
		}
		if (h.detailsSubmittedAt() != null) {
			events.add(h.id(), "GEN", "Details updated", body.size() + (body.size() == 1 ? " detail" : " details") + " changed by " + h.first(), true);
		}
		return payload(repo.require(h.id()), k -> true);
	}

	@Transactional
	public Map<String, Object> upload(String token, UUID docId, String fileName, String mime, byte[] bytes) {
		Hire h = authenticate(token);
		docs.upload(h.id(), docId, fileName, mime, bytes);
		return payload(repo.require(h.id()), k -> true);
	}

	@Transactional
	public Map<String, Object> submit(String token) {
		Hire h = authenticate(token);
		Map<String, String> values = values(h.id(), k -> true);
		Map<String, String> prefill = prefill(h);
		List<Map<String, String>> details = new ArrayList<>();
		for (PSection s : sections()) {
			for (PField f : s.fields()) {
				String v = values.get(f.key());
				if (v == null) {
					v = prefill.get(f.key());
				}
				if (f.required() && (v == null || v.isBlank())) {
					details.add(Map.of("key", f.key(), "code", "REQUIRED"));
				}
				else if (v != null && Sensitive.invalid(f.key(), v) != null) {
					details.add(Map.of("key", f.key(), "code", "INVALID_FORMAT"));
				}
			}
		}
		List<UUID> missingDocs = jdbc.sql("select id from onboarding_hire_document where hire_id = :h and required and status in ('MISSING', 'REJECTED')")
				.param("h", h.id()).query(UUID.class).list();
		if (!details.isEmpty() || !missingDocs.isEmpty()) {
			throw new StatusException(HttpStatus.UNPROCESSABLE_ENTITY, "VALIDATION",
					details.size() + " details missing or invalid · " + missingDocs.size() + " required documents to upload",
					Map.of("details", details, "documents", missingDocs));
		}
		boolean first = h.detailsSubmittedAt() == null;
		jdbc.sql("update onboarding_hire set details_submitted_at = coalesce(details_submitted_at, now()), updated_at = now() where id = :id").param("id", h.id()).update();
		if (first) {
			events.add(h.id(), "OK", "Details submitted", h.first() + " submitted details for review", true);
			notifier.inApp(owners.forHire(h), h.first() + " submitted details for review", "Documents and details are ready to check.", "/onboarding/" + h.id());
		}
		status.recompute(h.id());
		return payload(repo.require(h.id()), k -> true);
	}

	// ------------------------------------------------------------------ HR read

	@Transactional
	public List<Map<String, Object>> hrDetails(UUID hireId, Predicate<String> clear) {
		Hire h = repo.require(hireId);
		Map<String, Object> p = payload(h, clear);
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> sections = (List<Map<String, Object>>) p.get("sections");
		audit.publish("onboarding", "HireDetails", hireId, "VIEW_DETAILS", null, null);
		return sections;
	}
}
