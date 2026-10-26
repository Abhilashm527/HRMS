package com.ivoyant.hrms.config.service;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.authz.AuthzService;
import com.ivoyant.hrms.common.authz.FgaTuples;
import com.ivoyant.hrms.common.event.ConfigListsChangedEvent;
import com.ivoyant.hrms.common.forms.TableBackedList;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.BadRequestException;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.ForbiddenException;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.common.web.StaleException;
import com.ivoyant.hrms.common.web.UnprocessableException;
import com.ivoyant.hrms.config.EmployeeLookup;
import com.ivoyant.hrms.config.domain.ConfigList;
import com.ivoyant.hrms.config.domain.ConfigListId;
import com.ivoyant.hrms.config.dto.CreateSectionRequest;
import com.ivoyant.hrms.config.dto.ListSpecResponse;
import com.ivoyant.hrms.config.dto.ListsResponse;
import com.ivoyant.hrms.config.repo.ConfigListRepository;
import com.ivoyant.hrms.org.OrgDirectory;
import com.ivoyant.hrms.org.OrgLists;
import com.ivoyant.hrms.org.OrgSettings;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settings lookup lists (departments, positions, statuses, ID formats, ...). The screens edit a draft and save the
 * whole module at once: rows are validated together (references between lists included), then written, and the
 * module's version moves on. Lists live in their domain tables (org module), in form fields (hire form, custom
 * fields) or in {@code config_list} as plain JSON.
 */
@Service
public class ConfigListService {

	private static final String META = "_meta";
	private static final String CFIELDS_OTHER = "cfields_other";
	private static final String CFIELDS_META = "cfields_meta";
	/** Write order of the org-backed lists: a list comes after the lists it points at. */
	private static final List<String> ORG_ORDER = List.of("designations", "departments", "levels", "emptypes",
			"projects", "teams", "titles");

	private final ConfigListRepository repo;
	private final OrgLists orgLists;
	private final OrgDirectory org;
	private final FieldBackedLists fieldLists;
	private final ConfigService config;
	private final ObjectProvider<EmployeeLookup> employees;
	private final AuthzService authz;
	private final CurrentUser currentUser;
	private final Audit audit;
	private final ApplicationEventPublisher events;
	private final OrgSettings orgSettings;
	private final JdbcClient jdbc;
	/** Lists that live in their own tables, by {@code module:key}. */
	private final Map<String, TableBackedList> tables = new HashMap<>();

	public ConfigListService(ConfigListRepository repo, OrgLists orgLists, OrgDirectory org,
			FieldBackedLists fieldLists, ConfigService config, ObjectProvider<EmployeeLookup> employees,
			AuthzService authz, CurrentUser currentUser, Audit audit, ApplicationEventPublisher events,
			OrgSettings orgSettings, JdbcClient jdbc, List<TableBackedList> tableLists) {
		this.orgSettings = orgSettings;
		this.jdbc = jdbc;
		tableLists.forEach(t -> tables.put(t.module() + ":" + t.key(), t));
		this.repo = repo;
		this.orgLists = orgLists;
		this.org = org;
		this.fieldLists = fieldLists;
		this.config = config;
		this.employees = employees;
		this.authz = authz;
		this.currentUser = currentUser;
		this.audit = audit;
		this.events = events;
	}

	// ================================================================== reading

	public ListSpecResponse spec(String module) {
		requireAccess(module);
		return new ListSpecResponse(module, ListSpecs.module(module).values().stream()
				.map(s -> new ListSpecResponse.Spec(s.key(), s.label(), s.columns())).toList());
	}

	@Transactional(readOnly = true)
	public ListsResponse get(String module) {
		requireAccess(module);
		Map<String, List<Map<String, Object>>> lists = new LinkedHashMap<>();
		for (String key : ListSpecs.module(module).keySet()) {
			lists.put(key, load(module, key));
		}
		return new ListsResponse(module, version(module), lists);
	}

	/** One list, for dropdowns elsewhere in the app: any member may read it. */
	@Transactional(readOnly = true)
	public List<Map<String, Object>> getList(String module, String listKey) {
		knownList(module, listKey);
		return load(module, listKey);
	}

	// ================================================================== saving

	@Transactional
	public ListsResponse save(String module, String ifMatch, Map<String, List<Map<String, Object>>> payload) {
		requireAccess(module);
		if (ifMatch != null && !ifMatch.isBlank()
				&& !ifMatch.replace("W/", "").replace("\"", "").trim().equals(String.valueOf(version(module)))) {
			throw new StaleException("These lists were changed by someone else. Reload and try again.");
		}
		List<ErrorItem> errors = new ArrayList<>();
		for (String key : payload.keySet()) {
			if (!ListSpecs.module(module).containsKey(key)) {
				errors.add(ErrorItem.field(key, "UNKNOWN", "There is no list '" + key + "'"));
			}
		}
		if (!errors.isEmpty()) {
			throw new UnprocessableException(errors);
		}

		Map<String, List<Map<String, Object>>> rows = withIds(module, payload);
		validate(module, rows, errors);
		if (!errors.isEmpty()) {
			throw new UnprocessableException(errors);
		}

		Map<String, List<Map<String, Object>>> before = new LinkedHashMap<>();
		rows.keySet().forEach(k -> before.put(k, load(module, k)));
		write(module, rows);
		bump(module);

		Map<String, Object> diff = new LinkedHashMap<>();
		rows.forEach((k, v) -> diff.put(k, Map.of("before", before.get(k).size(), "after", v.size())));
		audit.publish("config", "ConfigLists:" + module, module, "UPDATE", null, diff);
		events.publishEvent(new ConfigListsChangedEvent(module, List.copyOf(rows.keySet())));
		return get(module);
	}

	private void write(String module, Map<String, List<Map<String, Object>>> rows) {
		boolean onboarding = "onboarding".equals(module);
		List<String> orgKeys = !onboarding ? List.of() : ORG_ORDER.stream().filter(rows::containsKey).toList();
		for (String key : orgKeys) {
			orgLists.upsert(key, rows.get(key));
		}
		for (int i = orgKeys.size() - 1; i >= 0; i--) {
			String key = orgKeys.get(i);
			Set<UUID> keep = new HashSet<>();
			rows.get(key).forEach(r -> keep.add(UUID.fromString(r.get("id").toString())));
			orgLists.deleteMissing(key, keep);
		}
		writeTables(module, rows);
		for (Map.Entry<String, List<Map<String, Object>>> e : rows.entrySet()) {
			String key = e.getKey();
			if (table(module, key) != null || onboarding && orgLists.keys().contains(key)) {
				continue;
			}
			if (("hardware".equals(module) || "software".equals(module)) && "fields".equals(key)) {
				fieldLists.saveAssetFields(module, e.getValue());
				continue;
			}
			if (onboarding && "portal".equals(key)) {
				fieldLists.savePortal(e.getValue());
				continue;
			}
			if (onboarding && "fields".equals(key)) {
				fieldLists.saveHireFields(e.getValue());
			}
			else if (onboarding && "cfields".equals(key)) {
				List<Map<String, Object>> fieldRows = e.getValue().stream()
						.filter(r -> FieldBackedLists.ENTITY_AREAS.containsKey(String.valueOf(r.get("ent")))).toList();
				List<Map<String, Object>> other = e.getValue().stream()
						.filter(r -> !FieldBackedLists.ENTITY_AREAS.containsKey(String.valueOf(r.get("ent")))).toList();
				Map<String, Map<String, Object>> meta = fieldLists.saveCustomFields(fieldRows);
				List<Map<String, Object>> metaRows = new ArrayList<>();
				meta.forEach((id, m) -> {
					Map<String, Object> r = new LinkedHashMap<>(m);
					r.put("id", id);
					metaRows.add(r);
				});
				store(module, CFIELDS_META, metaRows);
				store(module, CFIELDS_OTHER, other);
			}
			else {
				store(module, key, e.getValue());
				if (onboarding && "steps".equals(key)) {
					syncSections(e.getValue());
				}
			}
		}
	}

	/** Table-backed lists: write every list first (the ones others point at come first), then delete what was dropped, in reverse. */
	private void writeTables(String module, Map<String, List<Map<String, Object>>> rows) {
		List<TableBackedList> lists = rows.keySet().stream().map(k -> table(module, k)).filter(java.util.Objects::nonNull)
				.sorted(java.util.Comparator.comparingInt(TableBackedList::order)).toList();
		for (TableBackedList t : lists) {
			t.upsert(rows.get(t.key()));
			if ("onboarding".equals(module) && "steps".equals(t.key())) {
				syncSections(rows.get("steps"));
				// every Fields step owns the form section of the same name
				jdbc.sql("""
						update onboarding_step s set config_section_id = (
						    select c.id from config_section c where c.org_id = s.org_id and c.area_key = 'onboarding' and lower(c.name) = lower(s.name) limit 1)
						where s.kind = 'FIELDS' and s.config_section_id is null""").update();
			}
		}
		for (int i = lists.size() - 1; i >= 0; i--) {
			TableBackedList t = lists.get(i);
			Set<UUID> keep = new HashSet<>();
			rows.get(t.key()).forEach(r -> keep.add(UUID.fromString(r.get("id").toString())));
			t.deleteMissing(keep);
		}
	}

	/** Every active Fields step of the hire form has a section in the onboarding area. */
	private void syncSections(List<Map<String, Object>> steps) {
		Set<String> names = new HashSet<>();
		catalog().forEach(s -> names.add(s.getName().toLowerCase()));
		for (Map<String, Object> step : steps) {
			String name = String.valueOf(step.get("name"));
			if ("Fields".equals(step.get("kind")) && !Boolean.FALSE.equals(step.get("active"))
					&& names.add(name.toLowerCase())) {
				config.createSection(AreaCatalog.ONBOARDING, new CreateSectionRequest(name, null,
						step.get("desc") == null ? null : step.get("desc").toString()));
			}
		}
	}

	private List<com.ivoyant.hrms.config.domain.ConfigSection> catalog() {
		return config.sectionsOf(AreaCatalog.ONBOARDING);
	}

	// ================================================================== loading

	private TableBackedList table(String module, String key) {
		return tables.get(module + ":" + key);
	}

	private List<Map<String, Object>> load(String module, String key) {
		TableBackedList table = table(module, key);
		if (table != null) {
			return table.load();
		}
		if (("hardware".equals(module) || "software".equals(module)) && "fields".equals(key)) {
			return fieldLists.loadAssetFields(module);
		}
		if ("onboarding".equals(module)) {
			if ("portal".equals(key)) {
				return fieldLists.loadPortal();
			}
			if (orgLists.keys().contains(key)) {
				return orgLists.load(key);
			}
			if ("fields".equals(key)) {
				return fieldLists.loadHireFields();
			}
			if ("cfields".equals(key)) {
				Map<String, Map<String, Object>> meta = new HashMap<>();
				stored(module, CFIELDS_META).forEach(r -> meta.put(String.valueOf(r.get("id")), r));
				List<Map<String, Object>> out = new ArrayList<>(fieldLists.loadCustomFields(meta));
				out.addAll(stored(module, CFIELDS_OTHER));
				return out;
			}
		}
		return stored(module, key);
	}

	private List<Map<String, Object>> stored(String module, String key) {
		return repo.findById(new ConfigListId(currentUser.orgId(), module, key))
				.map(ConfigList::getItems).map(ArrayList::new).map(l -> (List<Map<String, Object>>) l)
				.orElseGet(ArrayList::new);
	}

	private void store(String module, String key, List<Map<String, Object>> rows) {
		UUID orgId = currentUser.orgId();
		ConfigList list = repo.findById(new ConfigListId(orgId, module, key)).orElseGet(() -> new ConfigList(orgId, module, key));
		list.setItems(new ArrayList<>(rows));
		list.setUpdatedAt(Instant.now());
		list.setUpdatedBy(currentUser.employeeId());
		repo.saveAndFlush(list);
	}

	private long version(String module) {
		return repo.findById(new ConfigListId(currentUser.orgId(), module, META))
				.map(l -> l.getVersion() == null ? 0L : l.getVersion()).orElse(0L);
	}

	private void bump(String module) {
		UUID orgId = currentUser.orgId();
		ConfigList meta = repo.findById(new ConfigListId(orgId, module, META)).orElseGet(() -> new ConfigList(orgId, module, META));
		meta.setUpdatedAt(Instant.now());
		meta.setUpdatedBy(currentUser.employeeId());
		repo.saveAndFlush(meta);
		if (meta.getVersion() != null && meta.getVersion() == 0L) {
			meta.setUpdatedAt(Instant.now());
			repo.saveAndFlush(meta);
		}
	}

	// ================================================================== validation

	/**
	 * Gives every new row a UUID and rewrites references that used a new row's temporary id. A row whose
	 * {@code id} is not a UUID (or is missing) is new.
	 */
	private Map<String, List<Map<String, Object>>> withIds(String module, Map<String, List<Map<String, Object>>> payload) {
		Map<String, Map<String, String>> remap = new HashMap<>();
		Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
		for (Map.Entry<String, List<Map<String, Object>>> e : payload.entrySet()) {
			List<Map<String, Object>> list = new ArrayList<>();
			Map<String, String> map = remap.computeIfAbsent(e.getKey(), k -> new HashMap<>());
			for (Map<String, Object> raw : e.getValue()) {
				Map<String, Object> row = new LinkedHashMap<>(raw);
				String id = row.get("id") == null ? "" : row.get("id").toString();
				if (!isUuid(id)) {
					String fresh = UUID.randomUUID().toString();
					if (!id.isBlank()) {
						map.put(id, fresh);
					}
					row.put("id", fresh);
				}
				list.add(row);
			}
			out.put(e.getKey(), list);
		}
		for (Map.Entry<String, List<Map<String, Object>>> e : out.entrySet()) {
			ListSpecs.Spec spec = ListSpecs.module(module).get(e.getKey());
			for (ListSpecs.Col col : spec.columns()) {
				if (col.ref() == null || !"id".equals(col.refBy())) {
					continue;
				}
				Map<String, String> map = remap.getOrDefault(col.ref(), Map.of());
				for (Map<String, Object> row : e.getValue()) {
					Object v = row.get(col.key());
					if (v != null && map.containsKey(v.toString())) {
						row.put(col.key(), map.get(v.toString()));
					}
				}
			}
		}
		return out;
	}

	private void validate(String module, Map<String, List<Map<String, Object>>> rows, List<ErrorItem> errors) {
		Map<String, ListSpecs.Spec> specs = ListSpecs.module(module);
		Map<String, List<Map<String, Object>>> known = new HashMap<>();
		for (String key : specs.keySet()) {
			known.put(key, rows.containsKey(key) ? rows.get(key) : load(module, key));
		}
		EmployeeLookup lookup = employees.getIfAvailable();
		for (Map.Entry<String, List<Map<String, Object>>> e : rows.entrySet()) {
			String listKey = e.getKey();
			ListSpecs.Spec spec = specs.get(listKey);
			Map<String, Set<String>> seen = new HashMap<>();
			for (int i = 0; i < e.getValue().size(); i++) {
				Map<String, Object> row = e.getValue().get(i);
				for (ListSpecs.Col col : spec.columns()) {
					Object v = row.get(col.key());
					boolean blank = v == null || v.toString().isBlank();
					if (blank) {
						if (col.required()) {
							errors.add(ErrorItem.cell(listKey, i, col.key(), "name".equals(col.key())
									? "Every row needs a name" : col.label() + " is required"));
						}
						continue;
					}
					String problem = problem(col, v, known, lookup);
					if (problem != null) {
						errors.add(ErrorItem.cell(listKey, i, col.key(), col.label() + " " + problem));
					}
					if (col.unique() && !seen.computeIfAbsent(col.key(), k -> new HashSet<>())
							.add(v.toString().trim().toLowerCase())) {
						errors.add(ErrorItem.cell(listKey, i, col.key(), col.label() + " must be unique"));
					}
				}
				rowRules(module, listKey, i, row, errors);
			}
			cycles(listKey, e.getValue(), errors);
			departmentRules(listKey, e.getValue(), errors);
			TableBackedList table = table(module, listKey);
			if (table != null) {
				table.validate(e.getValue(), known, errors);
			}
		}
		if ("onboarding".equals(module) && rows.containsKey("cfields")) {
			fieldLists.checkCustomFieldLocks(rows.get("cfields"), errors);
		}
		// a whole-list rule checked from two lists (an ownership and its ID format) is reported once
		List<ErrorItem> unique = new ArrayList<>(new java.util.LinkedHashSet<>(errors));
		errors.clear();
		errors.addAll(unique);
	}

	/** A column-level problem, or null. */
	private String problem(ListSpecs.Col col, Object v, Map<String, List<Map<String, Object>>> known, EmployeeLookup lookup) {
		String s = v.toString().trim();
		switch (col.type()) {
			case "toggle":
				return v instanceof Boolean ? null : "must be yes or no";
			case "number": {
				try {
					return new BigDecimal(s).signum() < 0 ? "must be 0 or more" : null;
				}
				catch (NumberFormatException ex) {
					return "must be a number";
				}
			}
			case "color", "icon":
				return col.options().contains(s) ? null : "is not one of the choices";
			case "select":
				if (col.ref() != null) {
					return refProblem(col, s, known, lookup);
				}
				return col.options().isEmpty() || col.options().contains(s) ? null : "must be one of " + col.options();
			default:
				return null;
		}
	}

	private String refProblem(ListSpecs.Col col, String s, Map<String, List<Map<String, Object>>> known, EmployeeLookup lookup) {
		if ("employee".equals(col.ref()) || "location".equals(col.ref())) {
			UUID id;
			try {
				id = UUID.fromString(s);
			}
			catch (IllegalArgumentException ex) {
				return "is not a valid id";
			}
			boolean found = "employee".equals(col.ref())
					? lookup == null || lookup.names(List.of(id)).containsKey(id)
					: org.activeLocationNames(List.of(id)).containsKey(id);
			return found ? null : "refers to something that does not exist";
		}
		String by = col.refBy() == null ? "id" : col.refBy();
		boolean found = known.getOrDefault(col.ref(), List.of()).stream()
				.filter(r -> !Boolean.FALSE.equals(r.get("active")))
				.anyMatch(r -> s.equalsIgnoreCase(String.valueOf(r.get(by))));
		return found ? null : "refers to something that does not exist or is inactive";
	}

	private void rowRules(String module, String listKey, int i, Map<String, Object> row, List<ErrorItem> errors) {
		switch (listKey) {
			case "designations" -> pair(listKey, i, row, "bmin", "bmax", "Band min must not be above band max", errors);
			case "levels" -> pair(listKey, i, row, "min", "max", "Min years must not be above max years", errors);
			case "idformat" -> {
				String token = "software".equals(module) ? "APP" : "TYPE";
				Object fmt = row.get("fmt");
				if (fmt != null && !fmt.toString().isBlank() && !IdFormatter.valid(fmt.toString(), token)) {
					errors.add(ErrorItem.cell(listKey, i, "fmt", "Format needs {" + token + "} and at least one #"));
				}
			}
			default -> { }
		}
	}

	private void pair(String listKey, int i, Map<String, Object> row, String lo, String hi, String message, List<ErrorItem> errors) {
		try {
			Object a = row.get(lo);
			Object b = row.get(hi);
			if (a != null && b != null && !a.toString().isBlank() && !b.toString().isBlank()
					&& new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) > 0) {
				errors.add(ErrorItem.cell(listKey, i, lo, message));
			}
		}
		catch (NumberFormatException ignored) {
			// already reported as "must be a number"
		}
	}

	/** Department parents and position "reports to" must not loop. */
	private void cycles(String listKey, List<Map<String, Object>> rows, List<ErrorItem> errors) {
		String column = switch (listKey) {
			case "departments" -> "parent";
			case "designations" -> "rep";
			default -> null;
		};
		if (column == null) {
			return;
		}
		Map<String, String> parent = new HashMap<>();
		for (Map<String, Object> r : rows) {
			Object p = r.get(column);
			parent.put(r.get("id").toString(), p == null || p.toString().isBlank() ? null : p.toString());
		}
		for (int i = 0; i < rows.size(); i++) {
			String start = rows.get(i).get("id").toString();
			String cursor = parent.get(start);
			for (int depth = 0; cursor != null && depth <= rows.size(); depth++) {
				if (cursor.equals(start)) {
					errors.add(ErrorItem.cell(listKey, i, column, "creates a loop"));
					break;
				}
				cursor = parent.get(cursor);
			}
		}
	}

	/** Department nesting follows the organization settings: on or off, and how deep. */
	private void departmentRules(String listKey, List<Map<String, Object>> rows, List<ErrorItem> errors) {
		if (!"departments".equals(listKey)) {
			return;
		}
		Map<String, String> parent = new HashMap<>();
		for (Map<String, Object> r : rows) {
			Object p = r.get("parent");
			parent.put(r.get("id").toString(), p == null || p.toString().isBlank() ? null : p.toString());
		}
		for (int i = 0; i < rows.size(); i++) {
			String p = parent.get(rows.get(i).get("id").toString());
			if (p == null) {
				continue;
			}
			if (!orgSettings.nestedDepartments()) {
				errors.add(ErrorItem.cell(listKey, i, "parent", "Nested departments are off in Settings → Organization"));
				continue;
			}
			int level = 1;
			for (String cursor = p; cursor != null && level <= rows.size() + 1; cursor = parent.get(cursor)) {
				level++;
			}
			if (level > orgSettings.maxDepartmentDepth()) {
				errors.add(ErrorItem.cell(listKey, i, "parent", "is nested deeper than "
						+ orgSettings.maxDepartmentDepth() + " levels (Settings → Organization)"));
			}
		}
	}

	// ================================================================== helpers

	private void requireAccess(String module) {
		if (!ListSpecs.knownModule(module)) {
			throw new NotFoundException("Unknown settings module '" + module + "'");
		}
		String area = "onboarding".equals(module) ? "onboarding" : "assets";
		if (!authz.can("can_configure", FgaTuples.configArea(area))) {
			throw new ForbiddenException("You cannot change the " + module + " settings");
		}
	}

	private static void knownList(String module, String key) {
		if (!ListSpecs.knownModule(module) || !ListSpecs.module(module).containsKey(key)) {
			throw new NotFoundException("There is no list " + module + "/" + key);
		}
	}

	private static boolean isUuid(String s) {
		try {
			UUID.fromString(s);
			return true;
		}
		catch (IllegalArgumentException ex) {
			return false;
		}
	}

	/** Used by the controller: the id format helper needs no data. */
	public static String preview(String fmt, String type) {
		if (fmt == null || fmt.isBlank()) {
			throw new BadRequestException("fmt is required");
		}
		return IdFormatter.preview(fmt, type);
	}
}
