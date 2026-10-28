package com.ivoyant.hrms.assets.service;

import com.ivoyant.hrms.assets.AssetIssuer;
import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.event.AssetRequestDecidedEvent;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.security.ModuleAccess;
import com.ivoyant.hrms.common.web.CodedConflictException;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.ForbiddenException;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.common.web.UnprocessableException;
import com.ivoyant.hrms.config.FieldDefinition;
import com.ivoyant.hrms.notification.Notifier;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Asset requests: the employee asks, the manager and then IT approve (two steps; no manager means IT only), and IT assigns a matching
 * asset or seat, which marks the request fulfilled. Onboarding creates already-approved system requests through {@link AssetIssuer}.
 */
@Service
public class AssetRequestService implements AssetIssuer {

	private static final String AREA = "assets";

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final ModuleAccess access;
	private final AssetSupport support;
	private final SpecStore specs;
	private final HardwareService hardware;
	private final LicenseService licenses;
	private final Notifier notifier;
	private final ApplicationEventPublisher events;
	private final Audit audit;

	AssetRequestService(JdbcClient jdbc, CurrentUser currentUser, ModuleAccess access, AssetSupport support, SpecStore specs, HardwareService hardware, LicenseService licenses,
			Notifier notifier, ApplicationEventPublisher events, Audit audit) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.access = access;
		this.support = support;
		this.specs = specs;
		this.hardware = hardware;
		this.licenses = licenses;
		this.notifier = notifier;
		this.events = events;
		this.audit = audit;
	}

	private static UnprocessableException problem(String field, String code, String message) {
		return new UnprocessableException(List.of(ErrorItem.field(field, code, message)));
	}

	private record Req(UUID id, UUID employeeId, String kind, UUID typeId, UUID licenseId, String reason, String source, String status, int step, LocalDate dueOn,
			OffsetDateTime createdAt, String note, UUID decidedBy) {
	}

	private Req load(UUID id) {
		return jdbc.sql("select id, employee_id, kind, asset_type_id, license_id, reason, source, status, approval_step, due_on, created_at, decision_note, decided_by from asset_request where id = :id and org_id = :org")
				.param("id", id).param("org", currentUser.orgId()).query((rs, i) -> new Req(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getObject(4, UUID.class),
						rs.getObject(5, UUID.class), rs.getString(6), rs.getString(7), rs.getString(8), rs.getInt(9), rs.getObject(10, LocalDate.class), rs.getObject(11, OffsetDateTime.class),
						rs.getString(12), rs.getObject(13, UUID.class))).optional().orElseThrow(() -> new NotFoundException("Request " + id + " not found"));
	}

	private UUID managerOf(UUID employeeId) {
		return jdbc.sql("select m.id from employee e join employee m on m.id = e.manager_id where e.id = :e and m.status <> 'EXITED'").param("e", employeeId).query(UUID.class).optional().orElse(null);
	}

	private List<UUID> approvers() {
		return jdbc.sql("""
				select distinct u.employee_id from user_role ur join role r on r.id = ur.role_id join app_user u on u.id = ur.user_id
				join role_permission rp on rp.role_id = r.id join permission p on p.id = rp.permission_id
				where r.org_id = :org and ((p.module = 'assets' and p.action = 'approve') or r.key in ('super_admin'))""").param("org", currentUser.orgId()).query(UUID.class).list();
	}

	boolean canDecide(Req r) {
		UUID me = currentUser.employeeId();
		if (me == null || !"PENDING".equals(r.status()) || me.equals(r.employeeId())) {
			return false;
		}
		if (access.has("assets", "APPROVE")) {
			return true;
		}
		return r.step() == 1 && me.equals(managerOf(r.employeeId()));
	}

	// ================================================================== employee

	@Transactional(readOnly = true)
	public Map<String, Object> mine(UUID employeeId) {
		boolean admin = access.has("assets", "EDIT");
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("hardware", jdbc.sql("""
				select a.id, a.tag, a.model, a.serial, t.name, ag.assigned_on, a.warranty_until from asset_assignment ag join asset a on a.id = ag.asset_id join asset_type t on t.id = a.type_id
				where ag.employee_id = :e and ag.returned_on is null and a.deleted_at is null order by a.tag""").param("e", employeeId).query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("id", rs.getObject(1, UUID.class));
					m.put("tag", rs.getString(2));
					m.put("model", rs.getString(3));
					m.put("serial", rs.getString(4));
					m.put("type", rs.getString(5));
					m.put("since", rs.getObject(6, LocalDate.class));
					m.put("warrantyUntil", rs.getObject(7, LocalDate.class));
					return m;
				}).list());
		out.put("licences", jdbc.sql("""
				select l.id, l.lid, l.name, s.assigned_on from license_seat s join license l on l.id = s.license_id where s.employee_id = :e and s.revoked_on is null and l.deleted_at is null order by l.name""")
				.param("e", employeeId).query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("id", rs.getObject(1, UUID.class));
					m.put("lid", rs.getString(2));
					m.put("name", rs.getString(3));
					m.put("since", rs.getObject(4, LocalDate.class));
					return m;
				}).list());
		out.put("requests", jdbc.sql("""
				select r.id, r.kind, r.status, r.created_at, r.reason, coalesce(t.name, l.name), r.decision_note from asset_request r left join asset_type t on t.id = r.asset_type_id
				left join license l on l.id = r.license_id where r.employee_id = :e order by r.created_at desc limit 50""").param("e", employeeId).query((rs, i) -> {
					Map<String, Object> m = new LinkedHashMap<>();
					m.put("id", rs.getObject(1, UUID.class));
					m.put("kind", rs.getString(2));
					m.put("status", rs.getString(3));
					m.put("date", rs.getObject(4, OffsetDateTime.class));
					m.put("reason", rs.getString(5));
					m.put("item", rs.getString(6));
					m.put("note", rs.getString(7));
					return m;
				}).list());
		return out;
	}

	@Transactional(readOnly = true)
	public Map<String, Object> options(String kind) {
		Map<String, Object> out = new LinkedHashMap<>();
		if ("SOFTWARE".equalsIgnoreCase(kind)) {
			out.put("items", jdbc.sql("""
					select l.id, l.name, l.lid, l.seats, (select count(*) from license_seat s where s.license_id = l.id and s.revoked_on is null)
					from license l where l.org_id = :org and l.deleted_at is null and (l.status_id is null or exists (select 1 from asset_status st where st.id = l.status_id and coalesce(st.system_key, 'ACTIVE') = 'ACTIVE'))
					order by lower(l.name)""").param("org", currentUser.orgId()).query((rs, i) -> {
						Integer seats = (Integer) rs.getObject(4);
						Map<String, Object> m = new LinkedHashMap<>();
						m.put("id", rs.getObject(1, UUID.class));
						m.put("name", rs.getString(2));
						m.put("lid", rs.getString(3));
						m.put("noFreeSeats", seats != null && rs.getInt(5) >= seats);
						return m;
					}).list());
		}
		else {
			out.put("items", jdbc.sql("select id, name from asset_type where org_id = :org and kind = 'HARDWARE' and active order by sort, name").param("org", currentUser.orgId())
					.query((rs, i) -> Map.<String, Object>of("id", rs.getObject(1, UUID.class), "name", rs.getString(2))).list());
		}
		out.put("fields", specs.fields(AREA, null).stream().map(d -> {
			Map<String, Object> f = new LinkedHashMap<>();
			f.put("key", d.key());
			f.put("label", d.label());
			f.put("type", d.type().name());
			f.put("required", d.required());
			f.put("options", d.options());
			f.put("helpText", d.helpText());
			return f;
		}).toList());
		return out;
	}

	@Transactional
	@SuppressWarnings("unchecked")
	public Map<String, Object> submit(UUID employeeId, Map<String, Object> b) {
		String kind = String.valueOf(b.get("kind")).toUpperCase();
		if (!kind.equals("HARDWARE") && !kind.equals("SOFTWARE")) {
			throw problem("kind", "INVALID", "Choose Hardware or Software");
		}
		String reason = HardwareService.text(b.get("reason"));
		if (reason == null) {
			throw problem("reason", "REQUIRED", "Tell us why you need it");
		}
		UUID typeId = null;
		UUID licenseId = null;
		String itemName;
		if (kind.equals("HARDWARE")) {
			typeId = HardwareService.uuid(b.get("assetTypeId"), "assetTypeId");
			itemName = jdbc.sql("select name from asset_type where id = :id and org_id = :org and kind = 'HARDWARE' and active").param("id", typeId).param("org", currentUser.orgId())
					.query(String.class).optional().orElseThrow(() -> problem("assetTypeId", "INVALID", "Choose what you need"));
		}
		else {
			licenseId = HardwareService.uuid(b.get("licenseId"), "licenseId");
			itemName = jdbc.sql("select name from license where id = :id and org_id = :org and deleted_at is null").param("id", licenseId).param("org", currentUser.orgId())
					.query(String.class).optional().orElseThrow(() -> problem("licenseId", "INVALID", "Choose the software you need"));
		}
		if (duplicate(employeeId, kind, typeId, licenseId)) {
			throw new CodedConflictException("DUPLICATE_REQUEST", "You already have an open request for " + itemName);
		}
		UUID manager = managerOf(employeeId);
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				insert into asset_request (id, org_id, employee_id, kind, asset_type_id, license_id, reason, source, status, approval_step)
				values (:id, :org, :e, :k, :t, :l, :r, 'EMPLOYEE', 'PENDING', :step)""").param("id", id).param("org", currentUser.orgId()).param("e", employeeId).param("k", kind)
				.param("t", typeId, java.sql.Types.OTHER).param("l", licenseId, java.sql.Types.OTHER).param("r", reason).param("step", manager != null ? 1 : 2).update();
		Map<String, Object> fields = new LinkedHashMap<>(b.get("fields") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of());
		for (FieldDefinition d : specs.fields(AREA, null)) {
			if (d.required() && !fields.containsKey(d.key()) && d.key().toLowerCase().contains("justification")) {
				fields.put(d.key(), reason);
			}
		}
		specs.write(AREA, id, null, fields, false);
		String who = support.employeeName(employeeId);
		if (manager != null) {
			notifier.inApp(List.of(manager), "ASSET_REQUEST", who + " asked for " + itemName, reason, "/assets/requests");
		}
		else {
			notifier.inApp(approvers(), "ASSET_REQUEST", who + " asked for " + itemName, reason, "/assets/requests");
		}
		audit.created("assets", "AssetRequest", id, Map.of("kind", kind, "item", itemName));
		return Map.of("id", id, "status", "PENDING");
	}

	private boolean duplicate(UUID employeeId, String kind, UUID typeId, UUID licenseId) {
		return jdbc.sql("""
				select count(*) from asset_request where employee_id = :e and kind = :k and status in ('PENDING', 'APPROVED')
				and (:t::uuid is null or asset_type_id = :t) and (:l::uuid is null or license_id = :l)""").param("e", employeeId).param("k", kind)
				.param("t", typeId, java.sql.Types.OTHER).param("l", licenseId, java.sql.Types.OTHER).query(Long.class).single() > 0;
	}

	@Transactional
	public void cancel(UUID employeeId, UUID id) {
		Req r = load(id);
		if (!r.employeeId().equals(employeeId)) {
			throw new NotFoundException("Request " + id + " not found");
		}
		if (!"PENDING".equals(r.status())) {
			throw new CodedConflictException("NOT_PENDING", "Only a pending request can be cancelled");
		}
		jdbc.sql("update asset_request set status = 'CANCELLED', version = version + 1 where id = :id").param("id", id).update();
		audit.publish("assets", "AssetRequest", id, "CANCEL", null, null);
	}

	// ================================================================== admin

	@Transactional(readOnly = true)
	public Map<String, Object> list(String kind, String status, String mine) {
		boolean pendingForMe = "pending".equalsIgnoreCase(mine);
		boolean admin = access.has("assets", "VIEW");
		UUID me = currentUser.employeeId();
		List<Map<String, Object>> items = new ArrayList<>();
		int pending = 0;
		for (Map<String, Object> m : jdbc.sql("""
				select r.id from asset_request r where r.org_id = :org and (cast(:k as text) is null or r.kind = :k) and (cast(:s as text) is null or r.status = :s)
				order by case r.status when 'PENDING' then 0 when 'APPROVED' then 1 else 2 end, r.created_at desc limit 300""").param("org", currentUser.orgId())
				.param("k", kind == null || kind.isBlank() ? null : kind.toUpperCase()).param("s", status == null || status.isBlank() ? null : status.toUpperCase())
				.query((rs, i) -> Map.<String, Object>of("id", rs.getObject(1, UUID.class))).list()) {
			Req r = load((UUID) m.get("id"));
			boolean decide = canDecide(r);
			boolean theirReport = me != null && me.equals(managerOf(r.employeeId()));
			if (!admin && !decide && !theirReport) {
				continue;
			}
			if (pendingForMe && !decide) {
				continue;
			}
			if (decide) {
				pending++;
			}
			items.add(view(r, decide));
		}
		return Map.of("items", items, "pendingForMe", pending);
	}

	private Map<String, Object> view(Req r, boolean decide) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("id", r.id());
		m.put("requester", support.person(r.employeeId()));
		m.put("kind", r.kind());
		Map<String, Object> item = new LinkedHashMap<>();
		if ("HARDWARE".equals(r.kind())) {
			item.put("typeId", r.typeId());
			item.put("name", jdbc.sql("select name from asset_type where id = :id").param("id", r.typeId()).query(String.class).optional().orElse(""));
		}
		else {
			item.put("licenseId", r.licenseId());
			item.put("name", jdbc.sql("select name from license where id = :id").param("id", r.licenseId()).query(String.class).optional().orElse(""));
		}
		m.put("item", item);
		m.put("reason", r.reason());
		m.put("source", r.source());
		m.put("status", r.status());
		m.put("step", r.step());
		m.put("date", r.createdAt());
		m.put("dueOn", r.dueOn());
		m.put("note", r.note());
		m.put("canDecide", decide);
		m.put("fields", specs.read(AREA, r.id(), null));
		return m;
	}

	@Transactional
	public Map<String, Object> approve(UUID id, Map<String, Object> b) {
		Req r = load(id);
		if (!canDecide(r)) {
			throw new ForbiddenException("You cannot decide this request");
		}
		boolean itApprover = access.has("assets", "APPROVE");
		String note = HardwareService.text(b.get("note"));
		if (r.step() == 1 && !itApprover) {
			jdbc.sql("update asset_request set approval_step = 2, decision_note = :n, version = version + 1 where id = :id").param("n", note).param("id", id).update();
			notifier.inApp(approvers(), "ASSET_REQUEST", support.employeeName(r.employeeId()) + "'s request needs IT approval", r.reason(), "/assets/requests");
			audit.publish("assets", "AssetRequest", id, "MANAGER_APPROVE", null, note);
			return Map.of("id", id, "status", "PENDING", "step", 2);
		}
		jdbc.sql("update asset_request set status = 'APPROVED', approval_step = 2, decided_by = :by, decided_at = now(), decision_note = :n, version = version + 1 where id = :id")
				.param("by", currentUser.employeeId(), java.sql.Types.OTHER).param("n", note).param("id", id).update();
		String fulfilled = "APPROVED";
		if (b.get("fulfil") instanceof Map<?, ?> f) {
			if ("HARDWARE".equals(r.kind()) && f.get("assetId") != null) {
				UUID assetId = HardwareService.uuid(f.get("assetId"), "assetId");
				UUID assetType = jdbc.sql("select type_id from asset where id = :id and org_id = :org and deleted_at is null").param("id", assetId).param("org", currentUser.orgId()).query(UUID.class)
						.optional().orElseThrow(() -> new NotFoundException("Asset " + assetId + " not found"));
				if (!assetType.equals(r.typeId())) {
					throw problem("assetId", "INVALID", "That asset is not the type requested");
				}
				hardware.assign(assetId, Map.of("employeeId", r.employeeId().toString(), "requestId", id.toString()));
				fulfilled = "FULFILLED";
			}
			else if ("SOFTWARE".equals(r.kind())) {
				licenses.assignSeats(r.licenseId(), Map.of("employeeIds", List.of(r.employeeId().toString()), "requestId", id.toString()));
				fulfilled = "FULFILLED";
			}
		}
		notifier.inApp(List.of(r.employeeId()), "ASSET_REQUEST", "Your request was approved", note, "/me/assets");
		events.publishEvent(new AssetRequestDecidedEvent(id, r.employeeId(), fulfilled));
		audit.publish("assets", "AssetRequest", id, "APPROVE", null, note);
		return Map.of("id", id, "status", fulfilled);
	}

	@Transactional
	public Map<String, Object> reject(UUID id, Map<String, Object> b) {
		Req r = load(id);
		if (!canDecide(r)) {
			throw new ForbiddenException("You cannot decide this request");
		}
		String note = HardwareService.text(b.get("note"));
		if (note == null) {
			throw problem("note", "REASON_REQUIRED", "Add a reason so the requester knows why");
		}
		jdbc.sql("update asset_request set status = 'REJECTED', decided_by = :by, decided_at = now(), decision_note = :n, version = version + 1 where id = :id")
				.param("by", currentUser.employeeId(), java.sql.Types.OTHER).param("n", note).param("id", id).update();
		notifier.inApp(List.of(r.employeeId()), "ASSET_REQUEST", "Your request was declined", note, "/me/assets");
		events.publishEvent(new AssetRequestDecidedEvent(id, r.employeeId(), "REJECTED"));
		audit.publish("assets", "AssetRequest", id, "REJECT", null, note);
		return Map.of("id", id, "status", "REJECTED");
	}

	// ================================================================== AssetIssuer (onboarding)

	@Override
	@Transactional
	public UUID requestLaptop(UUID employeeId, UUID typeId, LocalDate dueOn) {
		UUID type = typeId != null ? typeId : jdbc.sql("select id from asset_type where org_id = :org and kind = 'HARDWARE' and active and lower(name) = 'laptop'").param("org", currentUser.orgId())
				.query(UUID.class).optional().orElseThrow(() -> new NotFoundException("There is no Laptop hardware type"));
		return systemRequest(employeeId, "HARDWARE", type, null, dueOn, "Laptop for a new joiner");
	}

	@Override
	@Transactional
	public List<UUID> requestLicences(UUID employeeId, List<UUID> licenseIds, LocalDate dueOn) {
		List<UUID> out = new ArrayList<>();
		for (UUID l : new LinkedHashSet<>(licenseIds)) {
			out.add(systemRequest(employeeId, "SOFTWARE", null, l, dueOn, "Software for a new joiner"));
		}
		return out;
	}

	private UUID systemRequest(UUID employeeId, String kind, UUID typeId, UUID licenseId, LocalDate dueOn, String reason) {
		UUID existing = jdbc.sql("""
				select id from asset_request where employee_id = :e and kind = :k and status in ('PENDING', 'APPROVED') and (:t::uuid is null or asset_type_id = :t) and (:l::uuid is null or license_id = :l) limit 1""")
				.param("e", employeeId).param("k", kind).param("t", typeId, java.sql.Types.OTHER).param("l", licenseId, java.sql.Types.OTHER).query(UUID.class).optional().orElse(null);
		if (existing != null) {
			return existing;
		}
		UUID id = UUID.randomUUID();
		jdbc.sql("""
				insert into asset_request (id, org_id, employee_id, kind, asset_type_id, license_id, reason, source, status, approval_step, due_on)
				values (:id, :org, :e, :k, :t, :l, :r, 'ONBOARDING', 'APPROVED', 2, :due)""").param("id", id).param("org", currentUser.orgId()).param("e", employeeId).param("k", kind)
				.param("t", typeId, java.sql.Types.OTHER).param("l", licenseId, java.sql.Types.OTHER).param("r", reason).param("due", dueOn).update();
		notifier.inApp(approvers(), "ASSET_REQUEST", support.employeeName(employeeId) + " needs " + (kind.equals("HARDWARE") ? "a laptop" : "software"), reason, "/assets/requests");
		return id;
	}

	// ================================================================== recovery and profile tab

	@Transactional(readOnly = true)
	public Map<String, Object> recovery(UUID employeeId) {
		Map<String, Object> out = new LinkedHashMap<>(mine(employeeId));
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> hw = (List<Map<String, Object>>) out.get("hardware");
		out.put("pendingRecovery", hw.size());
		out.put("clear", hw.isEmpty());
		return out;
	}
}
