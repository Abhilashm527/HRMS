package com.ivoyant.hrms.onboarding.service;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.event.EmployeeOnboardedEvent;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.CodedConflictException;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.StatusException;
import com.ivoyant.hrms.common.web.UnprocessableException;
import com.ivoyant.hrms.assets.AssetIssuer;
import com.ivoyant.hrms.common.storage.FileStore;
import com.ivoyant.hrms.employee.EmployeeDocuments;
import com.ivoyant.hrms.employee.EmployeeProvisioning;
import com.ivoyant.hrms.notification.Notifier;
import com.ivoyant.hrms.onboarding.domain.Hire;
import com.ivoyant.hrms.onboarding.m365.M365Client;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Turns a hire into an employee, once: copies the verified data across and closes the pipeline row. */
@Service
public class HireJoinService {

	static final List<String> ESS_TASKS = List.of("docs", "offer", "profile", "policies", "laptop", "m365", "buddy", "goals");

	private final JdbcClient jdbc;
	private final HireRepo repo;
	private final HireStatusService status;
	private final EmployeeProvisioning employees;
	private final PortalService portal;
	private final InviteService invites;
	private final EventLog events;
	private final Audit audit;
	private final Notifier notifier;
	private final HrOwners owners;
	private final ApplicationEventPublisher publisher;
	private final CurrentUser currentUser;
	private final EmployeeDocuments employeeDocuments;
	private final FileStore store;
	private final AssetIssuer assets;

	HireJoinService(JdbcClient jdbc, HireRepo repo, HireStatusService status, EmployeeProvisioning employees, PortalService portal,
			InviteService invites, EventLog events, Audit audit, Notifier notifier, HrOwners owners, ApplicationEventPublisher publisher,
			CurrentUser currentUser, EmployeeDocuments employeeDocuments, FileStore store, AssetIssuer assets) {
		this.assets = assets;
		this.employeeDocuments = employeeDocuments;
		this.store = store;
		this.jdbc = jdbc;
		this.repo = repo;
		this.status = status;
		this.employees = employees;
		this.portal = portal;
		this.invites = invites;
		this.events = events;
		this.audit = audit;
		this.notifier = notifier;
		this.owners = owners;
		this.publisher = publisher;
		this.currentUser = currentUser;
	}

	private UUID create(Hire h, Map<String, Object> m365, UUID titleId, Map<String, Object> profile) {
		return employees.create(new EmployeeProvisioning.Provision(h.first(), h.last(), (String) m365.get("upn"), h.personalEmail(), h.phone(),
				h.departmentId(), titleId, h.locationId(), h.managerId(), h.employmentTypeId(), h.doj(), h.contractEnd(), h.probationMonths(), profile));
	}

	@Transactional
	public Map<String, Object> join(UUID hireId, boolean force, String reason) {
		Hire h = repo.lock(hireId);
		if (h.joinedAt() != null) {
			throw new StatusException(HttpStatus.CONFLICT, "ALREADY_JOINED", "This hire already joined", Map.of("employeeId", String.valueOf(h.employeeId())));
		}
		if (h.cancelledAt() != null) {
			throw new CodedConflictException("CANCELLED", "This hire was cancelled");
		}
		status.recompute(hireId);
		h = repo.require(hireId);
		if (!"READY_TO_JOIN".equals(h.status())) {
			if (!force) {
				throw new UnprocessableException(List.of(ErrorItem.field(null, "NOT_READY", h.name() + " is not ready to join yet. Verify the documents first, or join with force.")));
			}
			if (reason == null || reason.isBlank()) {
				throw new UnprocessableException(List.of(ErrorItem.field("reason", "REASON_REQUIRED", "Add a reason for joining early")));
			}
		}
		Map<String, Object> m365 = jdbc.sql("select upn, entra_user_id, simulated from onboarding_m365 where hire_id = :h and state = 'ACTIVE'").param("h", hireId)
				.query((rs, i) -> Map.<String, Object>of("upn", rs.getString(1), "entra", String.valueOf(rs.getString(2)), "sim", rs.getBoolean(3))).optional().orElse(null);
		if (m365 == null) {
			throw new UnprocessableException(List.of(ErrorItem.field(null, "NO_WORK_EMAIL", "Create the Microsoft 365 work account before joining")));
		}
		Map<String, Object> profile = portal.decryptedValues(hireId);
		if (h.dob() != null) {
			profile.putIfAbsent("dob", h.dob().toString());
		}
		if (h.gender() != null) {
			profile.putIfAbsent("gender", h.gender());
		}
		jdbc.sql("""
				select f.profile_field_key, v.value #>> '{}' from config_field f join config_field_value v on v.field_id = f.id
				where v.record_id = :h and v.area_key = 'onboarding' and f.profile_field_key is not null""").param("h", hireId)
				.query((rs, i) -> {
					if (rs.getString(2) != null) {
						profile.put(rs.getString(1), rs.getString(2));
					}
					return 0;
				}).list();
		UUID titleId = h.titleId() != null ? h.titleId() : jdbc.sql(
				"select id from job_title where org_id = :o and active and lower(name) = lower(:t) order by (department_id is null) limit 1")
				.param("o", currentUser.orgId()).param("t", h.title()).query(UUID.class).optional().orElse(null);
		UUID employeeId;
		try {
			employeeId = create(h, m365, titleId, profile);
		}
		catch (UnprocessableException ex) {
			boolean position = ex.getErrors().stream().anyMatch(e -> "designationId".equals(e.fieldKey()));
			if (position && titleId == null) {
				throw new UnprocessableException(List.of(ErrorItem.field("title", "TITLE_NOT_FOUND",
						"'" + h.title() + "' is not a job title in Settings → Job titles. Edit the hire's title to an existing one.")));
			}
			throw ex;
		}
		jdbc.sql("update onboarding_hire set employee_id = :e, joined_at = now(), status = 'JOINED', title_id = :t, version = version + 1, updated_at = now() where id = :id")
				.param("e", employeeId).param("t", titleId, java.sql.Types.OTHER).param("id", hireId).update();
		jdbc.sql("update employee set hire_id = :h where id = :e").param("h", hireId).param("e", employeeId).update();
		copyDocuments(hireId, employeeId);
		if (jdbc.sql("select count(*) from onboarding_task where hire_id = :h and owner = 'IT' and lower(name) like '%laptop%'").param("h", hireId).query(Long.class).single() > 0) {
			try {
				assets.requestLaptop(employeeId, null, h.doj());
			}
			catch (RuntimeException ex) {
				events.add(hireId, "BAD", "Laptop request not created", ex.getMessage());
			}
		}
		invites.revoke(hireId);
		if (!Boolean.TRUE.equals(m365.get("sim")) && !"null".equals(m365.get("entra"))) {
			jdbc.sql("update app_user set external_subject = :s where employee_id = :e and external_subject is null").param("s", m365.get("entra")).param("e", employeeId).update();
		}
		for (String key : ESS_TASKS) {
			jdbc.sql("insert into employee_onboarding_task (employee_id, task_key) values (:e, :k) on conflict do nothing").param("e", employeeId).param("k", key).update();
		}
		events.add(hireId, "OK", "Joined", force ? "Joined early by HR: " + reason.trim() : h.name() + " is now an employee");
		audit.publish("onboarding", "Hire", hireId, force ? "JOIN_FORCE" : "JOIN", null, force ? reason.trim() : employeeId.toString());
		publisher.publishEvent(new EmployeeOnboardedEvent(currentUser.orgId(), employeeId, hireId));
		notifier.inApp(Set.of(employeeId), "Welcome to Ivoyant, " + h.first() + "!", "Your first 90 days checklist is ready.", "/me/onboarding");
		Set<UUID> tell = owners.forHire(h);
		tell.remove(employeeId);
		notifier.inApp(tell, h.name() + " joined", h.name() + " is now an employee.", "/employees/" + employeeId);
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("employeeId", employeeId);
		out.put("hireId", hireId);
		return out;
	}

	/** Verified hire documents go on the employee's file (category from the document group). */
	private void copyDocuments(UUID hireId, UUID employeeId) {
		List<Map<String, Object>> docs = jdbc.sql("""
				select od.name, od.doc_group, f.file_name, f.mime, f.storage_key
				from onboarding_hire_document d join onboarding_document od on od.id = d.document_id
				join onboarding_hire_file f on f.hire_document_id = d.id and f.superseded_at is null
				where d.hire_id = :h and d.status = 'VERIFIED'""").param("h", hireId).query((rs, i) -> Map.<String, Object>of("name", rs.getString(1),
						"group", rs.getString(2), "file", rs.getString(3), "mime", rs.getString(4), "key", rs.getString(5))).list();
		for (Map<String, Object> d : docs) {
			try {
				String group = (String) d.get("group");
				String category = switch (group) {
					case "Bank" -> "Banking & statutory";
					case "Contract" -> "Offer";
					default -> group;
				};
				employeeDocuments.addVerified(employeeId, (String) d.get("name"), category, (String) d.get("file"), (String) d.get("mime"),
						store.get((String) d.get("key")), "ONBOARDING");
			}
			catch (java.io.IOException | RuntimeException ex) {
				events.add(hireId, "BAD", "Document not copied", d.get("name") + ": " + ex.getMessage());
			}
		}
	}
}
