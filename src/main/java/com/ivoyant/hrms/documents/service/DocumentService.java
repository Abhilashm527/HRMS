package com.ivoyant.hrms.documents.service;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.storage.FileStore;
import com.ivoyant.hrms.common.storage.VirusScanner;
import com.ivoyant.hrms.common.web.ConflictException;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.ForbiddenException;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.common.web.StatusException;
import com.ivoyant.hrms.common.web.UnprocessableException;
import com.ivoyant.hrms.iam.IamDirectory;
import com.ivoyant.hrms.iam.RoleRef;
import java.io.IOException;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
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
 * Documents: shared company files in a folder tree. Each folder is open to one group (all admins, HR, finance, IT or every
 * employee); a folder without its own setting inherits its parent's, and the top folder defaults to all admins. Super admins see
 * everything. Anyone who can see a folder and signs in to the admin console can add, rename, move and delete inside it; only super
 * admins and HR admins change who a folder is open to.
 */
@Service
public class DocumentService {

	public enum Access {
		ADMINS("All admins"), HR("HR only"), FINANCE("Finance only"), IT("IT only"), EMPLOYEES("All employees");

		final String label;

		Access(String label) {
			this.label = label;
		}
	}

	public record FileContent(byte[] bytes, String mime, String name) {
	}

	record Folder(UUID id, UUID parent, String name, Access access, UUID by, OffsetDateTime at) {
	}

	static final long MAX_BYTES = 6L * 1024 * 1024;
	static final long QUOTA = 10L * 1024 * 1024 * 1024;
	private static final int RECENT = 15;

	/** Allowed extensions and their media types; the content is checked against the type too. */
	private static final Map<String, String> TYPES = Map.ofEntries(Map.entry("pdf", "application/pdf"), Map.entry("png", "image/png"),
			Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"), Map.entry("gif", "image/gif"), Map.entry("webp", "image/webp"),
			Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
			Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
			Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"), Map.entry("doc", "application/msword"),
			Map.entry("xls", "application/vnd.ms-excel"), Map.entry("ppt", "application/vnd.ms-powerpoint"), Map.entry("txt", "text/plain"),
			Map.entry("csv", "text/csv"));

	/** The starting folders from the design, created the first time anyone opens Documents. */
	private static final List<String[]> SEED = List.of(new String[] {"Company", "", ""}, new String[] {"Registrations & certificates", "Company", ""},
			new String[] {"Client contracts", "Company", "FINANCE"}, new String[] {"Board & compliance", "Company", ""}, new String[] {"HR", "", "HR"},
			new String[] {"Templates", "HR", ""}, new String[] {"Handbooks", "HR", "EMPLOYEES"}, new String[] {"Exit records", "HR", ""},
			new String[] {"Finance", "", "FINANCE"}, new String[] {"Statutory filings", "Finance", ""}, new String[] {"Audit", "Finance", ""},
			new String[] {"IT", "", "IT"}, new String[] {"Licence agreements", "IT", ""}, new String[] {"Security", "IT", ""});

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final IamDirectory iam;
	private final FileStore store;
	private final VirusScanner scanner;
	private final Audit audit;

	DocumentService(JdbcClient jdbc, CurrentUser currentUser, IamDirectory iam, FileStore store, VirusScanner scanner, Audit audit) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.iam = iam;
		this.store = store;
		this.scanner = scanner;
		this.audit = audit;
	}

	// ================================================================== reading

	/** Folders the caller can see, each attached to its nearest visible ancestor, with file counts and storage use. */
	@Transactional
	public Map<String, Object> tree() {
		Ctx c = ctx();
		Map<UUID, Integer> direct = new HashMap<>();
		jdbc.sql("select folder_id, count(*) from doc_file where org_id = :org group by folder_id").param("org", org())
				.query((rs, i) -> direct.put(rs.getObject(1, UUID.class), rs.getInt(2))).list();
		Set<UUID> stars = stars();
		List<Map<String, Object>> out = new ArrayList<>();
		for (Folder f : c.sorted()) {
			if (!c.sees(f.id())) {
				continue;
			}
			int count = 0;
			for (Folder d : c.subtree(f.id())) {
				if (c.sees(d.id())) {
					count += direct.getOrDefault(d.id(), 0);
				}
			}
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", f.id());
			m.put("parentId", c.visibleParent(f.id()));
			m.put("name", f.name());
			m.put("access", f.access() == null ? null : f.access().name());
			m.put("effectiveAccess", c.effective(f.id()).name());
			m.put("inheritedFrom", f.access() == null ? c.name(c.accessSource(f.id())) : null);
			m.put("restricted", c.effective(f.id()) != Access.ADMINS);
			m.put("count", count);
			m.put("star", stars.contains(f.id()));
			m.put("root", f.parent() == null);
			out.add(m);
		}
		long used = jdbc.sql("select coalesce(sum(size_bytes), 0) from doc_file where org_id = :org").param("org", org()).query(Long.class).single();
		Map<String, Object> res = new LinkedHashMap<>();
		res.put("folders", out);
		res.put("usedBytes", used);
		res.put("quotaBytes", QUOTA);
		res.put("canEdit", c.admin());
		res.put("canSetAccess", c.canSetAccess());
		res.put("accessOptions", java.util.Arrays.stream(Access.values()).map(a -> Map.of("value", a.name(), "label", a.label)).toList());
		res.put("starredCount", (long) out.stream().filter(m -> Boolean.TRUE.equals(m.get("star"))).count()
				+ files(c, null, null).stream().filter(m -> Boolean.TRUE.equals(m.get("star"))).count());
		return res;
	}

	/**
	 * What one view lists: a folder's subfolders and files, everything starred, or the latest files. A search looks through the
	 * folder and everything under it (or everything, in Starred and Recent).
	 */
	@Transactional
	public Map<String, Object> items(UUID folderId, String view, String q) {
		Ctx c = ctx();
		String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
		List<Map<String, Object>> out = new ArrayList<>();
		Set<UUID> stars = stars();
		if ("starred".equals(view) || "recent".equals(view)) {
			if ("starred".equals(view)) {
				for (Folder f : c.sorted()) {
					if (c.sees(f.id()) && stars.contains(f.id()) && matches(f.name(), needle)) {
						out.add(folderRow(c, f, stars));
					}
				}
				files(c, null, null).stream().filter(m -> Boolean.TRUE.equals(m.get("star")) && matches((String) m.get("name"), needle)).forEach(out::add);
			}
			else {
				files(c, null, null).stream().filter(m -> matches((String) m.get("name"), needle))
						.sorted(Comparator.comparing((Map<String, Object> m) -> (OffsetDateTime) m.get("at")).reversed()).limit(RECENT).forEach(out::add);
			}
			return Map.of("items", out);
		}
		Folder f = folderId == null ? c.root() : c.folder(folderId);
		c.requireSee(f.id());
		if (needle.isEmpty()) {
			for (Folder k : c.sorted()) {
				if (c.sees(k.id()) && f.id().equals(c.visibleParent(k.id()))) {
					out.add(folderRow(c, k, stars));
				}
			}
			out.addAll(files(c, Set.of(f.id()), null));
		}
		else {
			Set<UUID> under = new HashSet<>();
			for (Folder d : c.subtree(f.id())) {
				if (c.sees(d.id())) {
					under.add(d.id());
					if (!d.id().equals(f.id()) && matches(d.name(), needle)) {
						out.add(folderRow(c, d, stars));
					}
				}
			}
			files(c, under, null).stream().filter(m -> matches((String) m.get("name"), needle)).forEach(out::add);
		}
		return Map.of("items", out);
	}

	@Transactional
	public FileContent content(UUID fileId) {
		Ctx c = ctx();
		Map<String, Object> f = file(fileId);
		c.requireSee((UUID) f.get("folder"));
		audit.publish("documents", "Document", fileId, "VIEW_FILE", null, f.get("name"));
		try {
			return new FileContent(store.get((String) f.get("key")), (String) f.get("mime"), (String) f.get("name"));
		}
		catch (IOException ex) {
			throw new NotFoundException("The file is no longer in storage. Upload it again.");
		}
	}

	// ================================================================== folders

	@Transactional
	public Map<String, Object> createFolder(UUID parentId, String name) {
		Ctx c = ctx();
		Folder parent = parentId == null ? c.root() : c.folder(parentId);
		c.requireEdit(parent.id());
		String n = cleanName(name, 120, "name");
		requireFreeFolderName(parent.id(), n, null);
		UUID id = jdbc.sql("insert into doc_folder (org_id, parent_id, name, created_by) values (:org, :p, :n, :by) returning id").param("org", org())
				.param("p", parent.id()).param("n", n).param("by", currentUser.employeeId(), java.sql.Types.OTHER).query(UUID.class).single();
		audit.publish("documents", "DocumentFolder", id, "CREATE", null, Map.of("name", n, "parent", parent.name()));
		return Map.of("id", id, "name", n);
	}

	/** Body: any of {@code name}, {@code access} (a group, or null to inherit), {@code parentId}. */
	@Transactional
	public Map<String, Object> updateFolder(UUID id, Map<String, Object> body) {
		Ctx c = ctx();
		Folder f = c.folder(id);
		c.requireEdit(id);
		if (body.containsKey("name")) {
			if (f.parent() == null) {
				throw new ConflictException("The top folder can't be renamed");
			}
			String n = cleanName(str(body.get("name")), 120, "name");
			if (!n.equals(f.name())) {
				requireFreeFolderName(f.parent(), n, id);
				jdbc.sql("update doc_folder set name = :n, updated_at = now() where id = :id").param("n", n).param("id", id).update();
				audit.publish("documents", "DocumentFolder", id, "RENAME", Map.of("name", f.name()), Map.of("name", n));
			}
		}
		if (body.containsKey("access")) {
			if (!c.canSetAccess()) {
				throw new ForbiddenException("Only a super admin or HR admin can change who a folder is open to");
			}
			String v = str(body.get("access"));
			Access a = v == null || v.isBlank() ? null : parseAccess(v);
			if (a == null && f.parent() == null) {
				throw new UnprocessableException(List.of(ErrorItem.field("access", "REQUIRED", "The top folder needs a group")));
			}
			jdbc.sql("update doc_folder set access = :a, updated_at = now() where id = :id").param("a", a == null ? null : a.name(), java.sql.Types.VARCHAR)
					.param("id", id).update();
			audit.publish("documents", "DocumentFolder", id, "ACCESS", Map.of("access", c.effective(id).label),
					Map.of("access", a == null ? "Inherited" : a.label, "name", f.name()));
		}
		if (body.containsKey("parentId")) {
			moveFolder(c, f, UUID.fromString(str(body.get("parentId"))));
		}
		return Map.of("id", id);
	}

	@Transactional
	public void deleteFolder(UUID id) {
		Ctx c = ctx();
		Folder f = c.folder(id);
		if (f.parent() == null) {
			throw new ConflictException("The top folder can't be deleted");
		}
		c.requireEdit(id);
		for (Folder d : c.subtree(id)) {
			if (!c.sees(d.id())) {
				throw new ConflictException(f.name() + " holds folders you can't see, so you can't delete it");
			}
		}
		int files = jdbc.sql("select count(*) from doc_file where folder_id in (:ids)").param("ids", c.subtree(id).stream().map(Folder::id).toList())
				.query(Integer.class).single();
		jdbc.sql("delete from doc_folder where id = :id").param("id", id).update();
		audit.publish("documents", "DocumentFolder", id, "DELETE", Map.of("name", f.name(), "files", files), null);
	}

	private void moveFolder(Ctx c, Folder f, UUID target) {
		if (f.parent() == null) {
			throw new ConflictException("The top folder can't move");
		}
		Folder t = c.folder(target);
		c.requireEdit(t.id());
		if (c.subtree(f.id()).stream().anyMatch(d -> d.id().equals(t.id()))) {
			throw new ConflictException("A folder can't move into itself");
		}
		if (t.id().equals(f.parent())) {
			return;
		}
		requireFreeFolderName(t.id(), f.name(), f.id());
		jdbc.sql("update doc_folder set parent_id = :p, updated_at = now() where id = :id").param("p", t.id()).param("id", f.id()).update();
		audit.publish("documents", "DocumentFolder", f.id(), "MOVE", Map.of("folder", c.name(f.parent())), Map.of("folder", t.name(), "name", f.name()));
	}

	// ================================================================== files

	public record Upload(String fileName, byte[] bytes) {
	}

	/** Saves the files into the folder; a name already there gets " (1)", " (2)"… added. */
	@Transactional
	public Map<String, Object> upload(UUID folderId, List<Upload> files) {
		Ctx c = ctx();
		Folder f = folderId == null ? c.root() : c.folder(folderId);
		c.requireEdit(f.id());
		if (files.isEmpty()) {
			throw new UnprocessableException(List.of(ErrorItem.field("files", "REQUIRED", "Choose at least one file")));
		}
		long used = jdbc.sql("select coalesce(sum(size_bytes), 0) from doc_file where org_id = :org").param("org", org()).query(Long.class).single();
		long adding = files.stream().mapToLong(u -> u.bytes().length).sum();
		if (used + adding > QUOTA) {
			throw new StatusException(HttpStatus.INSUFFICIENT_STORAGE, "QUOTA", "Documents storage is full (10 GB)");
		}
		Set<String> taken = new HashSet<>(jdbc.sql("select lower(name) from doc_file where folder_id = :f").param("f", f.id()).query(String.class).list());
		List<Map<String, Object>> saved = new ArrayList<>();
		for (Upload u : files) {
			String original = cleanName(u.fileName() == null ? "" : u.fileName().replaceAll("[\\\\/\\r\\n]", "_"), 200, "files");
			String mime = check(original, u.bytes());
			String name = unique(original, taken);
			taken.add(name.toLowerCase(Locale.ROOT));
			UUID id = UUID.randomUUID();
			String key = org() + "/documents/" + id;
			try {
				store.put(key, u.bytes());
			}
			catch (IOException ex) {
				throw new StatusException(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_ERROR", "The file could not be saved. Try again.");
			}
			jdbc.sql("""
					insert into doc_file (id, org_id, folder_id, name, storage_key, mime, size_bytes, sha256, uploaded_by)
					values (:id, :org, :f, :n, :k, :m, :s, :h, :by)""").param("id", id).param("org", org()).param("f", f.id()).param("n", name).param("k", key)
					.param("m", mime).param("s", u.bytes().length).param("h", sha256(u.bytes())).param("by", currentUser.employeeId(), java.sql.Types.OTHER).update();
			audit.publish("documents", "Document", id, "UPLOAD", null, Map.of("name", name, "folder", f.name()));
			saved.add(Map.of("id", id, "name", name));
		}
		return Map.of("files", saved, "folder", f.name());
	}

	/** Body: {@code name} and/or {@code folderId}. */
	@Transactional
	public Map<String, Object> updateFile(UUID id, Map<String, Object> body) {
		Ctx c = ctx();
		Map<String, Object> f = file(id);
		UUID folder = (UUID) f.get("folder");
		c.requireEdit(folder);
		if (body.containsKey("name")) {
			String n = cleanName(str(body.get("name")), 200, "name");
			if (!n.equals(f.get("name"))) {
				requireFreeFileName(folder, n, id);
				jdbc.sql("update doc_file set name = :n, updated_at = now() where id = :id").param("n", n).param("id", id).update();
				audit.publish("documents", "Document", id, "RENAME", Map.of("name", f.get("name")), Map.of("name", n));
			}
		}
		if (body.containsKey("folderId")) {
			moveFile(c, id, f, UUID.fromString(str(body.get("folderId"))));
		}
		return Map.of("id", id);
	}

	@Transactional
	public void deleteFile(UUID id) {
		Ctx c = ctx();
		Map<String, Object> f = file(id);
		c.requireEdit((UUID) f.get("folder"));
		jdbc.sql("delete from doc_file where id = :id").param("id", id).update();
		jdbc.sql("delete from doc_star where item_id = :id").param("id", id).update();
		audit.publish("documents", "Document", id, "DELETE", Map.of("name", f.get("name"), "folder", c.name((UUID) f.get("folder"))), null);
	}

	private void moveFile(Ctx c, UUID id, Map<String, Object> f, UUID target) {
		Folder t = c.folder(target);
		c.requireEdit(t.id());
		if (t.id().equals(f.get("folder"))) {
			return;
		}
		requireFreeFileName(t.id(), (String) f.get("name"), id);
		jdbc.sql("update doc_file set folder_id = :t, updated_at = now() where id = :id").param("t", t.id()).param("id", id).update();
		audit.publish("documents", "Document", id, "MOVE", Map.of("folder", c.name((UUID) f.get("folder"))), Map.of("folder", t.name(), "name", f.get("name")));
	}

	// ================================================================== several at once

	/** Moves files and folders into one folder; all or nothing. */
	@Transactional
	public Map<String, Object> move(List<UUID> ids, UUID target) {
		Ctx c = ctx();
		int n = 0;
		for (UUID id : ids) {
			if (c.folders.containsKey(id)) {
				moveFolder(c, c.folder(id), target);
				c = ctx();
			}
			else {
				moveFile(c, id, file(id), target);
			}
			n++;
		}
		return Map.of("moved", n);
	}

	@Transactional
	public Map<String, Object> delete(List<UUID> ids) {
		int n = 0;
		for (UUID id : ids) {
			Ctx c = ctx();
			if (c.folders.containsKey(id)) {
				deleteFolder(id);
			}
			else if (jdbc.sql("select count(*) from doc_file where id = :id and org_id = :org").param("id", id).param("org", org()).query(Long.class).single() > 0) {
				deleteFile(id);
			}
			else {
				continue;
			}
			n++;
		}
		return Map.of("deleted", n);
	}

	@Transactional
	public void star(UUID id, boolean on) {
		Ctx c = ctx();
		UUID folder = c.folders.containsKey(id) ? id : (UUID) file(id).get("folder");
		c.requireSee(folder);
		UUID me = currentUser.requireEmployeeId();
		if (on) {
			jdbc.sql("insert into doc_star (employee_id, item_id) values (:e, :i) on conflict do nothing").param("e", me).param("i", id).update();
		}
		else {
			jdbc.sql("delete from doc_star where employee_id = :e and item_id = :i").param("e", me).param("i", id).update();
		}
	}

	// ================================================================== helpers

	private Map<String, Object> folderRow(Ctx c, Folder f, Set<UUID> stars) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", f.id());
		m.put("kind", "FOLDER");
		m.put("name", f.name());
		m.put("folderId", c.visibleParent(f.id()));
		m.put("where", c.path(c.visibleParent(f.id())));
		m.put("size", null);
		m.put("at", f.at());
		m.put("by", null);
		m.put("star", stars.contains(f.id()));
		m.put("access", c.effective(f.id()).label);
		return m;
	}

	/** Files in the given folders (or every folder the caller can see), newest first. */
	private List<Map<String, Object>> files(Ctx c, Set<UUID> in, String unused) {
		Set<UUID> stars = stars();
		List<Map<String, Object>> rows = jdbc.sql("""
				select f.id, f.name, f.folder_id, f.size_bytes, f.mime, f.updated_at, e.display_name
				from doc_file f left join employee e on e.id = f.uploaded_by where f.org_id = :org order by f.updated_at desc""").param("org", org())
				.query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("id", rs.getObject(1, UUID.class));
					m.put("kind", "FILE");
					m.put("name", rs.getString(2));
					m.put("folderId", rs.getObject(3, UUID.class));
					m.put("size", rs.getLong(4));
					m.put("mime", rs.getString(5));
					m.put("at", rs.getObject(6, OffsetDateTime.class));
					m.put("by", rs.getString(7));
					return m;
				}).list();
		List<Map<String, Object>> out = new ArrayList<>();
		for (Map<String, Object> m : rows) {
			UUID folder = (UUID) m.get("folderId");
			if ((in == null || in.contains(folder)) && c.sees(folder)) {
				m.put("where", c.path(folder));
				m.put("star", stars.contains((UUID) m.get("id")));
				m.put("access", c.effective(folder).label);
				out.add(m);
			}
		}
		return out;
	}

	private Map<String, Object> file(UUID id) {
		return jdbc.sql("select name, folder_id, storage_key, mime from doc_file where id = :id and org_id = :org").param("id", id).param("org", org())
				.query((rs, i) -> Map.<String, Object>of("name", rs.getString(1), "folder", rs.getObject(2, UUID.class), "key", rs.getString(3), "mime", rs.getString(4)))
				.optional().orElseThrow(() -> new NotFoundException("Document not found"));
	}

	private Set<UUID> stars() {
		UUID me = currentUser.employeeId();
		if (me == null) {
			return Set.of();
		}
		return new HashSet<>(jdbc.sql("select item_id from doc_star where employee_id = :e").param("e", me).query(UUID.class).list());
	}

	private void requireFreeFolderName(UUID parent, String name, UUID self) {
		if (jdbc.sql("select count(*) from doc_folder where parent_id = :p and lower(name) = lower(:n) and id <> :self").param("p", parent).param("n", name)
				.param("self", self == null ? new UUID(0, 0) : self).query(Long.class).single() > 0) {
			throw new UnprocessableException(List.of(ErrorItem.field("name", "TAKEN", "Something with this name already exists here")));
		}
	}

	private void requireFreeFileName(UUID folder, String name, UUID self) {
		if (jdbc.sql("select count(*) from doc_file where folder_id = :f and lower(name) = lower(:n) and id <> :self").param("f", folder).param("n", name)
				.param("self", self).query(Long.class).single() > 0) {
			throw new UnprocessableException(List.of(ErrorItem.field("name", "TAKEN", "Something with this name already exists here")));
		}
	}

	/** Checks the extension is allowed and the content really is that kind of file; returns the media type. */
	private String check(String name, byte[] b) {
		if (b == null || b.length == 0) {
			throw new UnprocessableException(List.of(ErrorItem.field("files", "EMPTY", name + " is empty")));
		}
		if (b.length > MAX_BYTES) {
			throw new StatusException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE", name + " is larger than 6 MB");
		}
		int dot = name.lastIndexOf('.');
		String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
		String mime = TYPES.get(ext);
		boolean ok = mime != null && switch (ext) {
			case "pdf" -> starts(b, '%', 'P', 'D', 'F');
			case "png" -> b.length > 4 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
			case "jpg", "jpeg" -> b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8;
			case "gif" -> starts(b, 'G', 'I', 'F', '8');
			case "webp" -> b.length > 12 && starts(b, 'R', 'I', 'F', 'F') && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
			case "docx", "xlsx", "pptx" -> starts(b, 'P', 'K', 3, 4);
			case "doc", "xls", "ppt" -> b.length > 4 && (b[0] & 0xFF) == 0xD0 && (b[1] & 0xFF) == 0xCF && (b[2] & 0xFF) == 0x11 && (b[3] & 0xFF) == 0xE0;
			case "txt", "csv" -> noNul(b);
			default -> false;
		};
		if (!ok) {
			throw new StatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "BAD_TYPE",
					name + " isn't an allowed file. Upload PDF, Word, Excel, PowerPoint, text, CSV or an image.");
		}
		if (!scanner.clean(b)) {
			throw new StatusException(HttpStatus.UNPROCESSABLE_ENTITY, "INFECTED", name + " did not pass the virus scan");
		}
		return mime;
	}

	private static boolean starts(byte[] b, int... head) {
		if (b.length < head.length) {
			return false;
		}
		for (int i = 0; i < head.length; i++) {
			if ((b[i] & 0xFF) != head[i]) {
				return false;
			}
		}
		return true;
	}

	private static boolean noNul(byte[] b) {
		for (int i = 0; i < Math.min(b.length, 8192); i++) {
			if (b[i] == 0) {
				return false;
			}
		}
		return true;
	}

	private static String unique(String name, Set<String> taken) {
		if (!taken.contains(name.toLowerCase(Locale.ROOT))) {
			return name;
		}
		int dot = name.lastIndexOf('.');
		String base = dot > 0 ? name.substring(0, dot) : name;
		String ext = dot > 0 ? name.substring(dot) : "";
		for (int k = 1;; k++) {
			String n = base + " (" + k + ")" + ext;
			if (!taken.contains(n.toLowerCase(Locale.ROOT))) {
				return n;
			}
		}
	}

	private static String cleanName(String s, int max, String field) {
		String n = s == null ? "" : s.strip();
		if (n.isEmpty()) {
			throw new UnprocessableException(List.of(ErrorItem.field(field, "REQUIRED", "Enter a name")));
		}
		if (n.length() > max || n.contains("/") || n.contains("\\")) {
			throw new UnprocessableException(List.of(ErrorItem.field(field, "INVALID", "Names are up to " + max + " characters, without / or \\")));
		}
		return n;
	}

	private static boolean matches(String name, String needle) {
		return needle.isEmpty() || name.toLowerCase(Locale.ROOT).contains(needle);
	}

	private static Access parseAccess(String v) {
		try {
			return Access.valueOf(v.trim().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			throw new UnprocessableException(List.of(ErrorItem.field("access", "INVALID", "Pick who the folder is open to")));
		}
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (java.security.NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static String str(Object o) {
		return o == null ? null : o.toString();
	}

	private UUID org() {
		return currentUser.orgId();
	}

	// ================================================================== who sees what

	/** The org's folders and the caller's roles, loaded once per request. */
	private Ctx ctx() {
		Map<UUID, Folder> folders = loadFolders();
		if (folders.values().stream().noneMatch(f -> f.parent() == null)) {
			seed();
			folders = loadFolders();
		}
		UUID me = currentUser.employeeId();
		Set<String> keys = new HashSet<>();
		if (me != null) {
			Set<UUID> ids = iam.roleIdsOf(me);
			for (RoleRef r : iam.roles()) {
				if (ids.contains(r.id())) {
					keys.add(r.key());
				}
			}
		}
		return new Ctx(folders, keys);
	}

	private Map<UUID, Folder> loadFolders() {
		Map<UUID, Folder> m = new LinkedHashMap<>();
		jdbc.sql("select id, parent_id, name, access, created_by, updated_at from doc_folder where org_id = :org order by lower(name)").param("org", org())
				.query((rs, i) -> m.put(rs.getObject(1, UUID.class), new Folder(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
						rs.getString(4) == null ? null : Access.valueOf(rs.getString(4)), rs.getObject(5, UUID.class), rs.getObject(6, OffsetDateTime.class))))
				.list();
		return m;
	}

	private void seed() {
		UUID root = jdbc.sql("insert into doc_folder (org_id, name, access) values (:org, 'All documents', 'ADMINS') returning id").param("org", org())
				.query(UUID.class).single();
		Map<String, UUID> byName = new HashMap<>();
		for (String[] s : SEED) {
			UUID parent = s[1].isEmpty() ? root : byName.get(s[1]);
			UUID id = jdbc.sql("insert into doc_folder (org_id, parent_id, name, access) values (:org, :p, :n, :a) returning id").param("org", org())
					.param("p", parent).param("n", s[0]).param("a", s[2].isEmpty() ? null : s[2], java.sql.Types.VARCHAR).query(UUID.class).single();
			byName.put(s[0], id);
		}
	}

	private static final class Ctx {

		final Map<UUID, Folder> folders;
		final Set<String> keys;
		final Map<UUID, List<Folder>> children = new HashMap<>();

		Ctx(Map<UUID, Folder> folders, Set<String> keys) {
			this.folders = folders;
			this.keys = keys;
			folders.values().forEach(f -> {
				if (f.parent() != null) {
					children.computeIfAbsent(f.parent(), k -> new ArrayList<>()).add(f);
				}
			});
		}

		boolean superAdmin() {
			return keys.contains("super_admin");
		}

		/** Anyone with a role beyond Employee signs in to the admin console. */
		boolean admin() {
			return keys.stream().anyMatch(k -> !"employee".equals(k));
		}

		boolean canSetAccess() {
			return superAdmin() || keys.contains("hr_admin");
		}

		Folder root() {
			return folders.values().stream().filter(f -> f.parent() == null).findFirst().orElseThrow();
		}

		Folder folder(UUID id) {
			Folder f = folders.get(id);
			if (f == null) {
				throw new NotFoundException("Folder not found");
			}
			return f;
		}

		String name(UUID id) {
			Folder f = id == null ? null : folders.get(id);
			return f == null ? "" : f.name();
		}

		UUID accessSource(UUID id) {
			Folder f = folders.get(id);
			while (f != null && f.access() == null) {
				f = f.parent() == null ? null : folders.get(f.parent());
			}
			return f == null ? null : f.id();
		}

		Access effective(UUID id) {
			UUID src = accessSource(id);
			return src == null ? Access.ADMINS : folders.get(src).access();
		}

		boolean sees(UUID id) {
			if (superAdmin()) {
				return true;
			}
			return switch (effective(id)) {
				case EMPLOYEES -> true;
				case ADMINS -> admin();
				case HR -> keys.contains("hr_admin") || keys.contains("hr_ops");
				case FINANCE -> keys.contains("finance");
				case IT -> keys.contains("it_admin");
			};
		}

		void requireSee(UUID id) {
			if (!sees(id)) {
				throw new ForbiddenException("This folder isn't open to you");
			}
		}

		void requireEdit(UUID id) {
			requireSee(id);
			if (!admin()) {
				throw new ForbiddenException("You can open these documents but not change them");
			}
		}

		/** The closest ancestor the caller can see, so a folder inside one they can't see still shows up. */
		UUID visibleParent(UUID id) {
			Folder f = folders.get(id);
			UUID p = f == null ? null : f.parent();
			while (p != null && !sees(p)) {
				Folder pf = folders.get(p);
				p = pf == null ? null : pf.parent();
			}
			return p;
		}

		List<Folder> subtree(UUID id) {
			List<Folder> out = new ArrayList<>();
			java.util.ArrayDeque<UUID> todo = new java.util.ArrayDeque<>(List.of(id));
			while (!todo.isEmpty()) {
				Folder f = folders.get(todo.pop());
				if (f != null) {
					out.add(f);
					children.getOrDefault(f.id(), List.of()).forEach(c -> todo.push(c.id()));
				}
			}
			return out;
		}

		/** Folders in tree order (parents before children, siblings by name). */
		List<Folder> sorted() {
			List<Folder> out = new ArrayList<>();
			walk(root().id(), out);
			return out;
		}

		private void walk(UUID id, List<Folder> out) {
			out.add(folders.get(id));
			children.getOrDefault(id, List.of()).stream().sorted(Comparator.comparing(f -> f.name().toLowerCase(Locale.ROOT))).forEach(c -> walk(c.id(), out));
		}

		/** "HR / Templates", the visible path above an item. */
		String path(UUID id) {
			List<String> parts = new ArrayList<>();
			UUID p = id;
			while (p != null) {
				if (sees(p)) {
					parts.add(0, name(p));
				}
				Folder f = folders.get(p);
				p = f == null ? null : f.parent();
			}
			return String.join(" / ", parts);
		}
	}
}
