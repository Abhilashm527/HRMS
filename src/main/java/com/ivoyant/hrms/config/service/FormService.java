package com.ivoyant.hrms.config.service;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.event.FieldChangeRequestedEvent;
import com.ivoyant.hrms.common.event.FieldValueChangedEvent;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.ForbiddenException;
import com.ivoyant.hrms.common.web.UnprocessableException;
import com.ivoyant.hrms.config.EmployeeLookup;
import com.ivoyant.hrms.config.FieldAccess;
import com.ivoyant.hrms.config.FieldConfig;
import com.ivoyant.hrms.config.FieldDefinition;
import com.ivoyant.hrms.config.FieldType;
import com.ivoyant.hrms.config.FieldValues;
import com.ivoyant.hrms.config.FormField;
import com.ivoyant.hrms.config.FormSection;
import com.ivoyant.hrms.config.FormView;
import com.ivoyant.hrms.config.PendingChange;
import com.ivoyant.hrms.config.RecordFieldStore;
import com.ivoyant.hrms.config.RecordPolicy;
import com.ivoyant.hrms.config.WriteResult;
import com.ivoyant.hrms.config.domain.ConfigArea;
import com.ivoyant.hrms.config.domain.ConfigChangeRequest;
import com.ivoyant.hrms.config.domain.ConfigField;
import com.ivoyant.hrms.config.domain.ConfigFieldValue;
import com.ivoyant.hrms.config.domain.ConfigFieldValueId;
import com.ivoyant.hrms.config.domain.ConfigRecordOwner;
import com.ivoyant.hrms.config.domain.ConfigRecordOwnerId;
import com.ivoyant.hrms.config.domain.ConfigSection;
import com.ivoyant.hrms.config.repo.ConfigChangeRequestRepository;
import com.ivoyant.hrms.config.repo.ConfigFieldValueRepository;
import com.ivoyant.hrms.config.repo.ConfigRecordOwnerRepository;
import com.ivoyant.hrms.org.OrgDirectory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Renders and writes the forms of every area: what the caller sees (per-field level, masking, conditions), what
 * they may write (validation, defaults, direct write or change request), and the facade other modules call.
 */
@Service
public class FormService implements FieldConfig {

	private final AreaCatalog catalog;
	private final FieldAccessResolver resolver;
	private final Approvers approvers;
	private final ConfigFieldValueRepository valueRepo;
	private final ConfigChangeRequestRepository requestRepo;
	private final ConfigRecordOwnerRepository ownerRepo;
	private final ObjectProvider<RecordPolicy> policies;
	private final ObjectProvider<RecordFieldStore> stores;
	private final ObjectProvider<EmployeeLookup> employees;
	private final OrgDirectory org;
	private final CurrentUser currentUser;
	private final Audit audit;
	private final ApplicationEventPublisher events;

	public FormService(AreaCatalog catalog, FieldAccessResolver resolver, Approvers approvers,
			ConfigFieldValueRepository valueRepo, ConfigChangeRequestRepository requestRepo,
			ConfigRecordOwnerRepository ownerRepo, ObjectProvider<RecordPolicy> policies,
			ObjectProvider<RecordFieldStore> stores, ObjectProvider<EmployeeLookup> employees, OrgDirectory org,
			CurrentUser currentUser, Audit audit, ApplicationEventPublisher events) {
		this.catalog = catalog;
		this.resolver = resolver;
		this.approvers = approvers;
		this.valueRepo = valueRepo;
		this.requestRepo = requestRepo;
		this.ownerRepo = ownerRepo;
		this.policies = policies;
		this.stores = stores;
		this.employees = employees;
		this.org = org;
		this.currentUser = currentUser;
		this.audit = audit;
		this.events = events;
	}

	// ---------------------------------------------------------------- facade: definitions and levels

	@Override
	@Transactional(readOnly = true)
	public List<FieldDefinition> activeFields(String areaKey) {
		return catalog.definitions(areaKey);
	}

	@Override
	@Transactional(readOnly = true)
	public Map<String, FieldAccess> levels(String areaKey, UUID ownerEmployeeId) {
		return resolver.levels(catalog.area(areaKey), catalog.activeFields(areaKey), currentUser.requireEmployeeId(),
				ownerEmployeeId);
	}

	@Override
	@Transactional(readOnly = true)
	public FieldAccess levelFor(String areaKey, String fieldKey, UUID ownerEmployeeId) {
		return levels(areaKey, ownerEmployeeId).getOrDefault(fieldKey, FieldAccess.HIDDEN);
	}

	@Override
	@Transactional(readOnly = true)
	public Set<String> hiddenByCondition(String areaKey, Map<String, Object> values) {
		return AreaCatalog.hidden(catalog.definitions(areaKey), values);
	}

	// ---------------------------------------------------------------- reading

	/** Policy/owner/record-level helpers used by the controller too. */
	public UUID ownerOf(String areaKey, UUID recordId) {
		RecordPolicy policy = policy(areaKey);
		if (policy != null) {
			return policy.owner(areaKey, recordId);
		}
		return ownerRepo.findById(new ConfigRecordOwnerId(currentUser.orgId(), areaKey, recordId))
				.map(ConfigRecordOwner::getOwnerEmployeeId).orElse(null);
	}

	public boolean canOpen(String areaKey, UUID recordId) {
		RecordPolicy policy = policy(areaKey);
		return policy == null || policy.canOpen(areaKey, recordId);
	}

	@Override
	@Transactional(readOnly = true)
	public FormView render(String areaKey, UUID recordId, UUID ownerEmployeeId) {
		ConfigArea area = catalog.area(areaKey);
		List<ConfigField> fields = formFields(areaKey);
		Map<String, Object> values = currentValues(areaKey, recordId);
		Map<String, ConfigChangeRequest> pending = requestRepo
				.findByOrgIdAndAreaKeyAndRecordIdAndStatus(currentUser.orgId(), areaKey, recordId,
						ConfigChangeRequest.PENDING)
				.stream().collect(Collectors.toMap(r -> r.getFieldId().toString(), r -> r, (a, b) -> a));
		return build(area, fields, ownerEmployeeId, recordId, values, pending);
	}

	@Override
	@Transactional(readOnly = true)
	public FormView blank(String areaKey, UUID ownerEmployeeId) {
		return blank(areaKey, ownerEmployeeId, Map.of());
	}

	/** A blank form with some values filled in, e.g. the work location a department defaults to. */
	@Transactional(readOnly = true)
	public FormView blank(String areaKey, UUID ownerEmployeeId, Map<String, Object> prefill) {
		ConfigArea area = catalog.area(areaKey);
		List<ConfigField> fields = formFields(areaKey);
		Map<String, Object> defaults = new HashMap<>();
		for (ConfigField f : fields) {
			if (f.getDefaultValue() != null) {
				defaults.put(f.getFieldKey(), f.getDefaultValue());
			}
		}
		defaults.putAll(prefill);
		return build(area, fields, ownerEmployeeId, null, defaults, Map.of());
	}

	/** Fields the generic forms show: active, in active sections, in sections not rendered by code. */
	private List<ConfigField> formFields(String areaKey) {
		Set<UUID> shown = catalog.sections(areaKey).stream().filter(s -> s.isActive() && !s.isLocked())
				.map(ConfigSection::getId).collect(Collectors.toSet());
		return catalog.fields(areaKey).stream().filter(f -> f.isActive() && shown.contains(f.getSectionId())).toList();
	}

	private FormView build(ConfigArea area, List<ConfigField> fields, UUID owner, UUID recordId,
			Map<String, Object> values, Map<String, ConfigChangeRequest> pending) {
		UUID caller = currentUser.requireEmployeeId();
		Map<String, FieldAccess> levels = resolver.levels(area, fields, caller, owner);
		Map<String, String> sectionKeys = new HashMap<>();
		Map<UUID, ConfigSection> sections = AreaCatalog.byId(catalog.sections(area.getAreaKey()), ConfigSection::getId);
		sections.values().forEach(s -> sectionKeys.put(s.getId().toString(), s.getSectionKey()));
		List<FieldDefinition> defs = fields.stream()
				.map(f -> AreaCatalog.definition(f, sectionKeys.get(f.getSectionId().toString()))).toList();
		Set<String> hidden = AreaCatalog.hidden(defs, values);
		boolean profile = AreaCatalog.PROFILE.equals(area.getAreaKey());
		Lookups lookups = new Lookups();
		defs.forEach(d -> lookups.collect(d, values.get(d.key())));
		pending.values().forEach(r -> lookups.employeeIds.add(r.getRequestedBy()));
		lookups.load(org, employees.getIfAvailable());

		Map<UUID, List<FormField>> bySection = new LinkedHashMap<>();
		for (FieldDefinition d : defs) {
			FieldAccess level = levels.getOrDefault(d.key(), FieldAccess.HIDDEN);
			if (level == FieldAccess.HIDDEN || hidden.contains(d.key())) {
				continue;
			}
			if (d.type() == FieldType.HEADING) {
				if (profile) {
					bySection.computeIfAbsent(d.sectionId(), k -> new ArrayList<>()).add(new FormField(d.key(),
							d.label(), d.type(), level, false, null, List.of(), null, null, null));
				}
				continue;
			}
			Object value = values.get(d.key());
			boolean masked = level == FieldAccess.MASKED;
			ConfigChangeRequest req = level == FieldAccess.HR_APPROVES ? pending.get(d.id().toString()) : null;
			String help = d.helpText() != null ? d.helpText()
					: (level == FieldAccess.HR_APPROVES ? "Changes go to HR for approval." : null);
			bySection.computeIfAbsent(d.sectionId(), k -> new ArrayList<>()).add(new FormField(d.key(), d.label(),
					d.type(), level, d.required(), help, d.options(), masked ? null : value,
					masked ? mask(value) : lookups.display(d, value), req == null ? null : pendingOf(d, req, lookups, area)));
		}
		List<FormSection> out = new ArrayList<>();
		for (ConfigSection s : sections.values().stream()
				.sorted(java.util.Comparator.comparingInt(ConfigSection::getSort)).toList()) {
			List<FormField> list = bySection.get(s.getId());
			if (list != null && !list.isEmpty()) {
				out.add(new FormSection(s.getName(), false, list));
			}
		}
		boolean showName = out.size() > 1;
		out = out.stream().map(s -> new FormSection(s.name(), showName, s.fields())).toList();
		String title = out.size() == 1 ? out.get(0).name() : area.getName();
		return new FormView(title, area.getAreaKey(), recordId, FieldAccessResolver.side(owner, caller).name(), out);
	}

	private PendingChange pendingOf(FieldDefinition d, ConfigChangeRequest r, Lookups lookups, ConfigArea area) {
		UUID caller = currentUser.employeeId();
		String shown = lookups.display(d, r.getNewValue());
		return new PendingChange(r.getId(), r.getNewValue(), lookups.employeeName(r.getRequestedBy()),
				r.getRequestedAt(), "Change to “" + shown + "” waiting for approval", approvers.canDecide(area),
				r.getRequestedBy().equals(caller));
	}

	// ---------------------------------------------------------------- writing

	/** Everything worked out before anything is written. */
	private record Prepared(ConfigArea area, UUID caller, Map<String, FieldDefinition> defs,
			Map<String, FieldAccess> levels, Map<String, Object> current, Map<String, Object> normalized,
			List<String> forbidden, List<ErrorItem> errors, Map<String, ConfigField> fields) {
	}

	private Prepared prepare(String areaKey, UUID recordId, UUID owner, Map<String, Object> incoming, boolean draft) {
		ConfigArea area = catalog.area(areaKey);
		UUID caller = currentUser.requireEmployeeId();
		List<ConfigField> fields = catalog.activeFields(areaKey);
		Map<String, ConfigField> fieldByKey = fields.stream()
				.collect(Collectors.toMap(ConfigField::getFieldKey, Function.identity()));
		Map<String, String> sectionKeys = catalog.sections(areaKey).stream()
				.collect(Collectors.toMap(s -> s.getId().toString(), ConfigSection::getSectionKey));
		Map<String, FieldDefinition> defs = new LinkedHashMap<>();
		for (ConfigField f : fields) {
			defs.put(f.getFieldKey(), AreaCatalog.definition(f, sectionKeys.get(f.getSectionId().toString())));
		}
		Map<String, FieldAccess> levels = resolver.levels(area, fields, caller, owner);
		Map<String, Object> current = draft || recordId == null ? new HashMap<>() : currentValues(areaKey, recordId);
		Map<String, Object> merged = new HashMap<>(current);
		merged.putAll(incoming);
		Set<String> hidden = AreaCatalog.hidden(List.copyOf(defs.values()), merged);

		List<ErrorItem> errors = new ArrayList<>();
		List<String> forbidden = new ArrayList<>();
		Map<String, Object> normalized = new LinkedHashMap<>();
		Lookups refs = new Lookups();
		for (Map.Entry<String, Object> e : incoming.entrySet()) {
			String key = e.getKey();
			FieldDefinition def = defs.get(key);
			if (def == null) {
				errors.add(ErrorItem.field(key, "UNKNOWN", "There is no field '" + key + "' in this form"));
				continue;
			}
			if (hidden.contains(key)) {
				continue; // hidden by its condition: the value is ignored
			}
			FieldAccess level = levels.getOrDefault(key, FieldAccess.HIDDEN);
			if (level != FieldAccess.EDIT && level != FieldAccess.HR_APPROVES) {
				forbidden.add(key);
				continue;
			}
			try {
				Object value = FieldValues.normalize(def, e.getValue());
				if (value != null) {
					refs.collect(def, value);
				}
				normalized.put(key, value);
			}
			catch (FieldValues.Invalid ex) {
				errors.add(ErrorItem.field(key, ex.code(), ex.getMessage()));
			}
		}
		for (Map.Entry<String, Object> e : normalized.entrySet()) {
			String problem = refs.missing(defs.get(e.getKey()), e.getValue(), org, employees.getIfAvailable());
			if (problem != null) {
				errors.add(ErrorItem.field(e.getKey(), "INVALID", problem));
			}
		}
		if (draft) {
			for (FieldDefinition def : defs.values()) {
				FieldAccess level = levels.getOrDefault(def.key(), FieldAccess.HIDDEN);
				boolean writable = level == FieldAccess.EDIT || level == FieldAccess.HR_APPROVES;
				if (!writable || hidden.contains(def.key()) || !def.type().hasValue() || incoming.containsKey(def.key())) {
					continue;
				}
				if (def.defaultValue() != null) {
					try {
						normalized.put(def.key(), FieldValues.normalize(def, def.defaultValue()));
					}
					catch (FieldValues.Invalid ignored) {
						// a bad default is the admin's problem, not the caller's
					}
				}
				else if (def.required()) {
					errors.add(ErrorItem.field(def.key(), "REQUIRED", def.label() + " is required"));
				}
			}
		}
		return new Prepared(area, caller, defs, levels, current, normalized, forbidden, errors, fieldByKey);
	}

	@Override
	@Transactional
	public WriteResult write(String areaKey, UUID recordId, UUID ownerEmployeeId, Map<String, Object> values,
			boolean draft) {
		Prepared p = prepare(areaKey, recordId, ownerEmployeeId, values, draft);
		if (!p.forbidden().isEmpty()) {
			throw new ForbiddenException("You cannot edit: " + String.join(", ", p.forbidden().stream().sorted().toList()));
		}
		if (!p.errors().isEmpty()) {
			throw new UnprocessableException(p.errors());
		}
		UUID orgId = currentUser.orgId();
		Map<String, Object> direct = new LinkedHashMap<>();
		List<String> pending = new ArrayList<>();
		for (Map.Entry<String, Object> e : p.normalized().entrySet()) {
			String key = e.getKey();
			FieldAccess level = p.levels().get(key);
			boolean same = !draft && canon(p.current().get(key)).equals(canon(e.getValue()));
			if (same) {
				continue;
			}
			if (level == FieldAccess.HR_APPROVES && !draft) {
				requestChange(p, recordId, key, e.getValue());
				pending.add(key);
			}
			else {
				direct.put(key, e.getValue());
			}
		}
		if (!direct.isEmpty()) {
			persist(areaKey, recordId, direct);
		}
		if (draft && ownerEmployeeId != null && policy(areaKey) == null) {
			ownerRepo.save(new ConfigRecordOwner(orgId, areaKey, recordId, ownerEmployeeId));
		}
		if (!direct.isEmpty()) {
			Map<String, Object> before = new LinkedHashMap<>();
			direct.keySet().forEach(k -> before.put(k, p.current().get(k)));
			audit.updated("config", "FieldValues:" + areaKey, recordId, before, direct);
			events.publishEvent(new FieldValueChangedEvent(areaKey, recordId, List.copyOf(direct.keySet()), p.caller()));
		}
		FormView form = render(areaKey, recordId, ownerEmployeeId);
		return new WriteResult(List.copyOf(direct.keySet()), pending, form);
	}

	private void requestChange(Prepared p, UUID recordId, String key, Object newValue) {
		UUID orgId = currentUser.orgId();
		ConfigField field = p.fields().get(key);
		requestRepo.findByOrgIdAndAreaKeyAndRecordIdAndFieldIdAndStatus(orgId, p.area().getAreaKey(), recordId,
				field.getId(), ConfigChangeRequest.PENDING).ifPresent(old -> {
					old.setStatus(ConfigChangeRequest.CANCELLED);
					old.setDecidedBy(p.caller());
					old.setDecidedAt(Instant.now());
					old.setNote("Replaced by a newer request");
					requestRepo.saveAndFlush(old);
				});
		ConfigChangeRequest req = new ConfigChangeRequest();
		req.setOrgId(orgId);
		req.setAreaKey(p.area().getAreaKey());
		req.setRecordId(recordId);
		req.setFieldId(field.getId());
		req.setOldValue(p.current().get(key));
		req.setNewValue(newValue);
		req.setRequestedBy(p.caller());
		requestRepo.saveAndFlush(req);
		audit.created("config", "ChangeRequest", req.getId(), Map.of("area", p.area().getAreaKey(), "field", key));
		events.publishEvent(new FieldChangeRequestedEvent(req.getId(), p.area().getAreaKey(), recordId, key, p.caller()));
	}

	/** Stores validated values: in the owning module's tables when it has a store, otherwise as value rows. */
	void persist(String areaKey, UUID recordId, Map<String, Object> values) {
		RecordFieldStore store = store(areaKey);
		if (store != null) {
			store.apply(recordId, values);
			return;
		}
		UUID orgId = currentUser.orgId();
		Map<String, ConfigField> byKey = catalog.fields(areaKey).stream()
				.collect(Collectors.toMap(ConfigField::getFieldKey, Function.identity()));
		for (Map.Entry<String, Object> e : values.entrySet()) {
			ConfigField f = byKey.get(e.getKey());
			ConfigFieldValueId id = new ConfigFieldValueId(orgId, areaKey, recordId, f.getId());
			ConfigFieldValue row = valueRepo.findById(id).orElseGet(() -> new ConfigFieldValue(orgId, areaKey, recordId, f.getId()));
			row.setValue(e.getValue());
			row.setUpdatedAt(Instant.now());
			row.setUpdatedBy(currentUser.employeeId());
			valueRepo.save(row);
		}
		valueRepo.flush();
	}

	@Override
	@Transactional(readOnly = true)
	public List<ErrorItem> validateDraft(String areaKey, UUID ownerEmployeeId, Map<String, Object> values) {
		Prepared p = prepare(areaKey, null, ownerEmployeeId, values, true);
		List<ErrorItem> out = new ArrayList<>(p.errors());
		p.forbidden().forEach(k -> out.add(ErrorItem.field(k, "FORBIDDEN", "You cannot edit " + p.defs().get(k).label())));
		return out;
	}

	@Override
	@Transactional(readOnly = true)
	public List<String> missingRequired(String areaKey, UUID ownerEmployeeId, Map<String, Object> values) {
		Prepared p = prepare(areaKey, null, ownerEmployeeId, values, true);
		return p.errors().stream().filter(e -> "REQUIRED".equals(e.code())).map(e -> p.defs().get(e.fieldKey()).label())
				.toList();
	}

	@Override
	@Transactional
	public void deleteValues(String areaKey, UUID recordId) {
		UUID orgId = currentUser.orgId();
		valueRepo.deleteByOrgIdAndAreaKeyAndRecordId(orgId, areaKey, recordId);
		requestRepo.deleteByOrgIdAndAreaKeyAndRecordId(orgId, areaKey, recordId);
		ownerRepo.deleteById(new ConfigRecordOwnerId(orgId, areaKey, recordId));
	}

	// ---------------------------------------------------------------- helpers

	/** Current value of every field of the record, by key. */
	Map<String, Object> currentValues(String areaKey, UUID recordId) {
		RecordFieldStore store = store(areaKey);
		if (store != null) {
			return new HashMap<>(store.read(recordId));
		}
		Map<UUID, String> keyById = catalog.fields(areaKey).stream()
				.collect(Collectors.toMap(ConfigField::getId, ConfigField::getFieldKey));
		Map<String, Object> out = new HashMap<>();
		for (ConfigFieldValue v : valueRepo.findByOrgIdAndAreaKeyAndRecordId(currentUser.orgId(), areaKey, recordId)) {
			String key = keyById.get(v.getFieldId());
			if (key != null) {
				out.put(key, v.getValue());
			}
		}
		return out;
	}

	private RecordPolicy policy(String areaKey) {
		return policies.orderedStream().filter(p -> p.supports(areaKey)).findFirst().orElse(null);
	}

	RecordFieldStore store(String areaKey) {
		return stores.orderedStream().filter(s -> areaKey.equals(s.areaKey())).findFirst().orElse(null);
	}

	public String recordLabel(String areaKey, UUID recordId) {
		RecordPolicy policy = policy(areaKey);
		return policy != null ? policy.label(areaKey, recordId) : recordId.toString();
	}

	/** {@code ••••} plus the last four letters or digits. */
	static String mask(Object value) {
		if (value == null) {
			return "";
		}
		String alnum = String.valueOf(value).replaceAll("[^A-Za-z0-9]", "");
		return alnum.length() <= 4 ? "••••" : "••••" + alnum.substring(alnum.length() - 4);
	}

	/** A comparable form of a value, so 30 equals 30.0 and a UUID equals its string. */
	static String canon(Object v) {
		if (v == null) {
			return "";
		}
		if (v instanceof BigDecimal bd) {
			return bd.stripTrailingZeros().toPlainString();
		}
		if (v instanceof Number n) {
			return new BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
		}
		if (v instanceof Collection<?> c) {
			return c.stream().map(FormService::canon).collect(Collectors.joining("|", "[", "]"));
		}
		return String.valueOf(v);
	}

	/** Batch-loads the names shown next to department, location and employee ids. */
	private static final class Lookups {

		final Set<UUID> departments = new HashSet<>();
		final Set<UUID> locations = new HashSet<>();
		final Set<UUID> employeeIds = new HashSet<>();
		Map<UUID, String> departmentNames = Map.of();
		Map<UUID, String> locationNames = Map.of();
		Map<UUID, String> employeeNames = Map.of();

		void collect(FieldDefinition def, Object value) {
			UUID id = uuid(value);
			if (id == null) {
				return;
			}
			switch (def.type()) {
				case DEPARTMENT -> departments.add(id);
				case LOCATION -> locations.add(id);
				case EMPLOYEE -> employeeIds.add(id);
				default -> { }
			}
		}

		void load(OrgDirectory org, EmployeeLookup employees) {
			departmentNames = org.departmentNames(departments);
			locationNames = org.locationNames(locations);
			employeeNames = employees == null ? Map.of() : employees.names(employeeIds);
		}

		/** Null when the referenced department, location or employee exists. */
		String missing(FieldDefinition def, Object value, OrgDirectory org, EmployeeLookup employees) {
			UUID id = uuid(value);
			if (id == null) {
				return null;
			}
			boolean found = switch (def.type()) {
				case DEPARTMENT -> org.departmentNames(List.of(id)).containsKey(id);
				case LOCATION -> org.activeLocationNames(List.of(id)).containsKey(id);
				case EMPLOYEE -> employees == null || employees.names(List.of(id)).containsKey(id);
				default -> true;
			};
			return found ? null : def.label() + (def.type() == FieldType.LOCATION ? " does not exist or is not active" : " does not exist");
		}

		String employeeName(UUID id) {
			return employeeNames.getOrDefault(id, "Someone");
		}

		String display(FieldDefinition def, Object value) {
			if (value == null) {
				return "";
			}
			return switch (def.type()) {
				case BOOLEAN -> Boolean.TRUE.equals(value) ? "Yes" : "No";
				case FILE -> value instanceof Map<?, ?> m && m.get("name") != null ? m.get("name").toString() : "";
				case DEPARTMENT -> departmentNames.getOrDefault(uuid(value), "");
				case LOCATION -> locationNames.getOrDefault(uuid(value), "");
				case EMPLOYEE -> employeeNames.getOrDefault(uuid(value), "");
				case MULTISELECT -> value instanceof Collection<?> c
						? c.stream().map(String::valueOf).collect(Collectors.joining(", ")) : String.valueOf(value);
				default -> String.valueOf(value);
			};
		}

		static UUID uuid(Object value) {
			if (value instanceof UUID u) {
				return u;
			}
			try {
				return value == null ? null : UUID.fromString(value.toString());
			}
			catch (IllegalArgumentException ex) {
				return null;
			}
		}
	}
}
