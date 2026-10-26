package com.ivoyant.hrms.org.service;

import com.ivoyant.hrms.common.audit.Audit;
import com.ivoyant.hrms.common.authz.AuthzService;
import com.ivoyant.hrms.common.event.OrgFinancialYearChangedEvent;
import com.ivoyant.hrms.common.event.OrgTimezoneChangedEvent;
import com.ivoyant.hrms.common.event.OrgUpdatedEvent;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.ForbiddenException;
import com.ivoyant.hrms.common.web.NotFoundException;
import com.ivoyant.hrms.common.web.StaleException;
import com.ivoyant.hrms.common.web.UnprocessableException;
import com.ivoyant.hrms.org.domain.Org;
import com.ivoyant.hrms.org.dto.OrgMeta;
import com.ivoyant.hrms.org.dto.OrgResponse;
import com.ivoyant.hrms.org.ref.TimeZones;
import com.ivoyant.hrms.org.repo.OrgRepository;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Settings → Organisation → Organization: the profile. */
@Service
public class OrgService {

	private static final Pattern CODE = Pattern.compile("^[A-Z0-9]{2,12}$");
	private static final Pattern PREFIX = Pattern.compile("^[A-Z0-9]{2,8}$");
	private static final Pattern DOMAIN = Pattern.compile("^[a-z0-9.-]+\\.[a-z]{2,}$");
	private static final List<String> STATUSES = List.of("ACTIVE", "INACTIVE", "ARCHIVED");
	private static final List<String> COUNTRIES = List.of("IN", "US", "GB", "AE", "SG", "AU");
	private static final List<String> CURRENCIES = List.of("INR", "USD", "EUR", "GBP", "AED", "SGD", "AUD");

	private final OrgRepository repo;
	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final AuthzService authz;
	private final Audit audit;
	private final ApplicationEventPublisher events;

	public OrgService(OrgRepository repo, JdbcClient jdbc, CurrentUser currentUser, AuthzService authz, Audit audit,
			ApplicationEventPublisher events) {
		this.repo = repo;
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.authz = authz;
		this.audit = audit;
		this.events = events;
	}

	/** Admins get everything, other members the basic fields. */
	@Transactional(readOnly = true)
	public OrgResponse get() {
		Org org = load();
		return authz.isHrAdmin() ? full(org) : basic(org);
	}

	@Transactional
	public OrgResponse patch(Map<String, Object> patch, String ifMatch) {
		Org org = load();
		if (ifMatch != null && !ifMatch.isBlank()
				&& !ifMatch.replace("W/", "").replace("\"", "").trim().equals(String.valueOf(org.getVersion()))) {
			throw new StaleException("Someone else changed the organization. Reload and try again.");
		}
		Map<String, Object> before = snapshot(org);
		List<ErrorItem> errors = new ArrayList<>();
		for (Map.Entry<String, Object> e : patch.entrySet()) {
			apply(org, e.getKey(), e.getValue(), errors);
		}
		if (!errors.isEmpty()) {
			throw new UnprocessableException(errors);
		}
		Map<String, Object> after = snapshot(org);
		List<String> changed = after.keySet().stream().filter(k -> !Objects.equals(before.get(k), after.get(k))).toList();
		if (changed.isEmpty()) {
			return full(org);
		}
		repo.saveAndFlush(org);
		for (String key : changed) {
			audit.publish("org", "Organization", org.getId(), "UPDATE", Map.of(key, String.valueOf(before.get(key))),
					Map.of(key, String.valueOf(after.get(key))));
		}
		events.publishEvent(new OrgUpdatedEvent(changed));
		if (changed.contains("timezone")) {
			events.publishEvent(new OrgTimezoneChangedEvent((String) before.get("timezone"), (String) after.get("timezone")));
		}
		if (changed.contains("fyStartMonth")) {
			events.publishEvent(new OrgFinancialYearChangedEvent((Integer) before.get("fyStartMonth"),
					(Integer) after.get("fyStartMonth")));
		}
		List<String> warnings = new ArrayList<>();
		if (changed.contains("fyStartMonth")) {
			warnings.add("Financial year change affects the leave year and the payroll tax year");
		}
		if (changed.contains("timezone")) {
			warnings.add("Locations and employees without their own time zone now use " + org.getTimezone());
		}
		if (changed.contains("employeeIdPrefix")) {
			warnings.add("The new prefix applies to new employees only; existing codes are not renumbered");
		}
		if (changed.contains("currency")) {
			Long banded = jdbc.sql("select count(*) from designation where org_id = :o and band_max is not null")
					.param("o", org.getId()).query(Long.class).single();
			if (banded != null && banded > 0) {
				warnings.add(banded + (banded == 1 ? " position band is in " : " position bands are in ")
						+ before.get("currency") + ". They won't be converted.");
			}
		}
		if ("ARCHIVED".equals(org.getStatus())) {
			warnings.add("The organization is archived: every change is blocked except by a super admin");
		}
		return full(org).withWarnings(warnings);
	}

	@Transactional(readOnly = true)
	public OrgMeta meta() {
		return new OrgMeta(
				COUNTRIES.stream().map(c -> new OrgMeta.Country(c, countryName(c))).toList(),
				TimeZones.offered().stream().map(z -> new OrgMeta.Zone(z.id(), z.label())).toList(),
				CURRENCIES.stream().map(c -> new OrgMeta.Currency(c, currencyLabel(c))).toList(),
				java.util.stream.IntStream.rangeClosed(1, 12).mapToObj(m -> new OrgMeta.FyMonth(m, fyLabel(m))).toList(),
				List.of(new OrgMeta.Status("ACTIVE", "Active"), new OrgMeta.Status("INACTIVE", "Inactive"),
						new OrgMeta.Status("ARCHIVED", "Archived")));
	}

	// ---- applying one field ----

	private void apply(Org org, String key, Object raw, List<ErrorItem> errors) {
		String text = raw == null ? null : raw.toString().trim();
		switch (key) {
			case "name" -> {
				if (blank(text) || text.length() > 120) {
					errors.add(ErrorItem.field(key, blank(text) ? "REQUIRED" : "INVALID", "Display name must be 1–120 characters"));
				}
				else {
					org.setName(text);
				}
			}
			case "legalName" -> {
				if (blank(text) || text.length() > 200) {
					errors.add(ErrorItem.field(key, blank(text) ? "REQUIRED" : "INVALID", "Legal name must be 1–200 characters"));
				}
				else {
					org.setLegalName(text);
				}
			}
			case "code" -> {
				String code = text == null ? null : text.toUpperCase(Locale.ROOT);
				if (blank(code)) {
					errors.add(ErrorItem.field(key, "REQUIRED", "Organization code is required"));
				}
				else if (!CODE.matcher(code).matches()) {
					errors.add(ErrorItem.field(key, "CODE_FORMAT", "Code must be 2–12 letters or digits"));
				}
				else if (repo.existsByCodeIgnoreCaseAndIdNot(code, org.getId())) {
					errors.add(ErrorItem.field(key, "CODE_FORMAT", "That organization code is already in use"));
				}
				else {
					org.setCode(code);
				}
			}
			case "industry" -> {
				if (text != null && text.length() > 120) {
					errors.add(ErrorItem.field(key, "INVALID", "Industry must be at most 120 characters"));
				}
				else {
					org.setIndustry(blank(text) ? null : text);
				}
			}
			case "country" -> {
				String code = country(text);
				if (code == null) {
					errors.add(ErrorItem.field(key, "INVALID", "Choose a valid country"));
				}
				else {
					org.setCountry(code);
				}
			}
			case "timezone" -> {
				java.time.ZoneId zone = TimeZones.parse(text);
				if (zone == null) {
					errors.add(ErrorItem.field(key, "TIMEZONE", "Choose a valid time zone"));
				}
				else {
					org.setTimezone(zone.getId());
				}
			}
			case "fyStartMonth" -> {
				Integer month = fyMonth(raw);
				if (month == null) {
					errors.add(ErrorItem.field(key, "INVALID", "Financial year must start in month 1–12"));
				}
				else {
					org.setFyStartMonth(month);
				}
			}
			case "currency" -> {
				String code = text == null ? null : text.toUpperCase(Locale.ROOT).split("\\s+")[0];
				try {
					org.setCurrency(Currency.getInstance(code).getCurrencyCode());
				}
				catch (IllegalArgumentException | NullPointerException ex) {
					errors.add(ErrorItem.field(key, "INVALID", "Choose a valid currency"));
				}
			}
			case "primaryDomain" -> {
				String domain = text == null ? null : text.toLowerCase(Locale.ROOT);
				if (blank(domain)) {
					errors.add(ErrorItem.field(key, "REQUIRED", "Primary domain is required"));
				}
				else if (!DOMAIN.matcher(domain).matches()) {
					errors.add(ErrorItem.field(key, "DOMAIN_FORMAT", "Enter a domain such as ivoyant.com"));
				}
				else {
					org.setPrimaryDomain(domain);
				}
			}
			case "status" -> {
				String status = text == null ? null : text.toUpperCase(Locale.ROOT);
				if (status == null || !STATUSES.contains(status)) {
					errors.add(ErrorItem.field(key, "INVALID", "Status must be Active, Inactive or Archived"));
				}
				else if ("ARCHIVED".equals(status) && !authz.isSuperAdmin()) {
					throw new ForbiddenException("Only a super admin can archive the organization");
				}
				else {
					org.setStatus(status);
				}
			}
			case "employeeIdPrefix" -> {
				String prefix = text == null ? null : text.toUpperCase(Locale.ROOT);
				if (blank(prefix) || !PREFIX.matcher(prefix).matches()) {
					errors.add(ErrorItem.field(key, "CODE_FORMAT", "Prefix must be 2–8 letters or digits"));
				}
				else {
					org.setEmployeeIdPrefix(prefix);
				}
			}
			default -> {
				// unknown keys (id, version, counts, labels the UI echoes back) are ignored
			}
		}
	}

	// ---- reading ----

	private Org load() {
		UUID id = currentUser.orgId();
		return repo.findById(id).orElseThrow(() -> new NotFoundException("Organization " + id + " not found"));
	}

	private Map<String, Object> snapshot(Org o) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("name", o.getName());
		m.put("legalName", o.getLegalName());
		m.put("code", o.getCode());
		m.put("industry", o.getIndustry());
		m.put("country", o.getCountry());
		m.put("timezone", o.getTimezone());
		m.put("fyStartMonth", o.getFyStartMonth());
		m.put("currency", o.getCurrency());
		m.put("primaryDomain", o.getPrimaryDomain());
		m.put("status", o.getStatus());
		m.put("employeeIdPrefix", o.getEmployeeIdPrefix());
		return m;
	}

	private OrgResponse basic(Org o) {
		return new OrgResponse(o.getId(), o.getName(), null, o.getCode(), null, o.getCountry(), null, o.getTimezone(), null,
				o.getFyStartMonth(), null, o.getCurrency(), null, o.getPrimaryDomain(), null, null, null, null, null);
	}

	private OrgResponse full(Org o) {
		UUID id = o.getId();
		long departments = count("department", id);
		long positions = count("designation", id);
		long titles = count("job_title", id);
		long locations = count("location", id);
		long employees = jdbc.sql("select count(*) from employee where org_id = :id and status <> 'EXITED'")
				.param("id", id).query(Long.class).single();
		return new OrgResponse(id, o.getName(), o.getLegalName(), o.getCode(), o.getIndustry(), o.getCountry(),
				countryName(o.getCountry()), o.getTimezone(), TimeZones.label(o.getTimezone()), o.getFyStartMonth(),
				fyLabel(o.getFyStartMonth()), o.getCurrency(), currencyLabel(o.getCurrency()), o.getPrimaryDomain(),
				o.getStatus(), o.getEmployeeIdPrefix(),
				new OrgResponse.Counts(departments, positions, titles, locations, employees), o.getVersion(), List.of());
	}

	private long count(String table, UUID orgId) {
		return jdbc.sql("select count(*) from " + table + " where org_id = :id").param("id", orgId)
				.query(Long.class).single();
	}

	// ---- labels and parsing ----

	static String countryName(String code) {
		return code == null ? null : new Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.ENGLISH);
	}

	/** {@code 4} gives "April – March"; {@code 1} gives "January – December". */
	static String fyLabel(int startMonth) {
		Month start = Month.of(startMonth);
		return start.getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " – "
				+ start.minus(1).getDisplayName(TextStyle.FULL, Locale.ENGLISH);
	}

	static String currencyLabel(String code) {
		Currency c = Currency.getInstance(code);
		return code + " (" + c.getSymbol(Locale.US) + ")";
	}

	private static String country(String value) {
		if (blank(value)) {
			return null;
		}
		String v = value.trim();
		if (v.length() == 2 && Arrays.asList(Locale.getISOCountries()).contains(v.toUpperCase(Locale.ROOT))) {
			return v.toUpperCase(Locale.ROOT);
		}
		return Arrays.stream(Locale.getISOCountries()).filter(c -> countryName(c).equalsIgnoreCase(v)).findFirst().orElse(null);
	}

	/** A month number, or the label such as "April – March". */
	private static Integer fyMonth(Object raw) {
		if (raw instanceof Number n) {
			int m = n.intValue();
			return m >= 1 && m <= 12 ? m : null;
		}
		if (raw == null) {
			return null;
		}
		String s = raw.toString().trim();
		try {
			int m = Integer.parseInt(s);
			return m >= 1 && m <= 12 ? m : null;
		}
		catch (NumberFormatException ex) {
			String first = s.split("\\s+")[0];
			for (Month m : Month.values()) {
				if (m.getDisplayName(TextStyle.FULL, Locale.ENGLISH).equalsIgnoreCase(first)) {
					return m.getValue();
				}
			}
			return null;
		}
	}

	private static boolean blank(String s) {
		return s == null || s.isBlank();
	}
}
