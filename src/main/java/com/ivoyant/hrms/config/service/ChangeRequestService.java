package com.ivoyant.hrms.config.service;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.event.FieldValueChangedEvent;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.ConflictException;
import com.ivoyant.hrms.common.web.ForbiddenException;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.config.EmployeeLookup;
import com.ivoyant.hrms.config.domain.ConfigArea;
import com.ivoyant.hrms.config.domain.ConfigChangeRequest;
import com.ivoyant.hrms.config.domain.ConfigField;
import com.ivoyant.hrms.config.dto.ChangeRequestResponse;
import com.ivoyant.hrms.config.repo.ConfigChangeRequestRepository;
import com.ivoyant.hrms.config.repo.ConfigFieldRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The approval inbox for HR-approves fields. */
@Service
public class ChangeRequestService {

	private final ConfigChangeRequestRepository requests;
	private final ConfigFieldRepository fields;
	private final AreaCatalog catalog;
	private final FormService forms;
	private final Approvers approvers;
	private final ObjectProvider<EmployeeLookup> employees;
	private final CurrentUser currentUser;
	private final Audit audit;
	private final ApplicationEventPublisher events;

	public ChangeRequestService(ConfigChangeRequestRepository requests, ConfigFieldRepository fields,
			AreaCatalog catalog, FormService forms, Approvers approvers, ObjectProvider<EmployeeLookup> employees,
			CurrentUser currentUser, Audit audit, ApplicationEventPublisher events) {
		this.requests = requests;
		this.fields = fields;
		this.catalog = catalog;
		this.forms = forms;
		this.approvers = approvers;
		this.employees = employees;
		this.currentUser = currentUser;
		this.audit = audit;
		this.events = events;
	}

	/** Requests the caller may decide, or their own with {@code mine}. */
	@Transactional(readOnly = true)
	public List<ChangeRequestResponse> list(String status, String areaKey, boolean mine) {
		UUID caller = currentUser.requireEmployeeId();
		String wanted = status == null || status.isBlank() ? ConfigChangeRequest.PENDING : status.toUpperCase();
		List<ConfigChangeRequest> rows = requests.findByOrgIdAndStatusOrderByRequestedAtDesc(currentUser.orgId(), wanted)
				.stream().filter(r -> areaKey == null || areaKey.isBlank() || r.getAreaKey().equals(areaKey))
				.filter(r -> mine ? r.getRequestedBy().equals(caller) : approvers.canDecide(catalog.area(r.getAreaKey())))
				.toList();
		Map<UUID, ConfigField> fieldById = fields.findAllById(rows.stream().map(ConfigChangeRequest::getFieldId).toList())
				.stream().collect(Collectors.toMap(ConfigField::getId, Function.identity()));
		Set<UUID> ids = new HashSet<>(rows.stream().map(ConfigChangeRequest::getRequestedBy).toList());
		Map<UUID, String> names = employees.getIfAvailable() == null ? Map.of() : employees.getIfAvailable().names(ids);
		return rows.stream().map(r -> {
			ConfigField f = fieldById.get(r.getFieldId());
			return new ChangeRequestResponse(r.getId(), r.getAreaKey(), catalog.area(r.getAreaKey()).getName(),
					r.getRecordId(), forms.recordLabel(r.getAreaKey(), r.getRecordId()), f.getFieldKey(), f.getLabel(),
					r.getOldValue(), r.getNewValue(), names.getOrDefault(r.getRequestedBy(), "Someone"),
					r.getRequestedAt(), r.getStatus(), r.getNote());
		}).toList();
	}

	@Transactional
	public void approve(UUID id) {
		ConfigChangeRequest r = openRequest(id);
		ConfigArea area = catalog.area(r.getAreaKey());
		requireApprover(area);
		ConfigField field = fields.findById(r.getFieldId()).orElseThrow(() -> new NotFoundException("Field not found"));
		if (!field.isActive()) {
			throw new ConflictException("The field is no longer active, so the change cannot be applied");
		}
		Map<String, Object> applied = new HashMap<>();
		applied.put(field.getFieldKey(), r.getNewValue());
		forms.persist(r.getAreaKey(), r.getRecordId(), applied);
		decide(r, ConfigChangeRequest.APPROVED, null);
		audit.publish("config", "ChangeRequest", id, "APPROVE", r.getOldValue(), r.getNewValue());
		events.publishEvent(new FieldValueChangedEvent(r.getAreaKey(), r.getRecordId(), List.of(field.getFieldKey()),
				currentUser.employeeId()));
	}

	@Transactional
	public void reject(UUID id, String note) {
		ConfigChangeRequest r = openRequest(id);
		requireApprover(catalog.area(r.getAreaKey()));
		decide(r, ConfigChangeRequest.REJECTED, note);
		audit.publish("config", "ChangeRequest", id, "REJECT", r.getOldValue(), r.getNewValue());
	}

	@Transactional
	public void cancel(UUID id) {
		ConfigChangeRequest r = openRequest(id);
		if (!r.getRequestedBy().equals(currentUser.employeeId())) {
			throw new ForbiddenException("Only the person who asked can withdraw a change");
		}
		decide(r, ConfigChangeRequest.CANCELLED, null);
		audit.publish("config", "ChangeRequest", id, "CANCEL", r.getOldValue(), r.getNewValue());
	}

	private ConfigChangeRequest openRequest(UUID id) {
		ConfigChangeRequest r = requests.findByIdAndOrgId(id, currentUser.orgId())
				.orElseThrow(() -> new NotFoundException("Change request " + id + " not found"));
		if (!ConfigChangeRequest.PENDING.equals(r.getStatus())) {
			throw new ConflictException("This change was already " + r.getStatus().toLowerCase());
		}
		return r;
	}

	private void requireApprover(ConfigArea area) {
		if (!approvers.canDecide(area)) {
			throw new ForbiddenException("You cannot decide changes in " + area.getName());
		}
	}

	private void decide(ConfigChangeRequest r, String status, String note) {
		r.setStatus(status);
		r.setDecidedBy(currentUser.employeeId());
		r.setDecidedAt(Instant.now());
		r.setNote(note);
		requests.saveAndFlush(r);
	}
}
