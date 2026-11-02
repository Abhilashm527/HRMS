package com.ivoyant.hrms.policy.service;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.ConflictException;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.ForbiddenException;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.common.web.UnprocessableException;
import com.ivoyant.hrms.iam.IamDirectory;
import com.ivoyant.hrms.iam.RoleRef;
import com.ivoyant.hrms.notification.Notifier;
import java.io.IOException;
import java.io.InputStream;
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
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Policies. A policy has published versions (fixed text) and at most one draft, the next version being written. Publishing turns the
 * draft into version 1.0, then 1.1, 1.2… or 2.0 for a major change; a version can ask everyone to acknowledge it again. Employees read
 * the current version and acknowledge it; the policy is due for review a set number of months after it was last published or reviewed.
 * HR (super admin, HR admin, HR operations) manages policies; a policy's approver can approve a draft sent for review.
 */
@Service
public class PolicyService {

	public record Section(String heading, String body) {
	}

	public record FileContent(byte[] bytes, String mime, String name) {
	}

	private static final Set<String> MANAGERS = Set.of("super_admin", "hr_admin", "hr_ops");
	private static final List<String[]> DEFAULT_CATEGORIES = List.of(new String[] {"Time off", "#0F766E"}, new String[] {"Workplace", "#2563EB"},
			new String[] {"Conduct", "#7C3AED"}, new String[] {"Security", "#DC2626"}, new String[] {"Finance", "#B45309"},
			new String[] {"Compliance", "#475569"});
	private static final List<Section> BLANK = List.of(new Section("Purpose", ""), new Section("Scope", ""), new Section("Policy", ""),
			new Section("Exceptions", ""));
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final IamDirectory iam;
	private final Notifier notifier;
	private final Audit audit;
	private final JsonMapper json;
	private volatile List<Map<String, Object>> templates;

	PolicyService(JdbcClient jdbc, CurrentUser currentUser, IamDirectory iam, Notifier notifier, Audit audit, JsonMapper json) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.iam = iam;
		this.notifier = notifier;
		this.audit = audit;
		this.json = json;
	}

	// ================================================================== list (HR)

	@Transactional
	public Map<String, Object> list() {
		requireManager();
		ensureCategories();
		long staff = staff();
		List<Map<String, Object>> rows = jdbc.sql("""
				select p.id, p.name, c.id, c.name, c.color_hex, p.owner_id, o.display_name, p.approver_id, a.display_name, p.review_cycle_months,
				       p.ack_required, p.last_reviewed_on, p.archived_at,
				       (select count(*) from policy_version v where v.policy_id = p.id) as versions,
				       (select v.version from policy_version v where v.policy_id = p.id order by v.published_at desc limit 1) as cur,
				       (select v.published_at from policy_version v where v.policy_id = p.id order by v.published_at desc limit 1) as cur_at,
				       d.policy_id is not null as has_draft, coalesce(d.in_review, false), coalesce(d.approved, false),
				       (select max(e.at) from policy_event e where e.policy_id = p.id) as updated,
				       (select count(*) from policy_ack k join employee x on x.id = k.employee_id and x.status <> 'EXITED' and x.archived_at is null
				         where k.version_id = (select v.id from policy_version v where v.policy_id = p.id and v.requires_ack order by v.published_at desc limit 1)) as acked
				from policy p join policy_category c on c.id = p.category_id
				left join employee o on o.id = p.owner_id left join employee a on a.id = p.approver_id
				left join policy_draft d on d.policy_id = p.id
				where p.org_id = :org order by lower(p.name)""").param("org", org()).query((rs, i) -> {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", rs.getObject(1, UUID.class));
			m.put("name", rs.getString(2));
			m.put("category", Map.of("id", rs.getObject(3, UUID.class), "name", rs.getString(4), "color", rs.getString(5)));
			m.put("owner", person(rs.getObject(6, UUID.class), rs.getString(7)));
			m.put("approver", person(rs.getObject(8, UUID.class), rs.getString(9)));
			m.put("cycleMonths", rs.getInt(10));
			m.put("ackRequired", rs.getBoolean(11));
			LocalDate reviewed = rs.getObject(12, LocalDate.class);
			OffsetDateTime archived = rs.getObject(13, OffsetDateTime.class);
			int versions = rs.getInt(14);
			String cur = rs.getString(15);
			OffsetDateTime curAt = rs.getObject(16, OffsetDateTime.class);
			boolean draft = rs.getBoolean(17);
			m.put("archived", archived != null);
			m.put("versionCount", versions);
			m.put("version", cur);
			m.put("effective", curAt == null ? null : curAt.toLocalDate());
			m.put("status", archived != null ? "ARCHIVED" : cur == null ? "DRAFT" : draft && rs.getBoolean(18) ? "IN_REVIEW" : "PUBLISHED");
			m.put("draft", draft ? Map.of("inReview", rs.getBoolean(18), "approved", rs.getBoolean(19), "nextVersion", nextVersion(cur, false)) : null);
			OffsetDateTime updated = rs.getObject(20, OffsetDateTime.class);
			m.put("updatedAt", updated);
			LocalDate next = nextReview(curAt, reviewed, rs.getInt(10));
			m.put("nextReview", archived != null ? null : next);
			boolean asks = cur != null && archived == null && rs.getBoolean(11);
			m.put("ack", asks ? Map.of("done", rs.getLong(21), "total", staff) : null);
			return m;
		}).list();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("items", rows);
		out.put("categories", categories());
		out.put("staff", staff);
		return out;
	}

	// ================================================================== one policy (HR)

	@Transactional
	public Map<String, Object> detail(UUID id) {
		Ctx c = ctx();
		Map<String, Object> p = policyRow(id);
		boolean manager = c.manager();
		boolean approver = id(p.get("approverId")) != null && id(p.get("approverId")).equals(currentUser.employeeId());
		if (!manager && !approver) {
			throw new ForbiddenException("Only HR or this policy's approver can open it here");
		}
		List<Map<String, Object>> versions = jdbc.sql("""
				select v.id, v.version, v.note, v.major, v.requires_ack, v.published_at, e.display_name, v.sections::text
				from policy_version v left join employee e on e.id = v.published_by where v.policy_id = :p order by v.published_at desc""")
				.param("p", id).query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("id", rs.getObject(1, UUID.class));
					m.put("version", rs.getString(2));
					m.put("note", rs.getString(3));
					m.put("major", rs.getBoolean(4));
					m.put("requiresAck", rs.getBoolean(5));
					m.put("publishedAt", rs.getObject(6, OffsetDateTime.class));
					m.put("publishedBy", rs.getString(7));
					m.put("sections", sections(rs.getString(8)));
					return m;
				}).list();
		Map<String, Object> draft = jdbc.sql("""
				select d.sections::text, d.note, d.in_review, d.approved, d.updated_at, e.display_name
				from policy_draft d left join employee e on e.id = d.updated_by where d.policy_id = :p""").param("p", id).query((rs, i) -> {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("sections", sections(rs.getString(1)));
			m.put("note", rs.getString(2));
			m.put("inReview", rs.getBoolean(3));
			m.put("approved", rs.getBoolean(4));
			m.put("updatedAt", rs.getObject(5, OffsetDateTime.class));
			m.put("updatedBy", rs.getString(6));
			return m;
		}).optional().orElse(null);
		String cur = versions.isEmpty() ? null : (String) versions.get(0).get("version");
		if (draft != null) {
			draft.put("nextVersion", nextVersion(cur, false));
			draft.put("nextMajor", nextVersion(cur, true));
			@SuppressWarnings("unchecked")
			List<Section> was = versions.isEmpty() ? List.of() : (List<Section>) versions.get(0).get("sections");
			@SuppressWarnings("unchecked")
			List<Section> now = (List<Section>) draft.get("sections");
			draft.put("changes", diff(was, now));
		}
		List<Map<String, Object>> events = jdbc.sql("""
				select e.type, e.text, e.version, e.at, x.display_name from policy_event e left join employee x on x.id = e.actor_id
				where e.policy_id = :p order by e.at desc limit 200""").param("p", id).query((rs, i) -> {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("type", rs.getString(1));
			m.put("text", rs.getString(2));
			m.put("version", rs.getString(3));
			m.put("at", rs.getObject(4, OffsetDateTime.class));
			m.put("by", rs.getString(5) == null ? "System" : rs.getString(5));
			return m;
		}).list();
		OffsetDateTime curAt = versions.isEmpty() ? null : (OffsetDateTime) versions.get(0).get("publishedAt");
		boolean archived = Boolean.TRUE.equals(p.get("archived"));
		p.put("versions", versions);
		p.put("version", cur);
		p.put("effective", curAt == null ? null : curAt.toLocalDate());
		p.put("draft", draft);
		p.put("events", events);
		p.put("status", archived ? "ARCHIVED" : cur == null ? "DRAFT" : draft != null && Boolean.TRUE.equals(draft.get("inReview")) ? "IN_REVIEW" : "PUBLISHED");
		p.put("nextReview", archived ? null : nextReview(curAt, (LocalDate) p.get("lastReviewedOn"), (Integer) p.get("cycleMonths")));
		p.put("acks", acks(id, Boolean.TRUE.equals(p.get("ackRequired")) && !archived));
		p.put("canManage", manager);
		p.put("canApprove", draft != null && Boolean.TRUE.equals(draft.get("inReview")) && (manager || approver));
		return p;
	}

	/** Acknowledgement of the version that asked for it, by department, plus who hasn't yet. */
	private Map<String, Object> acks(UUID policyId, boolean asks) {
		UUID target = jdbc.sql("select id from policy_version where policy_id = :p and requires_ack order by published_at desc limit 1")
				.param("p", policyId).query(UUID.class).optional().orElse(null);
		if (!asks || target == null) {
			return null;
		}
		List<Map<String, Object>> people = jdbc.sql("""
				select e.id, e.display_name, coalesce(d.name, 'No department'), k.at
				from employee e left join department d on d.id = e.department_id
				left join policy_ack k on k.version_id = :v and k.employee_id = e.id
				where e.org_id = :org and e.status <> 'EXITED' and e.archived_at is null order by e.display_name""")
				.param("v", target).param("org", org()).query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("id", rs.getObject(1, UUID.class));
					m.put("name", rs.getString(2));
					m.put("department", rs.getString(3));
					m.put("at", rs.getObject(4, OffsetDateTime.class));
					return m;
				}).list();
		Map<String, int[]> byDept = new java.util.TreeMap<>();
		for (Map<String, Object> m : people) {
			int[] n = byDept.computeIfAbsent((String) m.get("department"), k -> new int[2]);
			n[0]++;
			if (m.get("at") != null) {
				n[1]++;
			}
		}
		String version = jdbc.sql("select version from policy_version where id = :v").param("v", target).query(String.class).single();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("version", version);
		out.put("total", people.size());
		out.put("done", people.stream().filter(m -> m.get("at") != null).count());
		out.put("departments", byDept.entrySet().stream().map(e -> Map.of("name", e.getKey(), "total", e.getValue()[0], "done", e.getValue()[1])).toList());
		out.put("pending", people.stream().filter(m -> m.get("at") == null).map(m -> Map.of("id", m.get("id"), "name", m.get("name"), "department", m.get("department"))).toList());
		out.put("recent", people.stream().filter(m -> m.get("at") != null)
				.sorted((a, b) -> ((OffsetDateTime) b.get("at")).compareTo((OffsetDateTime) a.get("at"))).limit(8).toList());
		return out;
	}

	// ================================================================== create & settings

	/** Body: name, categoryId, ownerId, approverId, cycleMonths, ackRequired, from (blank | template key | policy id). */
	@Transactional
	public Map<String, Object> create(Map<String, Object> body) {
		requireManager();
		ensureCategories();
		String name = name(str(body.get("name")));
		UUID cat = category(str(body.get("categoryId")));
		requireFreeName(name, null);
		List<Section> start = BLANK;
		String from = str(body.get("from"));
		String fromText = "";
		if (from != null && !from.isBlank() && !"blank".equals(from)) {
			Map<String, Object> t = templates().stream().filter(x -> from.equals(x.get("key"))).findFirst().orElse(null);
			if (t != null) {
				start = templateSections(t);
				fromText = " from the " + t.get("name") + " template";
			}
			else {
				UUID src = UUID.fromString(from);
				String srcName = jdbc.sql("select name from policy where id = :id and org_id = :org").param("id", src).param("org", org()).query(String.class)
						.optional().orElseThrow(() -> new NotFoundException("Policy to copy not found"));
				start = currentSections(src);
				if (start.isEmpty()) {
					start = BLANK;
				}
				fromText = " as a copy of " + srcName;
			}
		}
		UUID owner = employee(str(body.get("ownerId")), "ownerId");
		UUID approver = employee(str(body.get("approverId")), "approverId");
		int cycle = cycle(body.get("cycleMonths"));
		UUID id = jdbc.sql("""
				insert into policy (org_id, name, category_id, owner_id, approver_id, review_cycle_months, ack_required, created_by)
				values (:org, :n, :c, :o, :a, :cy, :ack, :by) returning id""").param("org", org()).param("n", name).param("c", cat)
				.param("o", owner, java.sql.Types.OTHER).param("a", approver, java.sql.Types.OTHER).param("cy", cycle)
				.param("ack", !Boolean.FALSE.equals(body.get("ackRequired"))).param("by", me(), java.sql.Types.OTHER).query(UUID.class).single();
		saveDraft(id, start, "Initial release", true);
		event(id, "created", "Created the policy" + fromText, null);
		audit.publish("policy", "Policy", id, "CREATE", null, Map.of("name", name));
		return Map.of("id", id);
	}

	/** Body: any of name, categoryId, ownerId, approverId, cycleMonths, ackRequired. */
	@Transactional
	public Map<String, Object> update(UUID id, Map<String, Object> body) {
		requireManager();
		Map<String, Object> p = policyRow(id);
		List<String> changed = new ArrayList<>();
		if (body.containsKey("name")) {
			String n = name(str(body.get("name")));
			if (!n.equals(p.get("name"))) {
				requireFreeName(n, id);
				jdbc.sql("update policy set name = :n where id = :id").param("n", n).param("id", id).update();
				changed.add("renamed to " + n);
			}
		}
		if (body.containsKey("categoryId")) {
			UUID c = category(str(body.get("categoryId")));
			jdbc.sql("update policy set category_id = :c where id = :id").param("c", c).param("id", id).update();
			changed.add("category");
		}
		if (body.containsKey("ownerId")) {
			jdbc.sql("update policy set owner_id = :o where id = :id").param("o", employee(str(body.get("ownerId")), "ownerId"), java.sql.Types.OTHER)
					.param("id", id).update();
			changed.add("owner");
		}
		if (body.containsKey("approverId")) {
			jdbc.sql("update policy set approver_id = :a where id = :id")
					.param("a", employee(str(body.get("approverId")), "approverId"), java.sql.Types.OTHER).param("id", id).update();
			changed.add("approver");
		}
		if (body.containsKey("cycleMonths")) {
			jdbc.sql("update policy set review_cycle_months = :c where id = :id").param("c", cycle(body.get("cycleMonths"))).param("id", id).update();
			changed.add("review cycle");
		}
		if (body.containsKey("appliesTo")) {
			String a = str(body.get("appliesTo"));
			a = a == null || a.isBlank() ? "All employees" : a.strip();
			if (a.length() > 80) {
				throw new UnprocessableException(List.of(ErrorItem.field("appliesTo", "TOO_LONG", "Keep it under 80 characters")));
			}
			jdbc.sql("update policy set applies_to = :a where id = :id").param("a", a).param("id", id).update();
			changed.add("applies to");
		}
		if (body.get("page") instanceof Map<?, ?> pg) {
			jdbc.sql("update policy set page = cast(:p as jsonb) where id = :id").param("p", json.writeValueAsString(pageOf(pg))).param("id", id).update();
			changed.add("page layout");
		}
		if (body.containsKey("header")) {
			jdbc.sql("update policy set header_html = :h where id = :id").param("h", PolicyHtml.cleanLine(str(body.get("header")))).param("id", id).update();
			changed.add("header");
		}
		if (body.containsKey("footer")) {
			jdbc.sql("update policy set footer_html = :h where id = :id").param("h", PolicyHtml.cleanLine(str(body.get("footer")))).param("id", id).update();
			changed.add("footer");
		}
		if (body.containsKey("ackRequired")) {
			jdbc.sql("update policy set ack_required = :a where id = :id").param("a", Boolean.TRUE.equals(body.get("ackRequired"))).param("id", id).update();
			changed.add(Boolean.TRUE.equals(body.get("ackRequired")) ? "acknowledgement on" : "acknowledgement off");
		}
		if (!changed.isEmpty()) {
			event(id, "settings", "Changed " + String.join(", ", changed), null);
			audit.publish("policy", "Policy", id, "UPDATE", null, Map.of("changed", changed));
		}
		return Map.of("id", id);
	}

	// ================================================================== drafting

	/** Starts the next version from the current text. */
	@Transactional
	public Map<String, Object> startDraft(UUID id) {
		requireManager();
		Map<String, Object> p = policyRow(id);
		if (Boolean.TRUE.equals(p.get("archived"))) {
			throw new ConflictException("Restore the policy to edit it");
		}
		if (hasDraft(id)) {
			return Map.of("id", id);
		}
		String cur = currentVersion(id);
		saveDraft(id, currentSections(id), "", true);
		event(id, "draft", "Started draft v" + nextVersion(cur, false), null);
		return Map.of("id", id);
	}

	/** Body: sections [{heading, body}], note. Editing a draft that was sent for review takes it back. */
	@Transactional
	public Map<String, Object> saveDraft(UUID id, Map<String, Object> body) {
		requireManager();
		policyRow(id);
		if (!hasDraft(id)) {
			throw new ConflictException("Start a draft first");
		}
		List<Section> secs = parseSections(body.get("sections"));
		saveDraft(id, secs, str(body.get("note")), false);
		return Map.of("id", id);
	}

	@Transactional
	public void discardDraft(UUID id) {
		requireManager();
		policyRow(id);
		if (currentVersion(id) == null) {
			throw new ConflictException("This policy has never been published; archive it instead");
		}
		if (jdbc.sql("delete from policy_draft where policy_id = :p").param("p", id).update() > 0) {
			event(id, "discard", "Discarded the draft", null);
		}
	}

	@Transactional
	public void submit(UUID id) {
		requireManager();
		Map<String, Object> p = policyRow(id);
		if (!hasDraft(id)) {
			throw new ConflictException("There is no draft to send");
		}
		UUID approver = id(p.get("approverId"));
		if (approver == null) {
			throw new UnprocessableException(List.of(ErrorItem.field("approverId", "REQUIRED", "Set an approver first")));
		}
		jdbc.sql("update policy_draft set in_review = true, approved = false where policy_id = :p").param("p", id).update();
		event(id, "review", "Sent for review to " + p.get("approverName"), null);
		notifier.inApp(List.of(approver), "POLICY", "Policy waiting for your review", p.get("name") + " · draft v" + nextVersion(currentVersion(id), false),
				"/policies?open=" + id);
	}

	@Transactional
	public void approve(UUID id) {
		Map<String, Object> p = policyRow(id);
		boolean approver = me() != null && me().equals(id(p.get("approverId")));
		if (!approver && !ctx().manager()) {
			throw new ForbiddenException("Only the approver can approve this draft");
		}
		if (jdbc.sql("update policy_draft set in_review = false, approved = true where policy_id = :p and in_review").param("p", id).update() == 0) {
			throw new ConflictException("This draft isn't waiting for review");
		}
		event(id, "approve", "Approved the draft", null);
		UUID owner = id(p.get("ownerId"));
		if (owner != null && !owner.equals(me())) {
			notifier.inApp(List.of(owner), "POLICY", "Policy draft approved", p.get("name") + " is ready to publish", "/policies?open=" + id);
		}
	}

	/** Body: major, note, requireAck. Turns the draft into the next version. */
	@Transactional
	public Map<String, Object> publish(UUID id, Map<String, Object> body) {
		requireManager();
		Map<String, Object> p = policyRow(id);
		if (Boolean.TRUE.equals(p.get("archived"))) {
			throw new ConflictException("Restore the policy to publish it");
		}
		String draftJson = jdbc.sql("select sections::text from policy_draft where policy_id = :p").param("p", id).query(String.class).optional()
				.orElseThrow(() -> new ConflictException("There is no draft to publish"));
		List<Section> secs = sections(draftJson);
		if (secs.stream().allMatch(s -> s.body().isBlank())) {
			throw new UnprocessableException(List.of(ErrorItem.field("sections", "EMPTY", "Write the policy before publishing it")));
		}
		String cur = currentVersion(id);
		if (cur != null && diff(currentSections(id), secs).get("total").equals(0)) {
			throw new ConflictException("Nothing changed since v" + cur + ". Use Mark reviewed instead.");
		}
		boolean major = Boolean.TRUE.equals(body.get("major"));
		String v = nextVersion(cur, major);
		String note = str(body.get("note"));
		if (note == null || note.isBlank()) {
			note = cur == null ? "Initial release" : "";
		}
		if (note.isBlank()) {
			throw new UnprocessableException(List.of(ErrorItem.field("note", "REQUIRED", "Say what changed")));
		}
		boolean ackPolicy = Boolean.TRUE.equals(p.get("ackRequired"));
		boolean requiresAck = ackPolicy && (cur == null || !Boolean.FALSE.equals(body.get("requireAck")));
		jdbc.sql("""
				insert into policy_version (policy_id, version, sections, note, major, requires_ack, published_by)
				values (:p, :v, cast(:s as jsonb), :n, :m, :a, :by)""").param("p", id).param("v", v).param("s", draftJson).param("n", note.strip())
				.param("m", major).param("a", requiresAck).param("by", me(), java.sql.Types.OTHER).update();
		jdbc.sql("delete from policy_draft where policy_id = :p").param("p", id).update();
		jdbc.sql("update policy set last_reviewed_on = current_date where id = :p").param("p", id).update();
		event(id, "publish", "Published v" + v + " · " + note.strip(), v);
		audit.publish("policy", "Policy", id, "PUBLISH", null, Map.of("version", v, "name", p.get("name")));
		if (requiresAck) {
			List<UUID> staff = jdbc.sql("select id from employee where org_id = :org and status <> 'EXITED' and archived_at is null").param("org", org())
					.query(UUID.class).list();
			notifier.inAppBatched(staff, cur == null ? "New policy to read" : "Updated policy to read", p.get("name") + " v" + v + " · please read and acknowledge",
					"/me/policies?open=" + id, 60);
		}
		return Map.of("version", v);
	}

	/** Confirms the policy is still right without changing it, which restarts the review clock. */
	@Transactional
	public void markReviewed(UUID id) {
		requireManager();
		policyRow(id);
		if (currentVersion(id) == null) {
			throw new ConflictException("Publish the policy first");
		}
		jdbc.sql("update policy set last_reviewed_on = current_date where id = :p").param("p", id).update();
		event(id, "reviewed", "Reviewed with no changes", currentVersion(id));
	}

	@Transactional
	public void archive(UUID id, boolean on) {
		requireManager();
		Map<String, Object> p = policyRow(id);
		if (on == Boolean.TRUE.equals(p.get("archived"))) {
			return;
		}
		jdbc.sql("update policy set archived_at = " + (on ? "now()" : "null") + " where id = :p").param("p", id).update();
		event(id, on ? "archive" : "restore", on ? "Archived · employees no longer see it" : "Restored", null);
		audit.publish("policy", "Policy", id, on ? "ARCHIVE" : "RESTORE", null, Map.of("name", p.get("name")));
	}

	@Transactional
	public void delete(UUID id) {
		requireManager();
		Map<String, Object> p = policyRow(id);
		if (currentVersion(id) != null) {
			throw new ConflictException("Published policies are kept for the record; archive it instead");
		}
		jdbc.sql("delete from policy where id = :p").param("p", id).update();
		audit.publish("policy", "Policy", id, "DELETE", Map.of("name", p.get("name")), null);
	}

	/** A version as a PDF; the draft with {@code version=draft}, otherwise the current version unless one is named. */
	@Transactional
	public FileContent pdf(UUID id, String version) {
		Map<String, Object> p = policyRow(id);
		boolean manager = ctx().manager();
		List<PolicyPdf.Section> secs;
		String label;
		if ("draft".equals(version)) {
			if (!manager) {
				throw new ForbiddenException("Drafts are for HR only");
			}
			secs = sections(jdbc.sql("select sections::text from policy_draft where policy_id = :p").param("p", id).query(String.class).optional()
					.orElseThrow(() -> new NotFoundException("No draft"))).stream().map(s -> new PolicyPdf.Section(s.heading(), pdfText(s.body()))).toList();
			label = "Draft v" + nextVersion(currentVersion(id), false);
		}
		else {
			if (Boolean.TRUE.equals(p.get("archived")) && !manager) {
				throw new NotFoundException("Policy not found");
			}
			if (version != null && !version.isBlank() && !manager) {
				throw new ForbiddenException("Earlier versions are for HR only");
			}
			Map<String, Object> v = jdbc.sql("""
					select version, sections::text, published_at from policy_version where policy_id = :p and (cast(:v as text) is null or version = :v)
					order by published_at desc limit 1""").param("p", id).param("v", version == null || version.isBlank() ? null : version, java.sql.Types.VARCHAR)
					.query((rs, i) -> Map.<String, Object>of("v", rs.getString(1), "s", rs.getString(2), "at", rs.getObject(3, OffsetDateTime.class))).optional()
					.orElseThrow(() -> new NotFoundException("This policy has no published version yet"));
			secs = sections((String) v.get("s")).stream().map(s -> new PolicyPdf.Section(s.heading(), pdfText(s.body()))).toList();
			label = "Version " + v.get("v") + " · effective " + DAY.format((OffsetDateTime) v.get("at"));
		}
		byte[] bytes = PolicyPdf.render((String) p.get("name"), label + " · " + ((Map<?, ?>) p.get("category")).get("name"), secs,
				p.get("name") + " · " + label);
		String file = ((String) p.get("name")).replaceAll("[^A-Za-z0-9 ._-]", "").trim() + ".pdf";
		return new FileContent(bytes, "application/pdf", file.isBlank() ? "policy.pdf" : file);
	}

	/** Reads an uploaded PDF or Word file into sections for the editor. Nothing is saved. */
	public Map<String, Object> importFile(String fileName, byte[] bytes) {
		requireManager();
		PolicyImport.Result r = PolicyImport.read(fileName, bytes);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("title", r.title());
		out.put("pages", r.pages());
		out.put("sections", r.sections());
		return out;
	}

	/** Sends an in-app reminder to everyone who hasn't acknowledged the version that asked for it. */
	@Transactional
	public Map<String, Object> remind(UUID id) {
		requireManager();
		Map<String, Object> p = policyRow(id);
		Map<String, Object> a = acks(id, Boolean.TRUE.equals(p.get("ackRequired")) && !Boolean.TRUE.equals(p.get("archived")));
		if (a == null) {
			throw new UnprocessableException(List.of(ErrorItem.field("policy", "NO_ACK", "This policy isn't asking anyone to acknowledge it")));
		}
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> pending = (List<Map<String, Object>>) a.get("pending");
		List<UUID> ids = pending.stream().map(m -> (UUID) m.get("id")).toList();
		if (!ids.isEmpty()) {
			notifier.inAppBatched(ids, "Please read and acknowledge " + p.get("name"), "Version " + a.get("version") + " needs your acknowledgement", "/me/policies?open=" + id, 60 * 12);
			event(id, "reminder", "Reminded " + ids.size() + " " + (ids.size() == 1 ? "person" : "people") + " to acknowledge v" + a.get("version"), (String) a.get("version"));
		}
		return Map.of("sent", ids.size());
	}

	// ================================================================== employees

	/** Published, active policies with whether the caller still needs to acknowledge each. */
	@Transactional(readOnly = true)
	public Map<String, Object> mine() {
		UUID me = currentUser.requireEmployeeId();
		List<Map<String, Object>> rows = jdbc.sql("""
				select p.id, p.name, c.name, c.color_hex, v.version, v.published_at, p.ack_required,
				       (select t.id from policy_version t where t.policy_id = p.id and t.requires_ack order by t.published_at desc limit 1) as target
				from policy p join policy_category c on c.id = p.category_id
				join lateral (select version, published_at from policy_version x where x.policy_id = p.id order by x.published_at desc limit 1) v on true
				where p.org_id = :org and p.archived_at is null order by lower(p.name)""").param("org", org()).query((rs, i) -> {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", rs.getObject(1, UUID.class));
			m.put("name", rs.getString(2));
			m.put("category", Map.of("name", rs.getString(3), "color", rs.getString(4)));
			m.put("version", rs.getString(5));
			m.put("effective", rs.getObject(6, OffsetDateTime.class).toLocalDate());
			m.put("ackRequired", rs.getBoolean(7));
			m.put("target", rs.getObject(8, UUID.class));
			return m;
		}).list();
		Set<UUID> acked = new HashSet<>(jdbc.sql("select version_id from policy_ack where employee_id = :e").param("e", me).query(UUID.class).list());
		Map<UUID, OffsetDateTime> when = new HashMap<>();
		jdbc.sql("select version_id, at from policy_ack where employee_id = :e").param("e", me)
				.query((rs, i) -> when.put(rs.getObject(1, UUID.class), rs.getObject(2, OffsetDateTime.class))).list();
		for (Map<String, Object> m : rows) {
			UUID t = (UUID) m.remove("target");
			boolean needs = Boolean.TRUE.equals(m.get("ackRequired")) && t != null;
			m.put("needsAck", needs && !acked.contains(t));
			m.put("acknowledgedAt", needs ? when.get(t) : null);
		}
		return Map.of("items", rows, "pending", rows.stream().filter(m -> Boolean.TRUE.equals(m.get("needsAck"))).count());
	}

	@Transactional(readOnly = true)
	public Map<String, Object> read(UUID id) {
		UUID me = currentUser.requireEmployeeId();
		Map<String, Object> p = policyRow(id);
		if (Boolean.TRUE.equals(p.get("archived"))) {
			throw new NotFoundException("Policy not found");
		}
		Map<String, Object> v = jdbc.sql("select id, version, sections::text, published_at, note from policy_version where policy_id = :p order by published_at desc limit 1")
				.param("p", id).query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("id", rs.getObject(1, UUID.class));
					m.put("version", rs.getString(2));
					m.put("sections", sections(rs.getString(3)));
					m.put("effective", rs.getObject(4, OffsetDateTime.class).toLocalDate());
					m.put("note", rs.getString(5));
					return m;
				}).optional().orElseThrow(() -> new NotFoundException("Policy not found"));
		UUID target = jdbc.sql("select id from policy_version where policy_id = :p and requires_ack order by published_at desc limit 1").param("p", id)
				.query(UUID.class).optional().orElse(null);
		boolean asks = Boolean.TRUE.equals(p.get("ackRequired")) && target != null;
		OffsetDateTime at = asks ? jdbc.sql("select at from policy_ack where version_id = :v and employee_id = :e").param("v", target).param("e", me)
				.query(OffsetDateTime.class).optional().orElse(null) : null;
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("id", id);
		out.put("name", p.get("name"));
		out.put("category", p.get("category"));
		out.put("owner", p.get("ownerName"));
		out.putAll(v);
		out.remove("id");
		out.put("id", id);
		out.put("ackRequired", asks);
		out.put("acknowledgedAt", at);
		out.put("page", p.get("page"));
		out.put("header", p.get("header"));
		out.put("footer", p.get("footer"));
		out.put("appliesTo", p.get("appliesTo"));
		return out;
	}

	@Transactional
	public Map<String, Object> acknowledge(UUID id) {
		UUID me = currentUser.requireEmployeeId();
		Map<String, Object> p = policyRow(id);
		if (Boolean.TRUE.equals(p.get("archived"))) {
			throw new NotFoundException("Policy not found");
		}
		Map<String, Object> t = jdbc.sql("select id, version from policy_version where policy_id = :p and requires_ack order by published_at desc limit 1")
				.param("p", id).query((rs, i) -> Map.<String, Object>of("id", rs.getObject(1, UUID.class), "v", rs.getString(2))).optional()
				.orElseThrow(() -> new ConflictException("This policy doesn't need acknowledging"));
		if (!Boolean.TRUE.equals(p.get("ackRequired"))) {
			throw new ConflictException("This policy doesn't need acknowledging");
		}
		int n = jdbc.sql("insert into policy_ack (version_id, employee_id) values (:v, :e) on conflict do nothing").param("v", t.get("id")).param("e", me).update();
		if (n > 0) {
			audit.publish("policy", "Policy", id, "ACKNOWLEDGE", null, Map.of("version", t.get("v")));
		}
		return Map.of("acknowledged", true, "version", t.get("v"));
	}

	// ================================================================== categories

	@Transactional
	public List<Map<String, Object>> categories() {
		ensureCategories();
		return jdbc.sql("""
				select c.id, c.name, c.color_hex, (select count(*) from policy p where p.category_id = c.id)
				from policy_category c where c.org_id = :org order by c.sort, lower(c.name)""").param("org", org()).query((rs, i) -> {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", rs.getObject(1, UUID.class));
			m.put("name", rs.getString(2));
			m.put("color", rs.getString(3));
			m.put("count", rs.getLong(4));
			return m;
		}).list();
	}

	@Transactional
	public Map<String, Object> saveCategory(UUID id, Map<String, Object> body) {
		requireManager();
		String name = str(body.get("name"));
		String color = str(body.get("color"));
		if (color != null && !color.matches("^#[0-9A-Fa-f]{6}$")) {
			throw new UnprocessableException(List.of(ErrorItem.field("color", "INVALID", "Pick a colour")));
		}
		if (name != null) {
			name = name.strip();
			if (name.isEmpty() || name.length() > 60) {
				throw new UnprocessableException(List.of(ErrorItem.field("name", "INVALID", "Enter a name up to 60 characters")));
			}
			if (jdbc.sql("select count(*) from policy_category where org_id = :org and lower(name) = lower(:n) and id <> :id").param("org", org()).param("n", name)
					.param("id", id == null ? new UUID(0, 0) : id).query(Long.class).single() > 0) {
				throw new UnprocessableException(List.of(ErrorItem.field("name", "TAKEN", "“" + name + "” already exists")));
			}
		}
		if (id == null) {
			if (name == null) {
				throw new UnprocessableException(List.of(ErrorItem.field("name", "REQUIRED", "Enter a name")));
			}
			UUID nid = jdbc.sql("insert into policy_category (org_id, name, color_hex, sort) values (:org, :n, :c, (select coalesce(max(sort), 0) + 1 from policy_category where org_id = :org)) returning id")
					.param("org", org()).param("n", name).param("c", color == null ? "#2563EB" : color.toUpperCase()).query(UUID.class).single();
			return Map.of("id", nid);
		}
		if (jdbc.sql("update policy_category set name = coalesce(:n, name), color_hex = coalesce(:c, color_hex) where id = :id and org_id = :org")
				.param("n", name, java.sql.Types.VARCHAR).param("c", color == null ? null : color.toUpperCase(), java.sql.Types.VARCHAR).param("id", id)
				.param("org", org()).update() == 0) {
			throw new NotFoundException("Category not found");
		}
		return Map.of("id", id);
	}

	@Transactional
	public void deleteCategory(UUID id) {
		requireManager();
		long n = jdbc.sql("select count(*) from policy where category_id = :c").param("c", id).query(Long.class).single();
		if (n > 0) {
			throw new ConflictException("Move its " + n + (n == 1 ? " policy" : " policies") + " to another category first");
		}
		jdbc.sql("delete from policy_category where id = :id and org_id = :org").param("id", id).param("org", org()).update();
	}

	/** Starting points for a new policy: the built-in templates and the people who can own or approve one. */
	@Transactional(readOnly = true)
	public Map<String, Object> newOptions() {
		requireManager();
		List<Map<String, Object>> people = jdbc.sql("""
				select e.id, e.display_name, jt.name from employee e left join job_title jt on jt.id = e.title_id where e.org_id = :org and e.status <> 'EXITED' and e.archived_at is null
				order by e.display_name""").param("org", org()).query((rs, i) -> {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", rs.getObject(1, UUID.class));
			m.put("name", rs.getString(2));
			m.put("title", rs.getString(3));
			return m;
		}).list();
		return Map.of("templates", templates().stream().map(t -> Map.of("key", t.get("key"), "name", t.get("name"), "category", t.get("category"))).toList(),
				"people", people, "me", me() == null ? "" : me());
	}

	// ================================================================== helpers

	private Map<String, Object> policyRow(UUID id) {
		return jdbc.sql("""
				select p.id, p.name, c.id, c.name, c.color_hex, p.owner_id, o.display_name, p.approver_id, a.display_name, p.review_cycle_months,
				       p.ack_required, p.last_reviewed_on, p.archived_at, p.created_at, p.page::text, p.header_html, p.footer_html, p.applies_to
				from policy p join policy_category c on c.id = p.category_id left join employee o on o.id = p.owner_id left join employee a on a.id = p.approver_id
				where p.id = :id and p.org_id = :org""").param("id", id).param("org", org()).query((rs, i) -> {
			Map<String, Object> m = new LinkedHashMap<>();
			m.put("id", rs.getObject(1, UUID.class));
			m.put("name", rs.getString(2));
			m.put("category", Map.of("id", rs.getObject(3, UUID.class), "name", rs.getString(4), "color", rs.getString(5)));
			m.put("ownerId", rs.getObject(6, UUID.class));
			m.put("ownerName", rs.getString(7));
			m.put("owner", person(rs.getObject(6, UUID.class), rs.getString(7)));
			m.put("approverId", rs.getObject(8, UUID.class));
			m.put("approverName", rs.getString(9));
			m.put("approver", person(rs.getObject(8, UUID.class), rs.getString(9)));
			m.put("cycleMonths", rs.getInt(10));
			m.put("ackRequired", rs.getBoolean(11));
			m.put("lastReviewedOn", rs.getObject(12, LocalDate.class));
			m.put("archived", rs.getObject(13, OffsetDateTime.class) != null);
			m.put("createdAt", rs.getObject(14, OffsetDateTime.class));
			m.put("page", page(rs.getString(15)));
			m.put("header", rs.getString(16));
			m.put("footer", rs.getString(17));
			m.put("appliesTo", rs.getString(18));
			return m;
		}).optional().orElseThrow(() -> new NotFoundException("Policy not found"));
	}

	/** A body as PDF lines: sub-headings marked bold, list items with bullets, table rows with cells joined. */
	private static String pdfText(String body) {
		StringBuilder b = new StringBuilder();
		for (PolicyHtml.Block x : PolicyHtml.blocks(body)) {
			b.append("h".equals(x.kind()) ? "\u0001" : "").append(x.text()).append('\n');
		}
		return b.toString();
	}

	private static final Set<String> SIZES = Set.of("A5", "A4", "A3", "Letter", "Legal");
	private static final Set<String> MARGINS = Set.of("Narrow", "Normal", "Wide");

	/** Page layout with defaults for anything missing or unknown. */
	private static Map<String, Object> pageOf(Map<?, ?> m) {
		Map<String, Object> out = new LinkedHashMap<>();
		Object size = m.get("size");
		Object margin = m.get("margin");
		out.put("size", size != null && SIZES.contains(size.toString()) ? size.toString() : "A4");
		out.put("margin", margin != null && MARGINS.contains(margin.toString()) ? margin.toString() : "Normal");
		out.put("orient", "Landscape".equals(String.valueOf(m.get("orient"))) ? "Landscape" : "Portrait");
		out.put("logo", !Boolean.FALSE.equals(m.get("logo")));
		out.put("wm", !Boolean.FALSE.equals(m.get("wm")));
		return out;
	}

	private Map<String, Object> page(String js) {
		try {
			return pageOf(js == null ? Map.of() : json.readValue(js, new tools.jackson.core.type.TypeReference<Map<String, Object>>() {
			}));
		}
		catch (RuntimeException e) {
			return pageOf(Map.of());
		}
	}

	private static Map<String, Object> person(UUID id, String name) {
		if (id == null) {
			return null;
		}
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", id);
		m.put("name", name);
		String[] parts = name == null ? new String[0] : name.trim().split("\\s+");
		m.put("initials", parts.length == 0 ? "?" : (parts[0].substring(0, 1) + (parts.length > 1 ? parts[parts.length - 1].substring(0, 1) : "")).toUpperCase());
		return m;
	}

	private void saveDraft(UUID id, List<Section> secs, String note, boolean create) {
		String js = json.writeValueAsString(secs);
		if (create) {
			jdbc.sql("""
					insert into policy_draft (policy_id, sections, note, started_by, updated_by) values (:p, cast(:s as jsonb), :n, :by, :by)
					on conflict (policy_id) do nothing""").param("p", id).param("s", js).param("n", note, java.sql.Types.VARCHAR)
					.param("by", me(), java.sql.Types.OTHER).update();
		}
		else {
			jdbc.sql("""
					update policy_draft set sections = cast(:s as jsonb), note = :n, in_review = false, approved = false, updated_by = :by, updated_at = now()
					where policy_id = :p""").param("p", id).param("s", js).param("n", note, java.sql.Types.VARCHAR).param("by", me(), java.sql.Types.OTHER).update();
		}
	}

	private boolean hasDraft(UUID id) {
		return jdbc.sql("select count(*) from policy_draft where policy_id = :p").param("p", id).query(Long.class).single() > 0;
	}

	private String currentVersion(UUID id) {
		return jdbc.sql("select version from policy_version where policy_id = :p order by published_at desc limit 1").param("p", id).query(String.class)
				.optional().orElse(null);
	}

	private List<Section> currentSections(UUID id) {
		return jdbc.sql("select sections::text from policy_version where policy_id = :p order by published_at desc limit 1").param("p", id)
				.query(String.class).optional().map(this::sections).orElse(List.of());
	}

	private List<Section> sections(String js) {
		if (js == null) {
			return List.of();
		}
		return json.readValue(js, new TypeReference<List<Section>>() {
		});
	}

	@SuppressWarnings("unchecked")
	private static List<Section> parseSections(Object raw) {
		if (!(raw instanceof List<?> list)) {
			throw new UnprocessableException(List.of(ErrorItem.field("sections", "REQUIRED", "The policy needs at least one section")));
		}
		List<Section> out = new ArrayList<>();
		for (Object o : list) {
			if (o instanceof Map<?, ?> m) {
				String h = m.get("heading") == null ? "" : m.get("heading").toString().strip();
				String b = m.get("body") == null ? "" : m.get("body").toString();
				if (h.isEmpty() && b.isBlank()) {
					continue;
				}
				if (h.isEmpty()) {
					throw new UnprocessableException(List.of(ErrorItem.field("sections", "HEADING", "Every section needs a heading")));
				}
				if (h.length() > 120 || b.length() > 3_000_000) {
					throw new UnprocessableException(List.of(ErrorItem.field("sections", "TOO_LONG", "A section is too long")));
				}
				out.add(new Section(h, PolicyHtml.clean(b)));
			}
		}
		if (out.isEmpty()) {
			throw new UnprocessableException(List.of(ErrorItem.field("sections", "REQUIRED", "The policy needs at least one section")));
		}
		if (out.stream().mapToInt(x -> x.body().length()).sum() > 8_000_000) {
			throw new UnprocessableException(List.of(ErrorItem.field("sections", "TOO_LONG", "The policy is too large; use smaller images")));
		}
		if (out.size() > 60) {
			throw new UnprocessableException(List.of(ErrorItem.field("sections", "TOO_MANY", "Keep it to 60 sections")));
		}
		return out;
	}

	/** Section-level changes between two texts: added, removed, edited, and sentences added/removed. */
	static Map<String, Object> diff(List<Section> was, List<Section> now) {
		Map<String, String> a = new LinkedHashMap<>();
		was.forEach(s -> a.put(s.heading().toLowerCase(Locale.ROOT), s.body()));
		Map<String, String> b = new LinkedHashMap<>();
		now.forEach(s -> b.put(s.heading().toLowerCase(Locale.ROOT), s.body()));
		int added = 0;
		int removed = 0;
		int edited = 0;
		int sAdd = 0;
		int sDel = 0;
		List<Map<String, Object>> secs = new ArrayList<>();
		for (Section s : now) {
			String k = s.heading().toLowerCase(Locale.ROOT);
			if (!a.containsKey(k)) {
				added++;
				sAdd += sentences(s.body()).size();
				secs.add(Map.of("heading", s.heading(), "change", "ADDED"));
			}
			else if (!a.get(k).equals(s.body())) {
				edited++;
				Set<String> x = new HashSet<>(sentences(a.get(k)));
				Set<String> y = new HashSet<>(sentences(s.body()));
				sAdd += (int) y.stream().filter(z -> !x.contains(z)).count();
				sDel += (int) x.stream().filter(z -> !y.contains(z)).count();
				secs.add(Map.of("heading", s.heading(), "change", "EDITED"));
			}
		}
		for (Section s : was) {
			if (!b.containsKey(s.heading().toLowerCase(Locale.ROOT))) {
				removed++;
				sDel += sentences(s.body()).size();
				secs.add(Map.of("heading", s.heading(), "change", "REMOVED"));
			}
		}
		boolean reordered = added == 0 && removed == 0 && !new ArrayList<>(a.keySet()).equals(new ArrayList<>(b.keySet()));
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("added", added);
		out.put("removed", removed);
		out.put("edited", edited);
		out.put("sentencesAdded", sAdd);
		out.put("sentencesRemoved", sDel);
		out.put("reordered", reordered);
		out.put("total", added + removed + edited + (reordered ? 1 : 0));
		out.put("sections", secs);
		return out;
	}

	private static List<String> sentences(String s) {
		List<String> out = new ArrayList<>();
		for (String x : PolicyHtml.text(s).split("(?<=[.!?])\\s+|\\n+")) {
			if (!x.isBlank()) {
				out.add(x.strip());
			}
		}
		return out;
	}

	static String nextVersion(String cur, boolean major) {
		if (cur == null) {
			return "1.0";
		}
		String[] p = cur.split("\\.");
		int a = Integer.parseInt(p[0]);
		int b = Integer.parseInt(p[1]);
		return major ? (a + 1) + ".0" : a + "." + (b + 1);
	}

	private static LocalDate nextReview(OffsetDateTime published, LocalDate reviewed, int cycle) {
		if (published == null) {
			return null;
		}
		LocalDate base = reviewed != null && reviewed.isAfter(published.toLocalDate()) ? reviewed : published.toLocalDate();
		return base.plusMonths(cycle);
	}

	private void event(UUID policy, String type, String text, String version) {
		jdbc.sql("insert into policy_event (policy_id, type, text, version, actor_id) values (:p, :t, :x, :v, :by)").param("p", policy).param("t", type)
				.param("x", text).param("v", version, java.sql.Types.VARCHAR).param("by", me(), java.sql.Types.OTHER).update();
	}

	private long staff() {
		return jdbc.sql("select count(*) from employee where org_id = :org and status <> 'EXITED' and archived_at is null").param("org", org())
				.query(Long.class).single();
	}

	private void ensureCategories() {
		if (jdbc.sql("select count(*) from policy_category where org_id = :org").param("org", org()).query(Long.class).single() > 0) {
			return;
		}
		int i = 0;
		for (String[] c : DEFAULT_CATEGORIES) {
			jdbc.sql("insert into policy_category (org_id, name, color_hex, sort) values (:org, :n, :c, :s) on conflict do nothing").param("org", org())
					.param("n", c[0]).param("c", c[1]).param("s", i++).update();
		}
	}

	private UUID category(String id) {
		if (id == null || id.isBlank()) {
			throw new UnprocessableException(List.of(ErrorItem.field("categoryId", "REQUIRED", "Pick a category")));
		}
		UUID c = UUID.fromString(id);
		if (jdbc.sql("select count(*) from policy_category where id = :c and org_id = :org").param("c", c).param("org", org()).query(Long.class).single() == 0) {
			throw new UnprocessableException(List.of(ErrorItem.field("categoryId", "INVALID", "Pick a category")));
		}
		return c;
	}

	private UUID employee(String id, String field) {
		if (id == null || id.isBlank()) {
			return null;
		}
		UUID e = UUID.fromString(id);
		if (jdbc.sql("select count(*) from employee where id = :e and org_id = :org and status <> 'EXITED'").param("e", e).param("org", org())
				.query(Long.class).single() == 0) {
			throw new UnprocessableException(List.of(ErrorItem.field(field, "INVALID", "Pick an active employee")));
		}
		return e;
	}

	private static int cycle(Object v) {
		if (v == null) {
			return 12;
		}
		int c = v instanceof Number n ? n.intValue() : Integer.parseInt(v.toString());
		if (!Set.of(3, 6, 12, 24, 36).contains(c)) {
			throw new UnprocessableException(List.of(ErrorItem.field("cycleMonths", "INVALID", "Review every 3, 6, 12, 24 or 36 months")));
		}
		return c;
	}

	private static String name(String n) {
		String s = n == null ? "" : n.strip();
		if (s.isEmpty() || s.length() > 120) {
			throw new UnprocessableException(List.of(ErrorItem.field("name", "REQUIRED", "Enter a name up to 120 characters")));
		}
		return s;
	}

	private void requireFreeName(String name, UUID self) {
		if (jdbc.sql("select count(*) from policy where org_id = :org and lower(name) = lower(:n) and id <> :id").param("org", org()).param("n", name)
				.param("id", self == null ? new UUID(0, 0) : self).query(Long.class).single() > 0) {
			throw new UnprocessableException(List.of(ErrorItem.field("name", "TAKEN", "A policy with this name already exists")));
		}
	}

	@SuppressWarnings("unchecked")
	private static List<Section> templateSections(Map<String, Object> t) {
		return ((List<Map<String, Object>>) t.get("sections")).stream().map(s -> new Section((String) s.get("heading"), (String) s.get("body"))).toList();
	}

	private List<Map<String, Object>> templates() {
		if (templates == null) {
			try (InputStream in = getClass().getResourceAsStream("/policy-templates.json")) {
				templates = in == null ? List.of() : json.readValue(in, new TypeReference<List<Map<String, Object>>>() {
				});
			}
			catch (IOException ex) {
				templates = List.of();
			}
		}
		return templates;
	}

	private void requireManager() {
		if (!ctx().manager()) {
			throw new ForbiddenException("Only HR can manage policies");
		}
	}

	private Ctx ctx() {
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
		return new Ctx(keys);
	}

	private record Ctx(Set<String> keys) {

		boolean manager() {
			return keys.stream().anyMatch(MANAGERS::contains);
		}
	}

	private UUID me() {
		return currentUser.employeeId();
	}

	private UUID org() {
		return currentUser.orgId();
	}

	private static UUID id(Object o) {
		return o == null ? null : (UUID) o;
	}

	private static String str(Object o) {
		return o == null ? null : o.toString();
	}
}
