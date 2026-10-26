package com.ivoyant.hrms.onboarding.service;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.CodedConflictException;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.common.web.StatusException;
import com.ivoyant.hrms.common.web.UnprocessableException;
import com.ivoyant.hrms.notification.Notifier;
import com.ivoyant.hrms.onboarding.domain.Hire;
import com.ivoyant.hrms.common.storage.FileStore;
import com.ivoyant.hrms.common.storage.VirusScanner;
import java.io.IOException;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Documents of a hire: the HR side (request, verify, reject, view) and the file upload both sides use. */
@Service
public class HireDocumentService {

	public static final int MAX_BYTES = 5 * 1024 * 1024;
	private static final Set<String> TYPES = Set.of("application/pdf", "image/jpeg", "image/png");

	public record FileContent(byte[] bytes, String mime, String name) {
	}

	private final JdbcClient jdbc;
	private final HireRepo repo;
	private final HireStatusService status;
	private final EventLog events;
	private final Audit audit;
	private final Notifier notifier;
	private final CurrentUser currentUser;
	private final FileStore store;
	private final VirusScanner scanner;
	private final HrOwners owners;

	HireDocumentService(JdbcClient jdbc, HireRepo repo, HireStatusService status, EventLog events, Audit audit, Notifier notifier,
			CurrentUser currentUser, FileStore store, VirusScanner scanner, HrOwners owners) {
		this.jdbc = jdbc;
		this.repo = repo;
		this.status = status;
		this.events = events;
		this.audit = audit;
		this.notifier = notifier;
		this.currentUser = currentUser;
		this.store = store;
		this.scanner = scanner;
		this.owners = owners;
	}

	// ------------------------------------------------------------------ reads

	@Transactional(readOnly = true)
	public List<Map<String, Object>> list(UUID hireId) {
		repo.require(hireId);
		return rows(hireId);
	}

	List<Map<String, Object>> rows(UUID hireId) {
		return jdbc.sql("""
				select d.id, d.doc_key, od.name, od.hint, od.doc_group, d.required, d.status, d.note, f.file_name, f.size_bytes, f.uploaded_at, f.scan_state
				from onboarding_hire_document d join onboarding_document od on od.id = d.document_id
				left join onboarding_hire_file f on f.hire_document_id = d.id and f.superseded_at is null
				where d.hire_id = :h
				order by case d.status when 'SUBMITTED' then 1 when 'REJECTED' then 2 when 'MISSING' then 3 else 4 end, d.sort, od.name""")
				.param("h", hireId).query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("id", rs.getObject(1, UUID.class));
					m.put("key", rs.getString(2));
					m.put("name", rs.getString(3));
					m.put("hint", rs.getString(4));
					m.put("group", rs.getString(5));
					m.put("required", rs.getBoolean(6));
					m.put("status", rs.getString(7));
					m.put("note", rs.getString(8));
					m.put("file", rs.getString(9) == null ? null : Map.of("name", rs.getString(9), "sizeBytes", rs.getInt(10),
							"uploadedAt", rs.getObject(11, OffsetDateTime.class), "scan", rs.getString(12)));
					return m;
				}).list();
	}

	public Map<String, Object> counts(UUID hireId) {
		return jdbc.sql("""
				select count(*) filter (where required), count(*) filter (where required and status = 'VERIFIED'),
				       count(*) filter (where status = 'SUBMITTED'), count(*) filter (where status = 'MISSING'),
				       count(*) filter (where status = 'REJECTED'), count(*)
				from onboarding_hire_document where hire_id = :h""").param("h", hireId).query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("required", rs.getInt(1));
					m.put("verified", rs.getInt(2));
					m.put("submitted", rs.getInt(3));
					m.put("missing", rs.getInt(4));
					m.put("rejected", rs.getInt(5));
					m.put("total", rs.getInt(6));
					return m;
				}).single();
	}

	// ------------------------------------------------------------------ HR actions

	@Transactional
	public Map<String, Object> request(UUID hireId, String key, boolean required) {
		Hire h = openHire(hireId);
		UUID docId = jdbc.sql("select id from onboarding_document where org_id = :org and key = :k and active")
				.param("org", currentUser.orgId()).param("k", key).query(UUID.class).optional()
				.orElseThrow(() -> new NotFoundException("Document '" + key + "' is not in the library"));
		if (jdbc.sql("select count(*) from onboarding_hire_document where hire_id = :h and doc_key = :k").param("h", hireId).param("k", key).query(Long.class).single() > 0) {
			throw new CodedConflictException("DOC_EXISTS", "That document is already on the list");
		}
		jdbc.sql("""
				insert into onboarding_hire_document (hire_id, document_id, doc_key, required, sort, requested_by)
				values (:h, :d, :k, :r, (select coalesce(max(sort), 0) + 10 from onboarding_hire_document where hire_id = :h), :by)""")
				.param("h", hireId).param("d", docId).param("k", key).param("r", required).param("by", currentUser.employeeId(), java.sql.Types.OTHER).update();
		String name = docName(hireId, key);
		events.add(hireId, "DOC", name + " requested", required ? "Required" : "Optional");
		if (h.joinedAt() == null && inviteSent(hireId)) {
			notifier.email(h.personalEmail(), null, name + " added to your checklist",
					"<p>Hi " + esc(h.first()) + ", HR added <b>" + esc(name) + "</b> to your onboarding checklist.</p>");
		}
		audit.publish("onboarding", "HireDocument", hireId, "REQUEST", null, key);
		status.recompute(hireId);
		return Map.of("key", key, "name", name);
	}

	@Transactional
	public void verify(UUID hireId, UUID docId) {
		openHire(hireId);
		Row d = row(hireId, docId);
		if (!"SUBMITTED".equals(d.status())) {
			throw new CodedConflictException("NOT_SUBMITTED", "Only a submitted document can be verified");
		}
		setVerified(hireId, d);
		status.recompute(hireId);
	}

	@Transactional
	public int verifyAll(UUID hireId) {
		openHire(hireId);
		int n = 0;
		for (Row d : jdbc.sql("select id, doc_key, status, required from onboarding_hire_document where hire_id = :h and status = 'SUBMITTED'")
				.param("h", hireId).query((rs, i) -> new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getBoolean(4))).list()) {
			setVerified(hireId, d);
			n++;
		}
		status.recompute(hireId);
		return n;
	}

	private void setVerified(UUID hireId, Row d) {
		jdbc.sql("update onboarding_hire_document set status = 'VERIFIED', note = null, verified_at = now(), verified_by = :by where id = :id")
				.param("by", currentUser.employeeId(), java.sql.Types.OTHER).param("id", d.id()).update();
		events.add(hireId, "OK", docName(hireId, d.key()) + " verified", null);
		audit.publish("onboarding", "HireDocument", d.id(), "VERIFY", null, d.key());
	}

	@Transactional
	public void reject(UUID hireId, UUID docId, String reason) {
		Hire h = openHire(hireId);
		Row d = row(hireId, docId);
		String why = reason == null ? "" : reason.trim();
		if (why.isEmpty()) {
			throw new UnprocessableException(List.of(ErrorItem.field("reason", "REASON_REQUIRED", "Add a reason so " + h.first() + " knows what to fix")));
		}
		if (!"SUBMITTED".equals(d.status())) {
			throw new CodedConflictException("NOT_SUBMITTED", "Only a submitted document can be rejected");
		}
		jdbc.sql("update onboarding_hire_document set status = 'REJECTED', note = :n, verified_at = null, verified_by = null where id = :id")
				.param("n", why).param("id", docId).update();
		String name = docName(hireId, d.key());
		events.add(hireId, "BAD", name + " rejected", why);
		notifier.email(h.personalEmail(), null, "Please re-upload " + name,
				"<p>Hi " + esc(h.first()) + ", HR asked for a new upload of <b>" + esc(name) + "</b>: " + esc(why) + "</p>");
		audit.publish("onboarding", "HireDocument", docId, "REJECT", null, why);
		status.recompute(hireId);
	}

	@Transactional
	public void unverify(UUID hireId, UUID docId, String reason) {
		openHire(hireId);
		Row d = row(hireId, docId);
		if (reason == null || reason.isBlank()) {
			throw new UnprocessableException(List.of(ErrorItem.field("reason", "REASON_REQUIRED", "Add a reason")));
		}
		if (!"VERIFIED".equals(d.status())) {
			throw new CodedConflictException("NOT_VERIFIED", "Only a verified document can be un-verified");
		}
		jdbc.sql("update onboarding_hire_document set status = 'SUBMITTED', verified_at = null, verified_by = null where id = :id").param("id", docId).update();
		events.add(hireId, "GEN", docName(hireId, d.key()) + " un-verified", reason.trim());
		audit.publish("onboarding", "HireDocument", docId, "UNVERIFY", null, reason.trim());
		status.recompute(hireId);
	}

	@Transactional
	public void delete(UUID hireId, UUID docId) {
		openHire(hireId);
		Row d = row(hireId, docId);
		if (!"MISSING".equals(d.status()) || d.required()) {
			throw new CodedConflictException("DOC_IN_USE", "Only an optional document with no upload can be removed. Make it optional first.");
		}
		jdbc.sql("delete from onboarding_hire_document where id = :id").param("id", docId).update();
		events.add(hireId, "GEN", docName(hireId, d.key()) + " removed", null);
		status.recompute(hireId);
	}

	/** Makes a document required or optional (HR). */
	@Transactional
	public void setRequired(UUID hireId, UUID docId, boolean required) {
		openHire(hireId);
		row(hireId, docId);
		jdbc.sql("update onboarding_hire_document set required = :r where id = :id").param("r", required).param("id", docId).update();
		status.recompute(hireId);
	}

	/** The latest file of a document, for HR to view (audited, so not a read-only transaction). */
	@Transactional
	public FileContent file(UUID hireId, UUID docId) {
		repo.require(hireId);
		Row d = row(hireId, docId);
		Map<String, Object> f = jdbc.sql("select storage_key, mime, file_name from onboarding_hire_file where hire_document_id = :d and superseded_at is null")
				.param("d", docId).query((rs, i) -> Map.<String, Object>of("k", rs.getString(1), "m", rs.getString(2), "n", rs.getString(3))).optional()
				.orElseThrow(() -> new NotFoundException("Nothing has been uploaded for " + d.key()));
		audit.publish("onboarding", "HireDocument", docId, "VIEW_FILE", null, d.key());
		try {
			return new FileContent(store.get((String) f.get("k")), (String) f.get("m"), (String) f.get("n"));
		}
		catch (IOException ex) {
			throw new NotFoundException("The file is no longer available");
		}
	}

	// ------------------------------------------------------------------ upload (hire side)

	@Transactional
	public void upload(UUID hireId, UUID docId, String fileName, String declaredMime, byte[] bytes) {
		Hire h = openHire(hireId);
		Row d = row(hireId, docId);
		if ("VERIFIED".equals(d.status())) {
			throw new CodedConflictException("DOC_VERIFIED", "HR already verified this document");
		}
		if (bytes == null || bytes.length == 0) {
			throw new UnprocessableException(List.of(ErrorItem.field("file", "REQUIRED", "Choose a file")));
		}
		if (bytes.length > MAX_BYTES) {
			throw new StatusException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", "File is larger than 5 MB");
		}
		String mime = sniff(bytes);
		if (mime == null || !TYPES.contains(mime)) {
			throw new StatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "BAD_TYPE", "Upload a PDF, JPG or PNG");
		}
		if (!scanner.clean(bytes)) {
			throw new StatusException(HttpStatus.UNPROCESSABLE_ENTITY, "INFECTED", "That file did not pass the virus scan");
		}
		String key = currentUser.orgId() + "/" + hireId + "/" + docId + "/" + UUID.randomUUID();
		try {
			store.put(key, bytes);
		}
		catch (IOException ex) {
			throw new StatusException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_ERROR", "The file could not be saved. Try again.");
		}
		jdbc.sql("update onboarding_hire_file set superseded_at = now() where hire_document_id = :d and superseded_at is null").param("d", docId).update();
		jdbc.sql("""
				insert into onboarding_hire_file (hire_document_id, storage_key, file_name, mime, size_bytes, sha256, scan_state)
				values (:d, :k, :n, :m, :s, :h, 'CLEAN')""").param("d", docId).param("k", key).param("n", safeName(fileName)).param("m", mime)
				.param("s", bytes.length).param("h", sha256(bytes)).update();
		jdbc.sql("update onboarding_hire_document set status = 'SUBMITTED', note = null, verified_at = null, verified_by = null where id = :id").param("id", docId).update();
		events.add(hireId, "DOC", docName(hireId, d.key()) + " uploaded", safeName(fileName), true);
		notifier.inAppBatched(owners.forHire(h), h.first() + " uploaded documents", "Documents are waiting for review.", "/onboarding/" + hireId, 15);
		status.recompute(hireId);
	}

	// ------------------------------------------------------------------ helpers

	record Row(UUID id, String key, String status, boolean required) {
	}

	private Row row(UUID hireId, UUID docId) {
		return jdbc.sql("select id, doc_key, status, required from onboarding_hire_document where id = :id and hire_id = :h")
				.param("id", docId).param("h", hireId)
				.query((rs, i) -> new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getBoolean(4))).optional()
				.orElseThrow(() -> new NotFoundException("Document " + docId + " not found"));
	}

	private Hire openHire(UUID hireId) {
		Hire h = repo.require(hireId);
		if (!h.open()) {
			throw new CodedConflictException("ALREADY_JOINED", "This hire is no longer in the pipeline");
		}
		return h;
	}

	String docName(UUID hireId, String key) {
		return jdbc.sql("select od.name from onboarding_hire_document d join onboarding_document od on od.id = d.document_id where d.hire_id = :h and d.doc_key = :k")
				.param("h", hireId).param("k", key).query(String.class).optional().orElse(key);
	}

	private boolean inviteSent(UUID hireId) {
		return jdbc.sql("select count(*) from onboarding_invite where hire_id = :h and revoked_at is null").param("h", hireId).query(Long.class).single() > 0;
	}

	/** The real type from the first bytes, not the name or the declared type. */
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

	private static String safeName(String name) {
		String n = name == null ? "file" : name.replaceAll("[\\\\/\\r\\n]", "_");
		return n.length() > 120 ? n.substring(n.length() - 120) : n;
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (java.security.NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static String esc(String s) {
		return org.springframework.web.util.HtmlUtils.htmlEscape(s == null ? "" : s);
	}
}
