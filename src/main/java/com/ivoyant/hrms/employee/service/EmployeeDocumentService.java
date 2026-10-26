package com.ivoyant.hrms.employee.service;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.storage.FileStore;
import com.ivoyant.hrms.common.storage.VirusScanner;
import com.ivoyant.hrms.common.web.CodedConflictException;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.ForbiddenException;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.common.web.StatusException;
import com.ivoyant.hrms.common.web.UnprocessableException;
import com.ivoyant.hrms.employee.EmployeeDocuments;
import com.ivoyant.hrms.employee.repo.EmployeeRepository;
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

/** Documents on an employee's file: self upload goes to review, HR with verify rights verifies as they upload. */
@Service
public class EmployeeDocumentService implements EmployeeDocuments {

	public static final int MAX_BYTES = 10 * 1024 * 1024;
	static final Set<String> CATEGORIES = Set.of("Identity", "Address", "Education", "Previous employment", "Banking & statutory", "Offer", "Letters", "Tax", "Other");

	public record FileContent(byte[] bytes, String mime, String name) {
	}

	private final JdbcClient jdbc;
	private final EmployeeRepository employees;
	private final CurrentUser currentUser;
	private final EmployeeActions actions;
	private final FileStore store;
	private final VirusScanner scanner;
	private final Audit audit;

	EmployeeDocumentService(JdbcClient jdbc, EmployeeRepository employees, CurrentUser currentUser, EmployeeActions actions, FileStore store,
			VirusScanner scanner, Audit audit) {
		this.jdbc = jdbc;
		this.employees = employees;
		this.currentUser = currentUser;
		this.actions = actions;
		this.store = store;
		this.scanner = scanner;
		this.audit = audit;
	}

	private void requireEmployee(UUID id) {
		if (employees.findByIdAndOrgId(id, currentUser.orgId()).filter(e -> e.getArchivedAt() == null).isEmpty()) {
			throw new NotFoundException("Employee " + id + " not found");
		}
	}

	private boolean self(UUID id) {
		return id.equals(currentUser.employeeId());
	}

	private void requireView(UUID id) {
		requireEmployee(id);
		if (!self(id) && !actions.can("upload_docs", id) && !actions.can("verify_docs", id)) {
			throw new ForbiddenException("You cannot see this person's documents");
		}
	}

	@Transactional(readOnly = true)
	public List<Map<String, Object>> list(UUID id) {
		requireView(id);
		return jdbc.sql("""
				select d.id, d.name, d.category, d.file_name, d.size_bytes, d.uploaded_at, u.display_name, d.status, d.note, d.source
				from employee_document d left join employee u on u.id = d.uploaded_by
				where d.employee_id = :e and d.deleted_at is null order by d.uploaded_at desc""").param("e", id).query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("id", rs.getObject(1, UUID.class));
					m.put("name", rs.getString(2));
					m.put("category", rs.getString(3));
					m.put("fileName", rs.getString(4));
					m.put("size", rs.getInt(5));
					m.put("uploadedAt", rs.getObject(6, OffsetDateTime.class));
					m.put("uploadedBy", rs.getString(7));
					m.put("status", rs.getString(8));
					m.put("note", rs.getString(9));
					m.put("source", rs.getString(10));
					return m;
				}).list();
	}

	@Transactional
	public Map<String, Object> upload(UUID id, String name, String category, String fileName, byte[] bytes) {
		requireEmployee(id);
		boolean hr = actions.can("upload_docs", id);
		if (!self(id) && !hr) {
			throw new ForbiddenException("You cannot upload documents for this person");
		}
		String cat = category == null || category.isBlank() ? "Other" : category.trim();
		if (!CATEGORIES.contains(cat)) {
			throw new UnprocessableException(List.of(ErrorItem.field("category", "INVALID", "Category is one of " + String.join(", ", CATEGORIES))));
		}
		String title = name == null || name.isBlank() ? fileName : name.trim();
		boolean verified = !self(id) && hr && actions.can("verify_docs", id);
		UUID docId = save(id, title, cat, fileName, bytes, verified ? "VERIFIED" : "IN_REVIEW", "UPLOAD");
		audit.publish("employee", "Employee", id, "DOCUMENT_UPLOAD", null, title);
		return Map.of("id", docId, "status", verified ? "VERIFIED" : "IN_REVIEW");
	}

	@Override
	@Transactional
	public void addVerified(UUID employeeId, String name, String category, String fileName, String mime, byte[] content, String source) {
		save(employeeId, name, CATEGORIES.contains(category) ? category : "Other", fileName, content, "VERIFIED", source);
	}

	private UUID save(UUID employeeId, String name, String category, String fileName, byte[] bytes, String status, String source) {
		if (bytes == null || bytes.length == 0) {
			throw new UnprocessableException(List.of(ErrorItem.field("file", "REQUIRED", "Choose a file")));
		}
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
		UUID docId = UUID.randomUUID();
		String key = currentUser.orgId() + "/employee/" + employeeId + "/docs/" + docId;
		try {
			store.put(key, bytes);
		}
		catch (IOException ex) {
			throw new StatusException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_ERROR", "The file could not be saved. Try again.");
		}
		jdbc.sql("""
				insert into employee_document (id, org_id, employee_id, name, category, storage_key, file_name, mime, size_bytes, sha256, status, source, uploaded_by,
				  verified_by, verified_at)
				values (:id, :org, :e, :n, :c, :k, :f, :m, :s, :h, :st, :src, :by, case when :st = 'VERIFIED' then :by else null end, case when :st = 'VERIFIED' then now() else null end)""")
				.param("id", docId).param("org", currentUser.orgId()).param("e", employeeId).param("n", name).param("c", category).param("k", key)
				.param("f", fileName == null ? "file" : fileName.replaceAll("[\\\\/\\r\\n]", "_")).param("m", mime).param("s", bytes.length).param("h", sha256(bytes))
				.param("st", status).param("src", source).param("by", currentUser.employeeId(), java.sql.Types.OTHER).update();
		return docId;
	}

	@Transactional
	public FileContent file(UUID id, UUID docId) {
		requireView(id);
		Map<String, Object> d = jdbc.sql("select storage_key, mime, file_name from employee_document where id = :d and employee_id = :e and deleted_at is null")
				.param("d", docId).param("e", id).query((rs, i) -> Map.<String, Object>of("k", rs.getString(1), "m", rs.getString(2), "n", rs.getString(3))).optional()
				.orElseThrow(() -> new NotFoundException("Document " + docId + " not found"));
		audit.publish("employee", "Employee", id, "DOCUMENT_VIEW", null, docId.toString());
		try {
			return new FileContent(store.get((String) d.get("k")), (String) d.get("m"), (String) d.get("n"));
		}
		catch (IOException ex) {
			throw new NotFoundException("The file is no longer available");
		}
	}

	@Transactional
	public void verify(UUID id, UUID docId) {
		requireEmployee(id);
		actions.require("verify_docs", id);
		int n = jdbc.sql("update employee_document set status = 'VERIFIED', note = null, verified_by = :by, verified_at = now() where id = :d and employee_id = :e and deleted_at is null and status <> 'VERIFIED'")
				.param("by", currentUser.employeeId(), java.sql.Types.OTHER).param("d", docId).param("e", id).update();
		if (n == 0) {
			throw new CodedConflictException("NOT_IN_REVIEW", "That document is not waiting for review");
		}
		audit.publish("employee", "Employee", id, "DOCUMENT_VERIFY", null, docId.toString());
	}

	@Transactional
	public void reject(UUID id, UUID docId, String reason) {
		requireEmployee(id);
		actions.require("verify_docs", id);
		if (reason == null || reason.isBlank()) {
			throw new UnprocessableException(List.of(ErrorItem.field("reason", "REASON_REQUIRED", "Add a reason")));
		}
		int n = jdbc.sql("update employee_document set status = 'REJECTED', note = :r, verified_by = :by, verified_at = now() where id = :d and employee_id = :e and deleted_at is null and status = 'IN_REVIEW'")
				.param("r", reason.trim()).param("by", currentUser.employeeId(), java.sql.Types.OTHER).param("d", docId).param("e", id).update();
		if (n == 0) {
			throw new CodedConflictException("NOT_IN_REVIEW", "That document is not waiting for review");
		}
		audit.publish("employee", "Employee", id, "DOCUMENT_REJECT", null, reason.trim());
	}

	@Transactional
	public void delete(UUID id, UUID docId) {
		requireEmployee(id);
		Map<String, Object> d = jdbc.sql("select uploaded_by, uploaded_at, status, source from employee_document where id = :d and employee_id = :e and deleted_at is null")
				.param("d", docId).param("e", id).query((rs, i) -> Map.<String, Object>of("by", String.valueOf(rs.getObject(1)), "at", rs.getObject(2, OffsetDateTime.class),
						"st", rs.getString(3), "src", rs.getString(4))).optional().orElseThrow(() -> new NotFoundException("Document " + docId + " not found"));
		boolean own = String.valueOf(currentUser.employeeId()).equals(d.get("by")) && "IN_REVIEW".equals(d.get("st"))
				&& ((OffsetDateTime) d.get("at")).isAfter(OffsetDateTime.now().minusHours(24));
		boolean hr = actions.can("upload_docs", id) && actions.can("delete_employee", id);
		if (!own && !hr) {
			throw new ForbiddenException("You cannot remove this document");
		}
		if (!"UPLOAD".equals(d.get("src")) && !hr) {
			throw new ForbiddenException("This document was added by the system");
		}
		jdbc.sql("update employee_document set deleted_at = now() where id = :d").param("d", docId).update();
		audit.publish("employee", "Employee", id, "DOCUMENT_DELETE", null, docId.toString());
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

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (java.security.NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}
}
