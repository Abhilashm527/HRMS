package com.ivoyant.hrms.config.service;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.authz.AuthzService;
import com.ivoyant.hrms.common.authz.FgaOutbox;
import com.ivoyant.hrms.common.authz.FgaTuples;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.ConfirmRequiredException;
import com.ivoyant.hrms.common.web.CodedConflictException;
import com.ivoyant.hrms.common.web.ConflictException;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.ForbiddenException;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.common.web.StaleException;
import com.ivoyant.hrms.common.web.UnprocessableException;
import com.ivoyant.hrms.config.FieldAccess;
import com.ivoyant.hrms.config.FieldType;
import com.ivoyant.hrms.config.FieldValues;
import com.ivoyant.hrms.config.RecordFieldStore;
import com.ivoyant.hrms.config.VisibilityRule;
import com.ivoyant.hrms.config.domain.AreaOwnerRole;
import com.ivoyant.hrms.config.domain.ConfigArea;
import com.ivoyant.hrms.config.domain.ConfigChangeRequest;
import com.ivoyant.hrms.config.domain.ConfigField;
import com.ivoyant.hrms.config.domain.ConfigFieldAccess;
import com.ivoyant.hrms.config.domain.ConfigSection;
import com.ivoyant.hrms.config.dto.AccessCell;
import com.ivoyant.hrms.config.dto.AccessEntry;
import com.ivoyant.hrms.config.dto.AccessMatrixPatch;
import com.ivoyant.hrms.config.dto.AccessMatrixResponse;
import com.ivoyant.hrms.config.dto.AreaDetail;
import com.ivoyant.hrms.config.dto.AreaOwnersResponse;
import com.ivoyant.hrms.config.dto.AreaSummary;
import com.ivoyant.hrms.config.dto.CreateFieldRequest;
import com.ivoyant.hrms.config.dto.CreateSectionRequest;
import com.ivoyant.hrms.config.dto.FieldDetail;
import com.ivoyant.hrms.config.dto.FieldUsage;
import com.ivoyant.hrms.config.dto.MetaResponse;
import com.ivoyant.hrms.config.dto.OwnerRef;
import com.ivoyant.hrms.config.dto.ReorderRequest;
import com.ivoyant.hrms.config.dto.SectionDetail;
import com.ivoyant.hrms.config.repo.AreaOwnerRoleRepository;
import com.ivoyant.hrms.config.repo.ConfigChangeRequestRepository;
import com.ivoyant.hrms.config.repo.ConfigFieldAccessRepository;
import com.ivoyant.hrms.config.repo.ConfigFieldRepository;
import com.ivoyant.hrms.config.repo.ConfigFieldValueRepository;
import com.ivoyant.hrms.config.repo.ConfigSectionRepository;
import com.ivoyant.hrms.iam.IamDirectory;
import com.ivoyant.hrms.iam.PermLevel;
import com.ivoyant.hrms.iam.RoleRef;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Settings → Fields &amp; forms: areas, sections, fields, role access, owners. */
@Service
public class ConfigService {

	private static final String BUILT_IN_FIELD = "This is a built-in field — screens and reports that use it will show blanks.";
	private static final String VALUES_KEPT = "Values already entered are kept but no longer shown.";
	private static final String VALUES_MAY_NOT_FIT = "Values already entered may not fit the new type.";
	private static final String NO_VALUES = "No values entered yet.";

	private final AreaCatalog catalog;
	private final ConfigSectionRepository sectionRepo;
	private final ConfigFieldRepository fieldRepo;
	private final ConfigFieldAccessRepository accessRepo;
	private final ConfigFieldValueRepository valueRepo;
	private final ConfigChangeRequestRepository requestRepo;
	private final AreaOwnerRoleRepository ownerRoleRepo;
	private final FieldAccessResolver resolver;
	private final FormService forms;
	private final IamDirectory iam;
	private final AuthzService authz;
	private final FgaOutbox fga;
	private final CurrentUser currentUser;
	private final Audit audit;
	private final LayoutVersions layoutVersions;

	public ConfigService(AreaCatalog catalog, ConfigSectionRepository sectionRepo, ConfigFieldRepository fieldRepo,
			ConfigFieldAccessRepository accessRepo, ConfigFieldValueRepository valueRepo,
			ConfigChangeRequestRepository requestRepo, AreaOwnerRoleRepository ownerRoleRepo,
			FieldAccessResolver resolver, FormService forms, IamDirectory iam, AuthzService authz, FgaOutbox fga,
			CurrentUser currentUser, Audit audit, LayoutVersions layoutVersions) {
		this.layoutVersions = layoutVersions;
		this.catalog = catalog;
		this.sectionRepo = sectionRepo;
		this.fieldRepo = fieldRepo;
		this.accessRepo = accessRepo;
		this.valueRepo = valueRepo;
		this.requestRepo = requestRepo;
		this.ownerRoleRepo = ownerRoleRepo;
		this.resolver = resolver;
		this.forms = forms;
		this.iam = iam;
		this.authz = authz;
		this.fga = fga;
		this.currentUser = currentUser;
		this.audit = audit;
	}

	// ================================================================== areas

	/** The areas the caller may configure (the sidebar). */
	@Transactional(readOnly = true)
	public List<AreaSummary> areas() {
		UUID orgId = currentUser.orgId();
		Map<String, List<AreaOwnerRole>> owners = ownerRoleRepo.findByOrgId(orgId).stream()
				.collect(Collectors.groupingBy(AreaOwnerRole::getAreaKey));
		Map<UUID, RoleRef> roles = iam.roles().stream().collect(Collectors.toMap(RoleRef::id, Function.identity()));
		Map<UUID, ConfigSection> sections = sectionRepo.findByOrgId(orgId).stream()
				.collect(Collectors.toMap(ConfigSection::getId, Function.identity()));
		List<ConfigField> allFields = fieldRepo.findByOrgId(orgId);
		List<AreaSummary> out = new ArrayList<>();
		for (ConfigArea a : catalog.areaList()) {
			if (!canConfigure(a.getAreaKey())) {
				continue;
			}
			long sectionCount = sections.values().stream()
					.filter(s -> s.getAreaKey().equals(a.getAreaKey()) && !s.isLocked()).count();
			long fieldCount = allFields.stream().filter(f -> f.getAreaKey().equals(a.getAreaKey()) && f.isActive()
					&& sections.get(f.getSectionId()) != null && !sections.get(f.getSectionId()).isLocked()).count();
			out.add(new AreaSummary(a.getAreaKey(), a.getName(), a.getDescription(), a.getIcon(), a.getModule(),
					a.getSort(), (int) sectionCount, (int) fieldCount, ownerNames(owners.get(a.getAreaKey()), roles)));
		}
		return out;
	}

	@Transactional(readOnly = true)
	public AreaDetail area(String areaKey) {
		ConfigArea area = requireConfigure(areaKey);
		return detail(area);
	}

	private AreaDetail detail(ConfigArea area) {
		String areaKey = area.getAreaKey();
		UUID orgId = currentUser.orgId();
		List<ConfigSection> sections = catalog.sections(areaKey).stream().filter(s -> !s.isLocked()).toList();
		List<ConfigField> fields = catalog.fields(areaKey);
		AccessContext ctx = accessContext(area, fields);
		Map<String, List<AreaOwnerRole>> owners = Map.of(areaKey, ownerRoleRepo.findByOrgIdAndAreaKey(orgId, areaKey));
		Map<UUID, RoleRef> roles = iam.roles().stream().collect(Collectors.toMap(RoleRef::id, Function.identity()));

		List<SectionDetail> out = new ArrayList<>();
		for (ConfigSection s : sections) {
			List<FieldDetail> list = fields.stream().filter(f -> f.getSectionId().equals(s.getId()))
					.map(f -> fieldDetail(area, f, ctx)).toList();
			out.add(sectionDetail(s, list));
		}
		List<OwnerRef> ownerRefs = new ArrayList<>();
		for (AreaOwnerRole o : owners.get(areaKey)) {
			RoleRef r = roles.get(o.getRoleId());
			if (r != null) {
				ownerRefs.add(new OwnerRef(r.id(), r.name()));
			}
		}
		return new AreaDetail(areaKey, area.getName(), area.getDescription(), area.getModule(),
				orderedTypes(catalog.allowedTypes(areaKey)), ownerRefs, out);
	}

	// ================================================================== sections

	@Transactional
	public SectionDetail createSection(String areaKey, CreateSectionRequest request) {
		ConfigArea area = requireConfigure(areaKey);
		String name = request.name() == null ? "" : request.name().trim();
		if (name.isEmpty()) {
			throw new UnprocessableException(List.of(ErrorItem.field("name", "REQUIRED", "Every section needs a name")));
		}
		UUID orgId = currentUser.orgId();
		String base = request.key() != null && !request.key().isBlank() ? slug(request.key()) : slug(name);
		String key = uniqueKey(base, k -> sectionRepo.existsByOrgIdAndAreaKeyAndSectionKey(orgId, areaKey, k));
		ConfigSection s = new ConfigSection();
		s.setOrgId(orgId);
		s.setAreaKey(areaKey);
		s.setSectionKey(key);
		s.setName(name);
		s.setDescription(request.description());
		s.setSort(catalog.sections(areaKey).stream().mapToInt(ConfigSection::getSort).max().orElse(0) + 10);
		sectionRepo.saveAndFlush(s);
		SectionDetail out = sectionDetail(s, List.of());
		layoutVersions.bump(s.getAreaKey());
		audit.created("config", "ConfigSection", s.getId(), out);
		return out;
	}

	@Transactional
	public SectionDetail updateSection(UUID id, Map<String, Object> patch, String ifMatch) {
		ConfigSection s = findSection(id);
		requireConfigure(s.getAreaKey());
		requireEditable(s);
		checkVersion(s.getVersion(), ifMatch);
		SectionDetail before = sectionDetail(s, List.of());
		if (patch.containsKey("name")) {
			String name = patch.get("name") == null ? "" : patch.get("name").toString().trim();
			if (name.isEmpty()) {
				throw new UnprocessableException(List.of(ErrorItem.field("name", "REQUIRED", "Every section needs a name")));
			}
			s.setName(name);
		}
		if (patch.containsKey("sort") && patch.get("sort") instanceof Number n) {
			s.setSort(n.intValue());
		}
		if (patch.containsKey("active") && patch.get("active") instanceof Boolean b) {
			s.setActive(b);
		}
		if (patch.containsKey("description")) {
			s.setDescription(patch.get("description") == null ? null : patch.get("description").toString());
		}
		if (patch.containsKey("showInPortal") && patch.get("showInPortal") instanceof Boolean b) {
			s.setShowInPortal(b);
		}
		sectionRepo.saveAndFlush(s);
		SectionDetail after = sectionDetail(s, List.of());
		layoutVersions.bump(s.getAreaKey());
		audit.updated("config", "ConfigSection", id, before, after);
		return after;
	}

	@Transactional
	public void deleteSection(UUID id, boolean force) {
		ConfigSection s = findSection(id);
		requireConfigure(s.getAreaKey());
		if (s.isLocked() || s.isSystem()) {
			throw new ConflictException("Built-in sections cannot be deleted");
		}
		List<ConfigField> inside = fieldRepo.findByOrgIdAndSectionId(s.getOrgId(), id);
		if (!inside.isEmpty() && !force) {
			throw new ConfirmRequiredException(inside.size() + (inside.size() == 1 ? " field goes" : " fields go")
					+ " with it. Values already entered are removed too.",
					inside.stream().map(f -> "field '" + f.getLabel() + "'").toList());
		}
		SectionDetail before = sectionDetail(s, List.of());
		for (ConfigField f : inside) {
			unlinkConditions(f);
			f.setActive(false);
			f.setDeletedAt(java.time.Instant.now());
			fieldRepo.save(f);
		}
		fieldRepo.flush();
		s.setActive(false);
		s.setDeletedAt(java.time.Instant.now());
		sectionRepo.saveAndFlush(s);
		layoutVersions.bump(s.getAreaKey());
		audit.deleted("config", "ConfigSection", id, before);
	}

	// ================================================================== fields

	@Transactional
	public FieldDetail createField(String areaKey, CreateFieldRequest r) {
		ConfigArea area = requireConfigure(areaKey);
		UUID orgId = currentUser.orgId();
		ConfigSection section = findSection(r.sectionId());
		if (!section.getAreaKey().equals(areaKey) || section.isLocked()) {
			throw new ConflictException("Fields cannot be added to that section");
		}
		if (!catalog.allowedTypes(areaKey).contains(r.type())) {
			throw new CodedConflictException("TYPE_NOT_ALLOWED", label(r.type()) + " fields are not available in " + area.getName());
		}
		String label = r.label() == null ? "" : r.label().trim();
		if (label.isEmpty()) {
			throw new UnprocessableException(List.of(ErrorItem.field("label", "REQUIRED", "Every field needs a label")));
		}
		String prefix = AreaCatalog.ONBOARDING.equals(areaKey) ? "c_" : "";
		String base = r.key() != null && !r.key().isBlank() ? slug(r.key()) : prefix + slug(label);
		String key = uniqueKey(base, k -> fieldRepo.existsByOrgIdAndAreaKeyAndFieldKey(orgId, areaKey, k));

		ConfigField f = new ConfigField();
		f.setOrgId(orgId);
		f.setAreaKey(areaKey);
		f.setSectionId(section.getId());
		f.setFieldKey(key);
		f.setLabel(label);
		f.setFieldType(r.type());
		f.setRequired(Boolean.TRUE.equals(r.required()) && r.type().hasValue());
		f.setHelpText(r.helpText());
		f.setMaskedDefault(Boolean.TRUE.equals(r.maskedDefault()));
		List<String> options = FieldValues.parseOptions(r.options());
		requireOptions(r.type(), options);
		f.setOptions(options);
		f.setVisibilityRule(validateRule(areaKey, key, r.visibilityRule(), null));
		f.setDefaultValue(normalizeDefault(f, r.defaultValue()));
		f.setSort(fieldRepo.findByOrgIdAndSectionId(orgId, section.getId()).stream()
				.mapToInt(ConfigField::getSort).max().orElse(0) + 10);
		fieldRepo.saveAndFlush(f);
		FieldDetail out = fieldDetail(area, f, accessContext(area, List.of(f)));
		layoutVersions.bump(f.getAreaKey());
		audit.created("config", "ConfigField", f.getId(), out);
		return out;
	}

	@Transactional
	public FieldDetail duplicateField(UUID id) {
		ConfigField src = findField(id);
		ConfigArea area = requireConfigure(src.getAreaKey());
		requireEditable(sectionOf(src));
		UUID orgId = currentUser.orgId();
		String key = uniqueKey(src.getFieldKey() + "_copy",
				k -> fieldRepo.existsByOrgIdAndAreaKeyAndFieldKey(orgId, src.getAreaKey(), k));
		ConfigField f = new ConfigField();
		f.setOrgId(orgId);
		f.setAreaKey(src.getAreaKey());
		f.setSectionId(src.getSectionId());
		f.setFieldKey(key);
		f.setLabel(src.getLabel() + " copy");
		f.setFieldType(src.getFieldType());
		f.setRequired(src.isRequired());
		f.setDefaultValue(src.getDefaultValue());
		f.setHelpText(src.getHelpText());
		f.setOptions(src.getOptions());
		f.setVisibilityRule(src.getVisibilityRule());
		f.setMaskedDefault(src.isMaskedDefault());
		f.setSort(fieldRepo.findByOrgIdAndSectionId(orgId, src.getSectionId()).stream()
				.mapToInt(ConfigField::getSort).max().orElse(0) + 10);
		fieldRepo.saveAndFlush(f);
		for (ConfigFieldAccess a : accessRepo.findByFieldId(src.getId())) {
			accessRepo.save(new ConfigFieldAccess(f.getId(), a.getRoleId(), a.getAccess(), a.getOwnAccess(), a.getOthersAccess()));
		}
		accessRepo.flush();
		FieldDetail out = fieldDetail(area, f, accessContext(area, List.of(f)));
		layoutVersions.bump(f.getAreaKey());
		audit.created("config", "ConfigField", f.getId(), out);
		return out;
	}

	@Transactional
	public FieldDetail updateField(UUID id, Map<String, Object> patch, boolean force, String ifMatch) {
		ConfigField f = findField(id);
		ConfigArea area = requireConfigure(f.getAreaKey());
		ConfigSection section = sectionOf(f);
		requireEditable(section);
		checkVersion(f.getVersion(), ifMatch);
		FieldDetail before = fieldDetail(area, f, accessContext(area, List.of(f)));

		FieldType newType = patch.containsKey("type") && patch.get("type") != null
				? FieldType.valueOf(patch.get("type").toString().toUpperCase(Locale.ROOT)) : f.getFieldType();
		boolean typeChange = newType != f.getFieldType();
		boolean requiredChange = patch.get("required") instanceof Boolean b && b != f.isRequired();
		boolean deactivate = Boolean.FALSE.equals(patch.get("active")) && f.isActive();
		if (typeChange && !catalog.allowedTypes(f.getAreaKey()).contains(newType)) {
			throw new CodedConflictException("TYPE_NOT_ALLOWED", label(newType) + " fields are not available in " + area.getName());
		}
		boolean hasData = typeChange && hasData(f);
		List<String> breaks = new ArrayList<>();
		if ((typeChange && (f.isSystem() || hasData)) || (requiredChange && f.isSystem()) || (deactivate && f.isSystem())) {
			if (f.isSystem()) {
				breaks.add(BUILT_IN_FIELD);
			}
			if (typeChange) {
				breaks.add(hasData ? VALUES_MAY_NOT_FIT : NO_VALUES);
			}
			if (deactivate) {
				breaks.add(hasData(f) ? VALUES_KEPT : NO_VALUES);
			}
			if (!force) {
				throw new ConfirmRequiredException("Are you sure? This changes how '" + f.getLabel() + "' works.", breaks);
			}
		}

		if (patch.containsKey("label")) {
			String label = patch.get("label") == null ? "" : patch.get("label").toString().trim();
			if (label.isEmpty()) {
				throw new UnprocessableException(List.of(ErrorItem.field("label", "REQUIRED", "Every field needs a label")));
			}
			f.setLabel(label);
		}
		if (typeChange) {
			f.setFieldType(newType);
			if (!newType.hasValue()) {
				f.setRequired(false);
			}
		}
		if (patch.get("required") instanceof Boolean b && f.getFieldType().hasValue()) {
			f.setRequired(b);
		}
		if (patch.containsKey("helpText")) {
			f.setHelpText(patch.get("helpText") == null ? null : patch.get("helpText").toString());
		}
		if (patch.containsKey("options") || typeChange) {
			List<String> options = patch.containsKey("options") ? FieldValues.parseOptions(patch.get("options"))
					: FieldValues.parseOptions(f.getOptions());
			requireOptions(f.getFieldType(), options);
			f.setOptions(options);
		}
		if (patch.containsKey("visibilityRule")) {
			f.setVisibilityRule(validateRule(f.getAreaKey(), f.getFieldKey(), patch.get("visibilityRule"), f.getId()));
		}
		if (patch.containsKey("defaultValue") || typeChange) {
			f.setDefaultValue(normalizeDefault(f, patch.containsKey("defaultValue") ? patch.get("defaultValue")
					: f.getDefaultValue()));
		}
		if (patch.get("maskedDefault") instanceof Boolean b) {
			f.setMaskedDefault(b);
		}
		if (patch.get("sort") instanceof Number n) {
			f.setSort(n.intValue());
		}
		if (patch.containsKey("sectionId") && patch.get("sectionId") != null) {
			UUID target = UUID.fromString(patch.get("sectionId").toString());
			if (!target.equals(f.getSectionId())) {
				ConfigSection t = findSection(target);
				if (!t.getAreaKey().equals(f.getAreaKey()) || t.isLocked()) {
					throw new ConflictException("The field cannot move to that section");
				}
				f.setSectionId(target);
				f.setSort(fieldRepo.findByOrgIdAndSectionId(f.getOrgId(), target).stream()
						.mapToInt(ConfigField::getSort).max().orElse(0) + 10);
			}
		}
		if (patch.get("active") instanceof Boolean b) {
			f.setActive(b);
			if (!b) {
				unlinkConditions(f);
			}
		}
		fieldRepo.saveAndFlush(f);
		FieldDetail after = fieldDetail(area, f, accessContext(area, List.of(f)));
		layoutVersions.bump(f.getAreaKey());
		audit.updated("config", "ConfigField", id, before, after);
		return after;
	}

	/**
	 * Removes a custom field. A system field, or a field that already has values, is only hidden, and only with
	 * {@code force}: built-in screens depend on the first, and the values of the second are kept.
	 */
	@Transactional
	public void deleteField(UUID id, boolean force) {
		ConfigField f = findField(id);
		ConfigArea area = requireConfigure(f.getAreaKey());
		requireEditable(sectionOf(f));
		FieldDetail before = fieldDetail(area, f, accessContext(area, List.of(f)));
		boolean hasData = hasData(f);
		if (f.isSystem() || hasData) {
			if (!force) {
				List<String> breaks = new ArrayList<>();
				if (f.isSystem()) {
					breaks.add(BUILT_IN_FIELD);
				}
				breaks.add(hasData ? VALUES_KEPT : NO_VALUES);
				throw new ConfirmRequiredException("'" + f.getLabel() + "' will be hidden, not deleted.", breaks);
			}
			f.setActive(false);
			unlinkConditions(f);
			fieldRepo.saveAndFlush(f);
		layoutVersions.bump(f.getAreaKey());
			audit.publish("config", "ConfigField", id, "DEACTIVATE", before, null);
			return;
		}
		unlinkConditions(f);
		f.setActive(false);
		f.setDeletedAt(java.time.Instant.now());
		fieldRepo.saveAndFlush(f);
		layoutVersions.bump(f.getAreaKey());
		audit.deleted("config", "ConfigField", id, before);
	}

	/** Brings back a deleted field (inactive, so it can be reviewed before it shows again). */
	@Transactional
	public FieldDetail restoreField(UUID id) {
		ConfigField f = findField(id);
		ConfigArea area = requireConfigure(f.getAreaKey());
		if (f.getDeletedAt() == null) {
			return fieldDetail(area, f, accessContext(area, List.of(f)));
		}
		ConfigSection sec = sectionOf(f);
		if (sec == null || sec.getDeletedAt() != null) {
			throw new CodedConflictException("SECTION_DELETED", "Restore the section first");
		}
		f.setDeletedAt(null);
		fieldRepo.saveAndFlush(f);
		layoutVersions.bump(f.getAreaKey());
		audit.publish("config", "ConfigField", id, "RESTORE", null, null);
		return fieldDetail(area, f, accessContext(area, List.of(f)));
	}

	/** Brings back a deleted section (inactive); its fields come back with it. */
	@Transactional
	public void restoreSection(UUID id) {
		ConfigSection s = findSection(id);
		requireConfigure(s.getAreaKey());
		if (s.getDeletedAt() == null) {
			return;
		}
		s.setDeletedAt(null);
		sectionRepo.saveAndFlush(s);
		for (ConfigField f : fieldRepo.findByOrgIdAndSectionId(s.getOrgId(), id)) {
			if (f.getDeletedAt() != null) {
				f.setDeletedAt(null);
				fieldRepo.save(f);
			}
		}
		fieldRepo.flush();
		layoutVersions.bump(s.getAreaKey());
		audit.publish("config", "ConfigSection", id, "RESTORE", null, null);
	}

	@Transactional(readOnly = true)
	public FieldUsage usage(UUID id) {
		ConfigField f = findField(id);
		requireConfigure(f.getAreaKey());
		long values = valueCount(f);
		long pending = requestRepo.countByFieldIdAndStatus(id, ConfigChangeRequest.PENDING);
		List<String> used = fieldRepo.findByOrgIdAndAreaKeyOrderBySortAscLabelAsc(f.getOrgId(), f.getAreaKey()).stream()
				.filter(o -> {
					VisibilityRule r = VisibilityRule.fromJson(o.getVisibilityRule());
					return r != null && r.fieldKey().equals(f.getFieldKey());
				}).map(ConfigField::getFieldKey).toList();
		return new FieldUsage(values, pending, used, f.isSystem());
	}

	@Transactional
	public AreaDetail reorder(String areaKey, ReorderRequest request) {
		ConfigArea area = requireConfigure(areaKey);
		Map<UUID, ConfigSection> areaSections = catalog.sections(areaKey).stream()
				.collect(Collectors.toMap(ConfigSection::getId, Function.identity()));
		Map<UUID, ConfigField> areaFields = catalog.fields(areaKey).stream()
				.collect(Collectors.toMap(ConfigField::getId, Function.identity()));
		if (request.sections() != null) {
			int sort = 10;
			for (UUID id : request.sections()) {
				ConfigSection s = areaSections.get(id);
				if (s == null) {
					throw new NotFoundException("Section " + id + " is not in area '" + areaKey + "'");
				}
				s.setSort(sort);
				sort += 10;
			}
		}
		if (request.fields() != null) {
			for (Map.Entry<UUID, List<UUID>> entry : request.fields().entrySet()) {
				if (!areaSections.containsKey(entry.getKey())) {
					throw new NotFoundException("Section " + entry.getKey() + " is not in area '" + areaKey + "'");
				}
				int sort = 10;
				for (UUID fieldId : entry.getValue()) {
					ConfigField f = areaFields.get(fieldId);
					if (f == null) {
						throw new NotFoundException("Field " + fieldId + " is not in area '" + areaKey + "'");
					}
					f.setSectionId(entry.getKey());
					f.setSort(sort);
					sort += 10;
				}
			}
		}
		sectionRepo.flush();
		fieldRepo.flush();
		layoutVersions.bump(areaKey);
		audit.publish("config", "ConfigArea", areaKey, "REORDER", null, request);
		return detail(area);
	}

	// ================================================================== role access

	/** Replaces the field's role rules; roles left out go back to their defaults. */
	@Transactional
	public List<AccessCell> replaceAccess(UUID fieldId, List<AccessEntry> entries) {
		ConfigField f = findField(fieldId);
		ConfigArea area = requireConfigure(f.getAreaKey());
		Map<UUID, RoleRef> roles = iam.roles().stream().collect(Collectors.toMap(RoleRef::id, Function.identity()));
		AccessContext before = accessContext(area, List.of(f));
		List<AccessCell> beforeCells = cells(f, before);
		accessRepo.deleteByFieldId(fieldId);
		accessRepo.flush();
		Set<UUID> seen = new HashSet<>();
		for (AccessEntry e : entries) {
			RoleRef role = roles.get(e.roleId());
			if (role == null) {
				throw new NotFoundException("Role " + e.roleId() + " not found");
			}
			if (!seen.add(e.roleId())) {
				throw new ConflictException("Duplicate entry for role " + role.name());
			}
			if (FieldAccessResolver.SUPER_ADMIN.equals(role.key()) || (e.own() == null && e.others() == null)) {
				continue;
			}
			accessRepo.save(new ConfigFieldAccess(fieldId, e.roleId(), null, e.own(), e.others()));
		}
		accessRepo.flush();
		List<AccessCell> after = cells(f, accessContext(area, List.of(f)));
		layoutVersions.bump(f.getAreaKey());
		audit.publish("config", "ConfigFieldAccess", fieldId, "UPDATE", beforeCells, after);
		return after;
	}

	@Transactional(readOnly = true)
	public AccessMatrixResponse matrix(String areaKey, String side) {
		ConfigArea area = requireConfigure(areaKey);
		FieldAccessResolver.Side s = parseSide(side);
		List<ConfigField> fields = catalog.fields(areaKey);
		AccessContext ctx = accessContext(area, fields);
		List<RoleRef> roles = iam.roles();
		List<AccessMatrixResponse.MatrixRow> rows = new ArrayList<>();
		for (ConfigSection sec : catalog.sections(areaKey)) {
			if (sec.isLocked()) {
				continue;
			}
			rows.add(new AccessMatrixResponse.MatrixRow("SECTION", sec.getName(), null, null, null));
			for (ConfigField f : fields) {
				if (!f.getSectionId().equals(sec.getId()) || f.getFieldType() == FieldType.HEADING) {
					continue;
				}
				List<AccessMatrixResponse.MatrixCell> cells = new ArrayList<>();
				for (RoleRef r : roles) {
					FieldAccessResolver.RoleLevel rl = ctx.level(r, f, s);
					cells.add(new AccessMatrixResponse.MatrixCell(r.id(), rl.level(), rl.explicit()));
				}
				rows.add(new AccessMatrixResponse.MatrixRow("FIELD", null, f.getId(), f.getLabel(), cells));
			}
		}
		String text = s == FieldAccessResolver.Side.OWN
				? "What each role can do with each field on their own record."
				: "What each role can do with each field on other people's records.";
		return new AccessMatrixResponse(s.name(), text,
				roles.stream().map(r -> new AccessMatrixResponse.MatrixRole(r.id(), r.key(), r.name())).toList(), rows);
	}

	@Transactional
	public void patchMatrix(String areaKey, AccessMatrixPatch patch) {
		ConfigArea area = requireConfigure(areaKey);
		FieldAccessResolver.Side side = parseSide(patch.side());
		Map<UUID, ConfigField> fields = catalog.fields(areaKey).stream()
				.collect(Collectors.toMap(ConfigField::getId, Function.identity()));
		Map<UUID, RoleRef> roles = iam.roles().stream().collect(Collectors.toMap(RoleRef::id, Function.identity()));
		AccessContext ctx = accessContext(area, List.copyOf(fields.values()));
		List<Map<String, Object>> changes = new ArrayList<>();
		for (AccessMatrixPatch.CellChange c : patch.cells()) {
			ConfigField f = fields.get(c.fieldId());
			RoleRef role = roles.get(c.roleId());
			if (f == null || role == null) {
				throw new NotFoundException("Unknown field or role in the matrix change");
			}
			if (FieldAccessResolver.SUPER_ADMIN.equals(role.key())) {
				continue;
			}
			ConfigFieldAccess rule = ctx.rule(f, role);
			if (rule == null && c.level() == null) {
				continue;
			}
			if (rule == null) {
				// the side that is not being changed stays on its default
				rule = new ConfigFieldAccess(f.getId(), role.id(), null, null, null);
			}
			if (side == FieldAccessResolver.Side.OWN) {
				rule.setOwnAccess(c.level());
			}
			else {
				rule.setOthersAccess(c.level());
			}
			if (rule.getOwnAccess() == null && rule.getOthersAccess() == null) {
				accessRepo.deleteById(new com.ivoyant.hrms.config.domain.ConfigFieldAccessId(f.getId(), role.id()));
			}
			else {
				accessRepo.save(rule);
			}
			Map<String, Object> change = new LinkedHashMap<>();
			change.put("field", f.getFieldKey());
			change.put("role", role.key());
			change.put("side", side.name());
			change.put("level", c.level());
			changes.add(change);
		}
		accessRepo.flush();
		layoutVersions.bump(areaKey);
		audit.publish("config", "ConfigFieldAccess", areaKey, "MATRIX", null, changes);
	}

	// ================================================================== owners and meta

	@Transactional(readOnly = true)
	public AreaOwnersResponse owners() {
		if (areas().isEmpty()) {
			throw new ForbiddenException("You cannot configure any area");
		}
		UUID orgId = currentUser.orgId();
		Map<String, List<UUID>> byArea = ownerRoleRepo.findByOrgId(orgId).stream().collect(
				Collectors.groupingBy(AreaOwnerRole::getAreaKey,
						Collectors.mapping(AreaOwnerRole::getRoleId, Collectors.toList())));
		List<AreaOwnersResponse.Role> roles = iam.roles().stream()
				.filter(r -> !FieldAccessResolver.EMPLOYEE.equals(r.key()))
				.map(r -> new AreaOwnersResponse.Role(r.id(), r.key(), r.name())).toList();
		List<AreaOwnersResponse.Row> rows = catalog.areaList().stream().map(a -> new AreaOwnersResponse.Row(
				a.getAreaKey(), a.getName(), byArea.getOrDefault(a.getAreaKey(), List.of()), true)).toList();
		return new AreaOwnersResponse(roles, rows, authz.isSuperAdmin());
	}

	@Transactional
	public AreaOwnersResponse putOwners(String areaKey, List<UUID> roleIds) {
		if (!authz.isSuperAdmin()) {
			throw new ForbiddenException("Only a Super admin can change area owners");
		}
		catalog.area(areaKey);
		UUID orgId = currentUser.orgId();
		Map<UUID, RoleRef> roles = iam.roles().stream().collect(Collectors.toMap(RoleRef::id, Function.identity()));
		for (UUID id : roleIds) {
			if (!roles.containsKey(id)) {
				throw new NotFoundException("Role " + id + " not found");
			}
		}
		Set<UUID> wanted = new HashSet<>(roleIds);
		List<AreaOwnerRole> existing = ownerRoleRepo.findByOrgIdAndAreaKey(orgId, areaKey);
		for (AreaOwnerRole o : existing) {
			RoleRef r = roles.get(o.getRoleId());
			if (r != null && FieldAccessResolver.SUPER_ADMIN.equals(r.key()) && !wanted.contains(o.getRoleId())) {
				throw new ConflictException("Super admin is always an owner and cannot be removed");
			}
		}
		Set<UUID> before = existing.stream().map(AreaOwnerRole::getRoleId).collect(Collectors.toSet());
		ownerRoleRepo.deleteByOrgIdAndAreaKey(orgId, areaKey);
		ownerRoleRepo.flush();
		for (UUID id : wanted) {
			ownerRoleRepo.save(new AreaOwnerRole(orgId, areaKey, id));
		}
		ownerRoleRepo.flush();
		fga.write(FgaTuples.configAreaOrg(orgId, areaKey));
		for (UUID id : before) {
			if (!wanted.contains(id) && roles.containsKey(id)) {
				fga.delete(FgaTuples.areaOwnerRole(areaKey, roles.get(id).key()));
			}
		}
		for (UUID id : wanted) {
			fga.write(FgaTuples.areaOwnerRole(areaKey, roles.get(id).key()));
		}
		audit.publish("config", "AreaOwners", areaKey, "UPDATE", before, wanted);
		return owners();
	}

	public MetaResponse meta() {
		List<FieldType> exposed = List.of(FieldType.TEXT, FieldType.TEXTAREA, FieldType.NUMBER, FieldType.DATE,
				FieldType.EMAIL, FieldType.PHONE, FieldType.SELECT, FieldType.BOOLEAN, FieldType.FILE,
				FieldType.HEADING, FieldType.DEPARTMENT, FieldType.EMPLOYEE, FieldType.LOCATION,
				FieldType.EMPLOYMENT_TYPE);
		Map<String, List<FieldType>> byArea = new LinkedHashMap<>();
		for (String key : catalog.keys()) {
			byArea.put(key, orderedTypes(catalog.allowedTypes(key)));
		}
		return new MetaResponse(
				exposed.stream().map(t -> new MetaResponse.Option(t.name(), label(t))).toList(), byArea,
				List.of(new MetaResponse.Option("HIDDEN", "Hidden"), new MetaResponse.Option("MASKED", "Masked"),
						new MetaResponse.Option("VIEW", "View"), new MetaResponse.Option("HR_APPROVES", "Edit · approval"),
						new MetaResponse.Option("EDIT", "Edit")),
				List.of(new MetaResponse.Option("FILLED", "has a value"), new MetaResponse.Option("EMPTY", "is empty"),
						new MetaResponse.Option("EQUALS", "equals")));
	}

	// ================================================================== helpers

	/** The sections of an area, for the list service that keeps hire-form steps and sections in step. */
	public List<ConfigSection> sectionsOf(String areaKey) {
		return catalog.sections(areaKey);
	}

	public boolean canConfigure(String areaKey) {
		return authz.can("can_configure", FgaTuples.configArea(ownerAreaOf(areaKey)));
	}

	/** Who may configure an area: the asset areas follow Assets; custom fields on departments, positions and titles follow Onboarding. */
	private static String ownerAreaOf(String areaKey) {
		return switch (areaKey) {
			case "asset_hardware", "asset_software" -> "assets";
			case "department", "position", "title" -> AreaCatalog.ONBOARDING;
			default -> areaKey;
		};
	}

	private ConfigArea requireConfigure(String areaKey) {
		ConfigArea area = catalog.area(areaKey);
		if (!canConfigure(areaKey)) {
			throw new ForbiddenException("You cannot configure the '" + areaKey + "' area");
		}
		return area;
	}

	private void requireEditable(ConfigSection s) {
		if (s != null && s.isLocked()) {
			throw new ConflictException("This part of the form is built in and cannot be changed here");
		}
	}

	private ConfigSection findSection(UUID id) {
		return sectionRepo.findByIdAndOrgId(id, currentUser.orgId())
				.orElseThrow(() -> new NotFoundException("Section " + id + " not found"));
	}

	private ConfigSection sectionOf(ConfigField f) {
		return sectionRepo.findById(f.getSectionId()).orElse(null);
	}

	private ConfigField findField(UUID id) {
		return fieldRepo.findByIdAndOrgId(id, currentUser.orgId())
				.orElseThrow(() -> new NotFoundException("Field " + id + " not found"));
	}

	private static void checkVersion(Long current, String ifMatch) {
		if (ifMatch == null || ifMatch.isBlank()) {
			return;
		}
		String v = ifMatch.replace("W/", "").replace("\"", "").trim();
		if (!v.equals(String.valueOf(current))) {
			throw new StaleException("This was changed by someone else. Reload and try again.");
		}
	}

	private long valueCount(ConfigField f) {
		RecordFieldStore store = forms.store(f.getAreaKey());
		return store != null ? store.countValues(f.getFieldKey()) : valueRepo.countByFieldId(f.getId());
	}

	private boolean hasData(ConfigField f) {
		return valueCount(f) > 0;
	}

	/** A condition pointing at a removed or hidden field is cleared. */
	private void unlinkConditions(ConfigField gone) {
		for (ConfigField o : fieldRepo.findByOrgIdAndAreaKeyOrderBySortAscLabelAsc(gone.getOrgId(), gone.getAreaKey())) {
			VisibilityRule r = VisibilityRule.fromJson(o.getVisibilityRule());
			if (r != null && r.fieldKey().equals(gone.getFieldKey())) {
				o.setVisibilityRule(null);
			}
		}
	}

	private Map<String, Object> validateRule(String areaKey, String ownKey, Object raw, UUID ownId) {
		if (raw == null) {
			return null;
		}
		VisibilityRule rule;
		try {
			rule = VisibilityRule.fromJson(raw);
		}
		catch (IllegalArgumentException ex) {
			throw new UnprocessableException(List.of(ErrorItem.field("visibilityRule", "INVALID", "Unknown condition")));
		}
		if (rule == null) {
			throw new UnprocessableException(List.of(ErrorItem.field("visibilityRule", "INVALID",
					"A condition needs a field and an operator")));
		}
		if (rule.fieldKey().equals(ownKey)) {
			throw new UnprocessableException(List.of(ErrorItem.field("visibilityRule", "INVALID",
					"A field cannot depend on itself")));
		}
		ConfigField other = fieldRepo.findByOrgIdAndAreaKeyOrderBySortAscLabelAsc(currentUser.orgId(), areaKey).stream()
				.filter(f -> f.getFieldKey().equals(rule.fieldKey())).findFirst().orElse(null);
		if (other == null || other.getFieldType() == FieldType.HEADING) {
			throw new UnprocessableException(List.of(ErrorItem.field("visibilityRule", "INVALID",
					"The condition must refer to another field of this area")));
		}
		if (rule.op() == VisibilityRule.Op.EQUALS && (rule.value() == null || rule.value().isBlank())) {
			throw new UnprocessableException(List.of(ErrorItem.field("visibilityRule", "REQUIRED",
					"'equals' needs a value")));
		}
		return rule.toJson();
	}

	private static void requireOptions(FieldType type, List<String> options) {
		if (type == FieldType.SELECT && options.isEmpty()) {
			throw new UnprocessableException(List.of(ErrorItem.field("options", "REQUIRED",
					"A dropdown needs at least one option")));
		}
	}

	/** The default is stored in its normalised form; a default that does not fit the type is rejected. */
	private Object normalizeDefault(ConfigField f, Object raw) {
		if (raw == null || !f.getFieldType().hasValue() || FieldValues.isBlank(raw)) {
			return null;
		}
		try {
			return FieldValues.normalize(AreaCatalog.definition(f, null), raw);
		}
		catch (FieldValues.Invalid ex) {
			throw new UnprocessableException(List.of(ErrorItem.field("defaultValue", ex.code(), ex.getMessage())));
		}
	}

	private static String slug(String s) {
		String slug = s.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
		return slug.isEmpty() ? "field" : slug;
	}

	private static String uniqueKey(String base, java.util.function.Predicate<String> taken) {
		String key = base;
		for (int i = 2; taken.test(key); i++) {
			key = base + "_" + i;
		}
		return key;
	}

	private static FieldAccessResolver.Side parseSide(String side) {
		try {
			return FieldAccessResolver.Side.valueOf(side == null ? "OTHERS" : side.toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex) {
			throw new UnprocessableException(List.of(ErrorItem.field("side", "INVALID", "side must be OWN or OTHERS")));
		}
	}

	private static List<FieldType> orderedTypes(Set<FieldType> types) {
		return EnumSet.copyOf(types).stream().toList();
	}

	private static String label(FieldType t) {
		return switch (t) {
			case TEXT -> "Text";
			case TEXTAREA -> "Long text";
			case NUMBER -> "Number";
			case DATE -> "Date";
			case EMAIL -> "Email";
			case PHONE -> "Phone";
			case SELECT -> "Dropdown";
			case MULTISELECT -> "Multi-select";
			case BOOLEAN -> "Yes / No";
			case FILE -> "File";
			case HEADING -> "Heading";
			case DEPARTMENT -> "Department";
			case EMPLOYEE -> "Employee";
			case LOCATION -> "Location";
			case EMPLOYMENT_TYPE -> "Employment type";
			case CURRENCY -> "Currency";
		};
	}

	private List<String> ownerNames(List<AreaOwnerRole> rows, Map<UUID, RoleRef> roles) {
		List<String> names = new ArrayList<>();
		names.add("Super admin");
		if (rows != null) {
			rows.stream().map(o -> roles.get(o.getRoleId())).filter(Objects::nonNull)
					.filter(r -> !FieldAccessResolver.SUPER_ADMIN.equals(r.key())).map(RoleRef::name).sorted()
					.forEach(names::add);
		}
		return names;
	}

	private SectionDetail sectionDetail(ConfigSection s, List<FieldDetail> fields) {
		return new SectionDetail(s.getId(), s.getSectionKey(), s.getName(), s.getDescription(), s.getSort(), s.isSystem(),
				s.isLocked(), s.isActive(), s.isShowInPortal(), fields.size(), s.getVersion(), fields);
	}

	/** Everything needed to work out role levels for a set of fields, loaded once. */
	private final class AccessContext {

		final List<RoleRef> roles;
		final Map<UUID, PermLevel> perms;
		final Map<UUID, Map<UUID, ConfigFieldAccess>> rules;

		AccessContext(ConfigArea area, List<ConfigField> fields) {
			this.roles = iam.roles();
			this.perms = iam.permissionLevels(area.getPermModule());
			this.rules = resolver.rulesByField(fields);
		}

		ConfigFieldAccess rule(ConfigField f, RoleRef role) {
			return rules.getOrDefault(f.getId(), Map.of()).get(role.id());
		}

		FieldAccessResolver.RoleLevel level(RoleRef role, ConfigField f, FieldAccessResolver.Side side) {
			return resolver.roleLevel(role, f, side, rule(f, role), perms.getOrDefault(role.id(), PermLevel.NONE));
		}
	}

	private AccessContext accessContext(ConfigArea area, List<ConfigField> fields) {
		return new AccessContext(area, fields);
	}

	private List<AccessCell> cells(ConfigField f, AccessContext ctx) {
		List<AccessCell> out = new ArrayList<>();
		for (RoleRef r : ctx.roles) {
			FieldAccessResolver.RoleLevel own = ctx.level(r, f, FieldAccessResolver.Side.OWN);
			FieldAccessResolver.RoleLevel others = ctx.level(r, f, FieldAccessResolver.Side.OTHERS);
			out.add(new AccessCell(r.id(), r.key(), r.name(), own.level(), others.level(), own.explicit() || others.explicit()));
		}
		return out;
	}

	private FieldDetail fieldDetail(ConfigArea area, ConfigField f, AccessContext ctx) {
		VisibilityRule rule = VisibilityRule.fromJson(f.getVisibilityRule());
		return new FieldDetail(f.getId(), f.getSectionId(), f.getFieldKey(), f.getLabel(), f.getFieldType(),
				f.isRequired(), f.getDefaultValue(), f.getHelpText(), FieldValues.parseOptions(f.getOptions()),
				rule == null ? null : rule.toJson(), f.getSort(), f.isSystem(), f.isActive(), f.isMaskedDefault(),
				f.getSource(), hasData(f), f.getVersion(), cells(f, ctx));
	}
}
