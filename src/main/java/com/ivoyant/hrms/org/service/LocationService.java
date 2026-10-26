package com.ivoyant.hrms.org.service;

import com.ivoyant.hrms.calendar.CalendarView;
import com.ivoyant.hrms.calendar.HolidayCalendars;
import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.authz.FgaOutbox;
import com.ivoyant.hrms.common.authz.FgaTuples;
import com.ivoyant.hrms.common.event.LocationActivationEvent;
import com.ivoyant.hrms.common.event.LocationCreatedEvent;
import com.ivoyant.hrms.common.event.LocationDeletedEvent;
import com.ivoyant.hrms.common.event.LocationPolicyChangedEvent;
import com.ivoyant.hrms.common.event.LocationUpdatedEvent;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.security.ModuleAccess;
import com.ivoyant.hrms.common.web.ConflictException;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.InUseException;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.common.web.PageResponse;
import com.ivoyant.hrms.common.web.StaleException;
import com.ivoyant.hrms.common.web.UnprocessableException;
import com.ivoyant.hrms.org.AttendanceMethod;
import com.ivoyant.hrms.org.LocationType;
import com.ivoyant.hrms.org.WeekPattern;
import com.ivoyant.hrms.org.domain.Location;
import com.ivoyant.hrms.org.dto.LocationBasic;
import com.ivoyant.hrms.org.dto.LocationEmployee;
import com.ivoyant.hrms.org.dto.LocationMeta;
import com.ivoyant.hrms.org.dto.LocationOption;
import com.ivoyant.hrms.org.dto.LocationResponse;
import com.ivoyant.hrms.org.dto.LocationSummary;
import com.ivoyant.hrms.org.dto.LocationUsage;
import com.ivoyant.hrms.org.dto.StatutoryPreview;
import com.ivoyant.hrms.org.ref.IndianStates;
import com.ivoyant.hrms.org.ref.TimeZones;
import com.ivoyant.hrms.org.repo.LocationRepository;
import com.ivoyant.hrms.payroll.PtRules;
import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Settings → Organisation → Locations. */
@Service
public class LocationService {

	private static final String ENTITY = "Location";
	private static final Pattern CODE = Pattern.compile("^[A-Z0-9-]{2,12}$");
	private static final Pattern PIN = Pattern.compile("^\\d{6}$");
	private static final Pattern GSTIN = Pattern.compile("^\\d{2}[A-Z0-9]{13}$");
	private static final Pattern PHONE = Pattern.compile("^[+0-9 ()-]{6,20}$");
	private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
	/** Changes to these affect pay or attendance, so payroll and attendance are told (from today, never retroactively). */
	private static final List<String> POLICY_KEYS = List.of("state", "lwfApplicable", "esiNotified",
			"holidayCalendarId", "weekPattern", "startTime", "endTime", "graceMinutes", "timezone");

	@PersistenceContext
	private EntityManager em;

	private final LocationRepository repo;
	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final ModuleAccess access;
	private final HolidayCalendars calendars;
	private final PtRules ptRules;
	private final FgaOutbox fga;
	private final Audit audit;
	private final ApplicationEventPublisher events;

	public LocationService(LocationRepository repo, JdbcClient jdbc, CurrentUser currentUser, ModuleAccess access,
			HolidayCalendars calendars, PtRules ptRules, FgaOutbox fga, Audit audit, ApplicationEventPublisher events) {
		this.repo = repo;
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.access = access;
		this.calendars = calendars;
		this.ptRules = ptRules;
		this.fga = fga;
		this.audit = audit;
		this.events = events;
	}

	// ================================================================== reading

	/** Full cards for callers who may view location settings, a short row for everyone else. */
	@Transactional(readOnly = true)
	public List<?> list(Boolean active, String q, boolean includeStats) {
		UUID orgId = currentUser.orgId();
		String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
		List<Location> rows = repo.findByOrgId(orgId).stream()
				.filter(l -> active == null || l.isActive() == active)
				.filter(l -> needle.isEmpty() || contains(l.getName(), needle) || contains(l.getCode(), needle)
						|| contains(l.getCity(), needle) || contains(l.getState(), needle))
				.sorted(Comparator.comparing(Location::isActive).reversed()
						.thenComparing(l -> l.getType() != LocationType.HEAD_OFFICE)
						.thenComparing(Location::getName, String.CASE_INSENSITIVE_ORDER))
				.toList();
		if (!access.has("org", "VIEW")) {
			return rows.stream().map(l -> new LocationBasic(l.getId(), l.getName(), l.getCode(), l.getCity(), l.getState(),
					l.isActive())).toList();
		}
		Context ctx = context(rows, includeStats);
		return rows.stream().map(l -> response(l, ctx)).toList();
	}

	@Transactional(readOnly = true)
	public LocationSummary summary() {
		UUID orgId = currentUser.orgId();
		List<Location> all = repo.findByOrgId(orgId);
		long active = all.stream().filter(Location::isActive).count();
		long states = all.stream().map(Location::getState).filter(s -> s != null && !s.isBlank()).distinct().count();
		long employees = headcounts(orgId).values().stream().mapToLong(Long::longValue).sum();
		String text = active + " active · " + states + (states == 1 ? " state · " : " states · ") + employees
				+ (employees == 1 ? " employee" : " employees");
		return new LocationSummary(active, all.size() - active, states, employees, text);
	}

	@Transactional(readOnly = true)
	public LocationResponse get(UUID id) {
		Location l = find(id);
		return response(l, context(List.of(l), true));
	}

	@Transactional(readOnly = true)
	public List<LocationOption> options() {
		return repo.findByOrgId(currentUser.orgId()).stream().filter(Location::isActive)
				.sorted(Comparator.comparing(Location::getName, String.CASE_INSENSITIVE_ORDER))
				.map(l -> new LocationOption(l.getId(), l.getName(), l.getCode())).toList();
	}

	@Transactional(readOnly = true)
	public LocationMeta meta() {
		int year = LocalDate.now().getYear();
		return new LocationMeta(
				Arrays.stream(LocationType.values())
						.map(t -> new LocationMeta.TypeOption(t.name(), t.label(), t.fg(), t.bg())).toList(),
				Arrays.stream(AttendanceMethod.values()).map(m -> new LocationMeta.Option(m.name(), m.label())).toList(),
				Arrays.stream(WeekPattern.values()).map(w -> new LocationMeta.Option(w.name(), w.label())).toList(),
				IndianStates.ALL.stream().map(s -> new LocationMeta.StateOption(s.name(), s.code(), s.gst(), s.pt(), s.lwf())).toList(),
				TimeZones.offered().stream().map(z -> new LocationMeta.ZoneOption(z.id(), z.label())).toList(),
				calendars.all().stream().filter(CalendarView::active)
						.map(c -> new LocationMeta.CalendarOption(c.id(), c.name(), c.state(), c.name() + " " + year)).toList());
	}

	/** What the drawer shows under the state field: the professional-tax rule and who it affects. */
	@Transactional(readOnly = true)
	public StatutoryPreview statutoryPreview(String state, UUID locationId) {
		String shown = state == null ? "" : state.trim();
		String rule = ptRules.describe(shown).orElse(null);
		long affected = locationId == null ? 0 : usage(locationId).employees() - usage(locationId).exitedEmployees();
		boolean lwf = IndianStates.lwfDefault(shown);
		if (rule == null) {
			return new StatutoryPreview("No professional tax in " + shown, "Nothing is deducted for employees based here",
					lwf, affected);
		}
		return new StatutoryPreview(shown + " professional tax", rule, lwf, affected);
	}

	@Transactional(readOnly = true)
	public LocationUsage usage(UUID id) {
		Location l = find(id);
		String idText = l.getId().toString();
		long[] emp = jdbc.sql("select count(*), count(*) filter (where status = 'EXITED') from employee where location_id = :id")
				.param("id", id).query((rs, i) -> new long[] {rs.getLong(1), rs.getLong(2)}).single();
		long departments = count("select count(*) from department where default_location_id = :id", id);
		long roleScopes = count("select count(*) from user_role where scope = 'LOCATION' and scope_ref = :id", id);
		long pendingHires = jdbc.sql("""
				select count(*) from config_field_value v join config_field f on f.id = v.field_id
				where f.area_key = 'onboarding' and f.key = 'loc' and v.value #>> '{}' = :id
				""").param("id", idText).query(Long.class).single();
		long fieldValues = jdbc.sql("""
				select count(*) from config_field_value v join config_field f on f.id = v.field_id
				where f.type = 'LOCATION' and not (f.area_key = 'onboarding' and f.key = 'loc') and v.value #>> '{}' = :id
				""").param("id", idText).query(Long.class).single();
		// assets and recruiting have no tables yet, so nothing can be held there
		return new LocationUsage(emp[0], emp[1], departments, 0, 0, pendingHires, roleScopes, fieldValues);
	}

	@Transactional(readOnly = true)
	public PageResponse<LocationEmployee> employees(UUID id, String status, int page, int size) {
		find(id);
		int pageSize = Math.min(Math.max(size, 1), 100);
		int offset = Math.max(page, 0) * pageSize;
		boolean all = status == null || status.isBlank();
		long total = jdbc.sql("select count(*) from employee where location_id = :id" + (all ? "" : " and status = :status"))
				.param("id", id).param("status", all ? "" : status.toUpperCase(Locale.ROOT)).query(Long.class).single();
		List<LocationEmployee> items = jdbc.sql("""
				select id, employee_code, display_name, work_email, status from employee
				where location_id = :id %s order by display_name limit :limit offset :offset
				""".formatted(all ? "" : "and status = :status"))
				.param("id", id).param("status", all ? "" : status.toUpperCase(Locale.ROOT))
				.param("limit", pageSize).param("offset", offset)
				.query((rs, i) -> new LocationEmployee(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
						rs.getString(4), rs.getString(5)))
				.list();
		return new PageResponse<>(items, Math.max(page, 0), pageSize, total);
	}

	// ================================================================== writing

	@Transactional
	public LocationResponse create(Map<String, Object> body) {
		UUID orgId = currentUser.orgId();
		Location l = new Location();
		l.setOrgId(orgId);
		OrgDefaults org = orgDefaults(orgId);
		l.setCountry(org.country());
		l.setTimezone(org.timezone());

		Input in = new Input(body);
		in.applyTo(l);
		if (!in.touched.contains("holidayCalendarId") || l.getHolidayCalendarId() == null) {
			l.setHolidayCalendarId(defaultCalendar(l.getState()));
		}
		if (!in.touched.contains("lwfApplicable") || in.nulls.contains("lwfApplicable")) {
			l.setLwfApplicable(IndianStates.lwfDefault(l.getState()));
		}
		if (l.getCountry() == null) {
			l.setCountry(org.country());
		}
		if (l.getTimezone() == null) {
			l.setTimezone(org.timezone());
		}
		List<String> warnings = new ArrayList<>();
		validate(l, in, true, warnings, 0);
		throwIfInvalid(in);
		requireUnique(l, null);

		repo.saveAndFlush(l);
		if (l.getHeadEmployeeId() != null) {
			fga.write(FgaTuples.locationHead(l.getId(), l.getHeadEmployeeId()));
		}
		fga.write(FgaTuples.locationOrg(orgId, l.getId()));
		LocationResponse out = response(l, context(List.of(l), true)).withWarnings(warnings);
		audit.created("org", ENTITY, l.getId(), out);
		events.publishEvent(new LocationCreatedEvent(l.getId(), l.getName(), l.getState()));
		return out;
	}

	@Transactional
	public LocationResponse update(UUID id, Map<String, Object> patch, String ifMatch) {
		// validate against the database before the edited entity is flushed (a duplicate must answer 409, not hit the index)
		em.setFlushMode(FlushModeType.COMMIT);
		Location l = find(id);
		if (ifMatch != null && !ifMatch.isBlank()
				&& !ifMatch.replace("W/", "").replace("\"", "").trim().equals(String.valueOf(l.getVersion()))) {
			throw new StaleException("Someone else changed this location. Reload and try again.");
		}
		Context ctx = context(List.of(l), true);
		LocationResponse before = response(l, ctx);
		Map<String, Object> policyBefore = policy(l);
		String oldState = l.getState();
		UUID oldHead = l.getHeadEmployeeId();
		boolean wasActive = l.isActive();

		Input in = new Input(patch);
		in.applyTo(l);
		if (in.touched.contains("state") && !Objects.equals(oldState, l.getState())) {
			if (!in.touched.contains("holidayCalendarId")) {
				l.setHolidayCalendarId(defaultCalendar(l.getState()));
			}
			if (!in.touched.contains("lwfApplicable")) {
				l.setLwfApplicable(IndianStates.lwfDefault(l.getState()));
			}
			in.touched.add("holidayCalendarId");
			in.touched.add("lwfApplicable");
		}
		if (in.nulls.contains("lwfApplicable")) {
			l.setLwfApplicable(IndianStates.lwfDefault(l.getState()));
		}
		List<String> warnings = new ArrayList<>();
		long headcount = ctx.headcounts == null ? 0 : ctx.headcounts.getOrDefault(id, 0L);
		validate(l, in, false, warnings, headcount);
		throwIfInvalid(in);
		requireUnique(l, id);

		if (wasActive && !l.isActive()) {
			if (headcount > 0) {
				warnings.add(headcount + (headcount == 1 ? " employee is" : " employees are") + " still based here");
			}
			long scoped = count("select count(*) from user_role where scope = 'LOCATION' and scope_ref = :id", id);
			if (scoped > 0) {
				warnings.add(scoped + (scoped == 1 ? " role is" : " roles are") + " still scoped to this location");
			}
		}
		repo.saveAndFlush(l);
		if (!Objects.equals(oldHead, l.getHeadEmployeeId())) {
			if (oldHead != null) {
				fga.delete(FgaTuples.locationHead(id, oldHead));
			}
			if (l.getHeadEmployeeId() != null) {
				fga.write(FgaTuples.locationHead(id, l.getHeadEmployeeId()));
			}
		}
		LocationResponse after = response(l, context(List.of(l), true)).withWarnings(warnings);

		Map<String, Object> policyAfter = policy(l);
		List<String> policyChanged = POLICY_KEYS.stream()
				.filter(k -> !Objects.equals(String.valueOf(policyBefore.get(k)), String.valueOf(policyAfter.get(k)))).toList();
		audit.updated("org", ENTITY, id, before, after);
		events.publishEvent(new LocationUpdatedEvent(id, List.copyOf(in.touched)));
		if (!policyChanged.isEmpty()) {
			events.publishEvent(new LocationPolicyChangedEvent(id, policyChanged, LocalDate.now()));
		}
		if (wasActive != l.isActive()) {
			events.publishEvent(new LocationActivationEvent(id, l.isActive()));
		}
		return after;
	}

	@Transactional
	public void delete(UUID id) {
		Location l = find(id);
		LocationUsage usage = usage(id);
		if (usage.inUse()) {
			throw new InUseException(inUseMessage(usage), usageMap(usage));
		}
		LocationResponse before = response(l, context(List.of(l), false));
		fga.delete(FgaTuples.locationOrg(l.getOrgId(), id));
		if (l.getHeadEmployeeId() != null) {
			fga.delete(FgaTuples.locationHead(id, l.getHeadEmployeeId()));
		}
		repo.delete(l);
		repo.flush();
		audit.deleted("org", ENTITY, id, before);
		events.publishEvent(new LocationDeletedEvent(id));
	}

	private static String inUseMessage(LocationUsage u) {
		if (u.employees() > 0) {
			return u.employees() + (u.employees() == 1 ? " employee is" : " employees are")
					+ " based here. Reassign them first, or mark the location inactive.";
		}
		List<String> parts = new ArrayList<>();
		if (u.departments() > 0) {
			parts.add(u.departments() + " department default(s)");
		}
		if (u.assets() > 0) {
			parts.add(u.assets() + " asset(s)");
		}
		if (u.pendingHires() > 0) {
			parts.add(u.pendingHires() + " pending hire(s)");
		}
		if (u.openRequisitions() > 0) {
			parts.add(u.openRequisitions() + " open requisition(s)");
		}
		if (u.roleScopes() > 0) {
			parts.add(u.roleScopes() + " scoped role(s)");
		}
		if (u.fieldValues() > 0) {
			parts.add(u.fieldValues() + " form value(s)");
		}
		return "This location is still used by " + String.join(", ", parts) + ". Move them first, or mark it inactive.";
	}

	private static Map<String, Object> usageMap(LocationUsage u) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("employees", u.employees());
		m.put("exitedEmployees", u.exitedEmployees());
		m.put("departments", u.departments());
		m.put("assets", u.assets());
		m.put("openRequisitions", u.openRequisitions());
		m.put("pendingHires", u.pendingHires());
		m.put("roleScopes", u.roleScopes());
		m.put("fieldValues", u.fieldValues());
		return m;
	}

	// ================================================================== input and validation

	/** A request body applied to a location: which keys were present, and what went wrong reading them. */
	private final class Input {

		final Map<String, Object> body;
		final Set<String> touched = new LinkedHashSet<>();
		final Set<String> nulls = new LinkedHashSet<>();
		final List<ErrorItem> errors = new ArrayList<>();

		Input(Map<String, Object> body) {
			this.body = body;
		}

		void applyTo(Location l) {
			for (Map.Entry<String, Object> e : body.entrySet()) {
				String k = e.getKey();
				Object v = e.getValue();
				boolean known = true;
				try {
					switch (k) {
						case "name" -> l.setName(text(v));
						case "code" -> l.setCode(upper(v));
						case "type" -> l.setType(v == null ? LocationType.BRANCH_OFFICE : enumOf(LocationType.class, v));
						case "headEmployeeId" -> l.setHeadEmployeeId(uuid(v));
						case "addressLine" -> l.setAddressLine(text(v));
						case "city" -> l.setCity(text(v));
						case "state" -> l.setState(text(v));
						case "postalCode" -> l.setPostalCode(digits(v, 6));
						case "country" -> l.setCountry(v == null ? null : upper(v));
						case "timezone" -> l.setTimezone(zone(v));
						case "phone" -> l.setPhone(text(v));
						case "email" -> l.setEmail(text(v));
						case "weekPattern" -> l.setWeekPattern(v == null ? WeekPattern.MON_FRI : enumOf(WeekPattern.class, v));
						case "startTime" -> l.setStartTime(time(v, l.getStartTime()));
						case "endTime" -> l.setEndTime(time(v, l.getEndTime()));
						case "graceMinutes" -> l.setGraceMinutes(v == null ? 15 : integer(v));
						case "holidayCalendarId" -> l.setHolidayCalendarId(uuid(v));
						case "attendanceMethods" -> l.setAttendanceMethods(methods(v));
						case "geoRadiusM" -> l.setGeoRadiusM(v == null ? null : integer(v));
						case "latitude" -> l.setLatitude(decimal(v));
						case "longitude" -> l.setLongitude(decimal(v));
						case "wfhAllowed" -> l.setWfhAllowed(v == null || bool(v));
						case "lwfApplicable" -> l.setLwfApplicable(v != null && bool(v));
						case "esiNotified" -> l.setEsiNotified(v == null || bool(v));
						case "shopsEstabRegNo" -> l.setShopsEstabRegNo(text(v));
						case "gstin" -> l.setGstin(upper(v));
						case "seatCapacity" -> l.setSeatCapacity(v == null ? null : integer(v));
						case "assetStore" -> l.setAssetStore(v != null && bool(v));
						case "active" -> l.setActive(v == null || bool(v));
						default -> known = false;
					}
				}
				catch (IllegalArgumentException | DateTimeParseException ex) {
					errors.add(ErrorItem.field(k, "INVALID", "'" + k + "' is not valid: " + ex.getMessage()));
				}
				if (known) {
					touched.add(k);
					if (v == null) {
						nulls.add(k);
					}
				}
			}
		}
	}

	private void validate(Location l, Input in, boolean create, List<String> warnings, long headcount) {
		List<ErrorItem> errors = in.errors;
		java.util.function.Predicate<String[]> any = keys -> create || Arrays.stream(keys).anyMatch(in.touched::contains);
		boolean india = "IN".equalsIgnoreCase(l.getCountry());

		if (any.test(new String[] {"name"}) && blank(l.getName())) {
			errors.add(ErrorItem.field("name", "REQUIRED", "Name is required"));
		}
		if (any.test(new String[] {"code"})) {
			if (blank(l.getCode())) {
				errors.add(ErrorItem.field("code", "REQUIRED", "Code is required"));
			}
			else if (!CODE.matcher(l.getCode()).matches()) {
				errors.add(ErrorItem.field("code", "CODE_FORMAT", "Code must be 2–12 letters, digits or dashes"));
			}
		}
		for (String[] f : new String[][] {{"addressLine", "Address"}, {"city", "City"}}) {
			String value = "addressLine".equals(f[0]) ? l.getAddressLine() : l.getCity();
			if (any.test(new String[] {f[0]}) && blank(value)) {
				errors.add(ErrorItem.field(f[0], "REQUIRED", f[1] + " is required"));
			}
		}
		if (any.test(new String[] {"state", "country"})) {
			if (blank(l.getState())) {
				errors.add(ErrorItem.field("state", "REQUIRED", "State is required"));
			}
			else if (india && IndianStates.byName(l.getState()).isEmpty()) {
				errors.add(ErrorItem.field("state", "INVALID", "'" + l.getState() + "' is not a state we support"));
			}
			else if (india) {
				l.setState(IndianStates.byName(l.getState()).get().name());
			}
		}
		if (any.test(new String[] {"postalCode", "country"})) {
			if (blank(l.getPostalCode())) {
				errors.add(ErrorItem.field("postalCode", "REQUIRED", "PIN code is required"));
			}
			else if (india && !PIN.matcher(l.getPostalCode()).matches()) {
				errors.add(ErrorItem.field("postalCode", "PIN_FORMAT", "PIN code must be 6 digits"));
			}
		}
		if (any.test(new String[] {"timezone"}) && blank(l.getTimezone())) {
			errors.add(ErrorItem.field("timezone", "TIMEZONE", "Choose a valid time zone"));
		}
		if (!blank(l.getPhone()) && any.test(new String[] {"phone"}) && !PHONE.matcher(l.getPhone()).matches()) {
			errors.add(ErrorItem.field("phone", "PHONE", "Enter a valid phone number"));
		}
		if (!blank(l.getEmail()) && any.test(new String[] {"email"}) && !EMAIL.matcher(l.getEmail()).matches()) {
			errors.add(ErrorItem.field("email", "EMAIL", "Enter a valid email address"));
		}
		if (any.test(new String[] {"startTime", "endTime"}) && l.getStartTime().equals(l.getEndTime())) {
			errors.add(ErrorItem.field("endTime", "TIME_RANGE", "End time must differ from start time"));
		}
		if (any.test(new String[] {"graceMinutes"}) && (l.getGraceMinutes() < 0 || l.getGraceMinutes() > 120)) {
			errors.add(ErrorItem.field("graceMinutes", "INVALID", "Grace period must be 0–120 minutes"));
		}
		if (l.getHolidayCalendarId() == null) {
			if (any.test(new String[] {"holidayCalendarId"})) {
				errors.add(ErrorItem.field("holidayCalendarId", "REQUIRED", "Choose a holiday calendar"));
			}
		}
		else if (any.test(new String[] {"holidayCalendarId"}) && !calendars.exists(l.getHolidayCalendarId())) {
			errors.add(ErrorItem.field("holidayCalendarId", "CALENDAR_NOT_FOUND", "That holiday calendar does not exist"));
		}
		if (any.test(new String[] {"attendanceMethods", "geoRadiusM"})) {
			if (l.getAttendanceMethods().isEmpty()) {
				errors.add(ErrorItem.field("attendanceMethods", "METHODS_EMPTY", "Keep at least one attendance method"));
			}
			if (l.getAttendanceMethods().contains(AttendanceMethod.MOBILE_GPS.name())
					&& (l.getGeoRadiusM() == null || l.getGeoRadiusM() < 25 || l.getGeoRadiusM() > 5000)) {
				errors.add(ErrorItem.field("geoRadiusM", "GPS_RADIUS_REQUIRED",
						"Mobile GPS needs a geofence radius of 25–5000 metres"));
			}
		}
		if (!blank(l.getGstin()) && any.test(new String[] {"gstin"})) {
			if (!GSTIN.matcher(l.getGstin()).matches()) {
				errors.add(ErrorItem.field("gstin", "GSTIN_FORMAT", "GSTIN must be 15 characters"));
			}
			else {
				IndianStates.byName(l.getState()).filter(s -> !l.getGstin().startsWith(s.gst())).ifPresent(s -> warnings
						.add("GSTIN starts with " + l.getGstin().substring(0, 2) + " but " + s.name() + " is " + s.gst()));
			}
		}
		if (l.getSeatCapacity() != null && l.getSeatCapacity() < 0) {
			errors.add(ErrorItem.field("seatCapacity", "INVALID", "Seat capacity cannot be negative"));
		}
		if (l.getSeatCapacity() != null && headcount > l.getSeatCapacity()) {
			warnings.add((headcount - l.getSeatCapacity()) + " over capacity · WFH rotation needed");
		}
		if (l.getHeadEmployeeId() != null && any.test(new String[] {"headEmployeeId"}) && !activeEmployee(l.getHeadEmployeeId())) {
			errors.add(ErrorItem.field("headEmployeeId", "HEAD_INACTIVE", "The location head must be an active employee"));
		}
	}

	private void throwIfInvalid(Input in) {
		if (!in.errors.isEmpty()) {
			throw new UnprocessableException(in.errors);
		}
	}

	private void requireUnique(Location l, UUID selfId) {
		UUID org = currentUser.orgId();
		boolean codeTaken = selfId == null ? repo.existsByOrgIdAndCodeIgnoreCase(org, l.getCode())
				: repo.existsByOrgIdAndCodeIgnoreCaseAndIdNot(org, l.getCode(), selfId);
		if (codeTaken) {
			throw new ConflictException("Location code already in use");
		}
		boolean nameTaken = selfId == null ? repo.existsByOrgIdAndNameIgnoreCase(org, l.getName())
				: repo.existsByOrgIdAndNameIgnoreCaseAndIdNot(org, l.getName(), selfId);
		if (nameTaken) {
			throw new ConflictException("A location with this name already exists");
		}
	}

	// ---- value readers ----

	private static String text(Object v) {
		return v == null || v.toString().isBlank() ? null : v.toString().trim();
	}

	private static String upper(Object v) {
		String t = text(v);
		return t == null ? null : t.toUpperCase(Locale.ROOT);
	}

	private static String digits(Object v, int max) {
		String t = text(v);
		if (t == null) {
			return null;
		}
		String d = t.replaceAll("\\D", "");
		return d.length() > max ? d.substring(0, max) : d;
	}

	private static UUID uuid(Object v) {
		String t = text(v);
		return t == null ? null : UUID.fromString(t);
	}

	private static Integer integer(Object v) {
		if (v instanceof Number n) {
			return n.intValue();
		}
		return Integer.valueOf(v.toString().trim());
	}

	private static BigDecimal decimal(Object v) {
		String t = text(v);
		return t == null ? null : new BigDecimal(t);
	}

	private static boolean bool(Object v) {
		if (v instanceof Boolean b) {
			return b;
		}
		String t = v.toString().trim().toLowerCase(Locale.ROOT);
		if (!t.equals("true") && !t.equals("false")) {
			throw new IllegalArgumentException("must be true or false");
		}
		return Boolean.parseBoolean(t);
	}

	private static LocalTime time(Object v, LocalTime fallback) {
		String t = text(v);
		return t == null ? fallback : LocalTime.parse(t.length() == 5 ? t : t.substring(0, 5));
	}

	private static String zone(Object v) {
		String t = text(v);
		if (t == null) {
			return null;
		}
		java.time.ZoneId z = TimeZones.parse(t);
		if (z == null) {
			throw new IllegalArgumentException("unknown time zone");
		}
		return z.getId();
	}

	private static <E extends Enum<E>> E enumOf(Class<E> type, Object v) {
		return Enum.valueOf(type, v.toString().trim().toUpperCase(Locale.ROOT).replace(' ', '_'));
	}

	private static List<String> methods(Object v) {
		if (!(v instanceof Collection<?> c)) {
			throw new IllegalArgumentException("must be a list");
		}
		return c.stream().map(x -> enumOf(AttendanceMethod.class, x).name()).distinct().collect(Collectors.toCollection(ArrayList::new));
	}

	private static boolean blank(String s) {
		return s == null || s.isBlank();
	}

	// ================================================================== helpers

	private record OrgDefaults(String country, String timezone) {
	}

	private OrgDefaults orgDefaults(UUID orgId) {
		return jdbc.sql("select country, timezone from org where id = :id").param("id", orgId)
				.query((rs, i) -> new OrgDefaults(rs.getString(1) == null ? "IN" : rs.getString(1),
						rs.getString(2) == null ? "Asia/Kolkata" : rs.getString(2)))
				.optional().orElse(new OrgDefaults("IN", "Asia/Kolkata"));
	}

	/** The state's own calendar if there is one, else the national-only calendar. */
	private UUID defaultCalendar(String state) {
		return calendars.forState(state).or(calendars::nationalOnly).orElse(null);
	}

	private boolean activeEmployee(UUID id) {
		return jdbc.sql("select count(*) from employee where id = :id and org_id = :org and status <> 'EXITED'")
				.param("id", id).param("org", currentUser.orgId()).query(Long.class).single() > 0;
	}

	private long count(String sql, UUID id) {
		return jdbc.sql(sql).param("id", id).query(Long.class).single();
	}

	private Map<UUID, Long> headcounts(UUID orgId) {
		Map<UUID, Long> out = new HashMap<>();
		jdbc.sql("""
				select location_id, count(*) from employee
				where org_id = :org and status <> 'EXITED' and location_id is not null group by location_id
				""").param("org", orgId).query((rs, i) -> {
					out.put(rs.getObject(1, UUID.class), rs.getLong(2));
					return 0;
				}).list();
		return out;
	}

	private Map<String, Object> policy(Location l) {
		Map<String, Object> m = new HashMap<>();
		m.put("state", l.getState());
		m.put("lwfApplicable", l.isLwfApplicable());
		m.put("esiNotified", l.isEsiNotified());
		m.put("holidayCalendarId", l.getHolidayCalendarId());
		m.put("weekPattern", l.getWeekPattern());
		m.put("startTime", l.getStartTime());
		m.put("endTime", l.getEndTime());
		m.put("graceMinutes", l.getGraceMinutes());
		m.put("timezone", l.getTimezone());
		return m;
	}

	private Location find(UUID id) {
		return repo.findByIdAndOrgId(id, currentUser.orgId())
				.orElseThrow(() -> new NotFoundException("Location " + id + " not found"));
	}

	private static boolean contains(String value, String needle) {
		return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
	}

	// ---- response building: batch loads, no per-row queries ----

	private record Context(Map<UUID, String> headNames, Map<UUID, CalendarView> calendars, Map<UUID, Long> headcounts,
			int year) {
	}

	private Context context(Collection<Location> rows, boolean withStats) {
		Set<UUID> heads = rows.stream().map(Location::getHeadEmployeeId).filter(Objects::nonNull).collect(Collectors.toSet());
		Map<UUID, String> names = new HashMap<>();
		if (!heads.isEmpty()) {
			jdbc.sql("select id, display_name from employee where id in (:ids)").param("ids", heads)
					.query((rs, i) -> {
						names.put(rs.getObject(1, UUID.class), rs.getString(2));
						return 0;
					}).list();
		}
		Set<UUID> cals = rows.stream().map(Location::getHolidayCalendarId).filter(Objects::nonNull).collect(Collectors.toSet());
		return new Context(names, calendars.byIds(cals), withStats ? headcounts(currentUser.orgId()) : null,
				LocalDate.now().getYear());
	}

	private LocationResponse response(Location l, Context ctx) {
		String headName = l.getHeadEmployeeId() == null ? null : ctx.headNames().get(l.getHeadEmployeeId());
		LocationResponse.Head head = headName == null ? null
				: new LocationResponse.Head(l.getHeadEmployeeId(), headName, initials(headName));
		CalendarView cal = l.getHolidayCalendarId() == null ? null : ctx.calendars().get(l.getHolidayCalendarId());
		LocationResponse.HolidayCalendar calendar = cal == null ? null
				: new LocationResponse.HolidayCalendar(cal.id(), cal.name(), cal.name() + " " + ctx.year());
		LocationResponse.Stats stats = null;
		if (ctx.headcounts() != null) {
			long headcount = ctx.headcounts().getOrDefault(l.getId(), 0L);
			Integer cap = l.getSeatCapacity();
			stats = new LocationResponse.Stats(headcount, cap == null || cap == 0 ? null : (double) headcount / cap,
					cap == null ? null : (int) Math.max(cap - headcount, 0));
		}
		return new LocationResponse(l.getId(), l.getName(), l.getCode(), l.getType(), l.getType().label(),
				new LocationResponse.Address(l.getAddressLine(), l.getCity(), l.getState(), l.getPostalCode(), l.getCountry()),
				l.getTimezone(), l.getPhone(), l.getEmail(), head,
				new LocationResponse.Schedule(l.getWeekPattern().name(), l.getWeekPattern().label(), hhmm(l.getStartTime()),
						hhmm(l.getEndTime()), l.getGraceMinutes()),
				calendar,
				new LocationResponse.Attendance(List.copyOf(l.getAttendanceMethods()), l.getGeoRadiusM(), l.getLatitude(),
						l.getLongitude(), l.isWfhAllowed()),
				new LocationResponse.Statutory(l.isLwfApplicable(), l.isEsiNotified(), l.getShopsEstabRegNo(), l.getGstin(),
						ptRules.label(l.getState()), ptRules.applies(l.getState())),
				l.getSeatCapacity(), l.isAssetStore(), l.isActive(), stats, l.getVersion(), List.of());
	}

	private static String hhmm(LocalTime t) {
		return String.format("%02d:%02d", t.getHour(), t.getMinute());
	}

	private static String initials(String name) {
		return Arrays.stream(name.trim().split("\\s+")).filter(p -> !p.isEmpty()).limit(2)
				.map(p -> p.substring(0, 1).toUpperCase(Locale.ROOT)).collect(Collectors.joining());
	}
}
