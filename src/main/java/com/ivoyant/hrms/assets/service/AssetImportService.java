package com.ivoyant.hrms.assets.service;

import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.util.Csv;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.UnprocessableException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** CSV import of hardware and licences: a dry run lists every row problem, the real run saves the good rows (500 at a time). Names are matched to the configuration, never created. */
@Service
public class AssetImportService {

	static final int MAX_ROWS = 5000;
	private static final int CHUNK = 500;

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;
	private final HardwareService hardware;
	private final LicenseService licenses;
	private final SpecStore specs;
	private final TransactionTemplate tx;

	AssetImportService(JdbcClient jdbc, CurrentUser currentUser, HardwareService hardware, LicenseService licenses, SpecStore specs, PlatformTransactionManager txm) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
		this.hardware = hardware;
		this.licenses = licenses;
		this.specs = specs;
		this.tx = new TransactionTemplate(txm);
	}

	private record Row(int number, Map<String, String> v) {
	}

	/** "Cost (₹)" and "Assigned to (employee code)" are the column names with a hint; "RAM (GB)" is a field label and stays as it is. */
	private static String normalize(String header) {
		String h = header.trim().toLowerCase(Locale.ROOT);
		return h.replaceFirst("^(tag|cost per seat|cost|assigned to|owner|holders|auto-renew)\\s*\\(.*\\)$", "$1");
	}

	public String template(String kind) {
		if ("software".equalsIgnoreCase(kind)) {
			return "Application,Vendor,Category,License type,Seats,Cost per seat (₹),Start,Renews,Owner (employee code),Holders (codes separated by ;),Auto-renew\n"
					+ "Figma,Figma Inc.,Design,Subscription,18,3600,2026-01-01,2027-01-01,EMP001,EMP002;EMP003,yes\n";
		}
		Set<String> extra = new LinkedHashSet<>();
		specs.fields("asset_hardware", null).forEach(d -> extra.add(d.label()));
		return "Tag (optional),Type,Model,Serial,Status,Ownership,Location,Purchase date,Cost (₹),Warranty until,Assigned to (employee code),Since" + (extra.isEmpty() ? "" : "," + String.join(",", extra))
				+ "\nCMP-LPT-0001,Laptop,Dell Latitude 7450,C0295220,Available,Company,Bengaluru HQ,2026-09-18,112500,2029-09-17,,\n";
	}

	public Map<String, Object> run(String kind, String csv, boolean dryRun) {
		boolean sw = "software".equalsIgnoreCase(kind);
		List<List<String>> table = Csv.parse(csv);
		if (table.size() < 2) {
			throw new UnprocessableException(List.of(ErrorItem.field(null, "EMPTY", "The file has no rows")));
		}
		if (table.size() - 1 > MAX_ROWS) {
			throw new UnprocessableException(List.of(ErrorItem.field(null, "TOO_MANY_ROWS", "At most " + MAX_ROWS + " rows per file")));
		}
		List<String> header = table.get(0).stream().map(h -> normalize(h)).toList();
		List<Row> rows = new ArrayList<>();
		for (int i = 1; i < table.size(); i++) {
			Map<String, String> v = new HashMap<>();
			for (int c = 0; c < header.size() && c < table.get(i).size(); c++) {
				String cell = table.get(i).get(c).trim();
				if (!cell.isEmpty()) {
					v.put(header.get(c), cell);
				}
			}
			rows.add(new Row(i, v));
		}
		Lookups lk = new Lookups();
		List<Map<String, Object>> errors = new ArrayList<>();
		Map<Integer, Map<String, Object>> bodies = new LinkedHashMap<>();
		Set<String> seen = new HashSet<>();
		for (Row r : rows) {
			int before = errors.size();
			Map<String, Object> body = sw ? software(r, lk, errors) : hardware(r, lk, errors, seen);
			if (errors.size() == before && body != null) {
				bodies.put(r.number(), body);
			}
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("rows", rows.size());
		out.put("valid", bodies.size());
		out.put("errors", errors);
		out.put("dryRun", dryRun);
		if (dryRun) {
			return out;
		}
		List<Integer> numbers = new ArrayList<>(bodies.keySet());
		int created = 0;
		for (int from = 0; from < numbers.size(); from += CHUNK) {
			List<Integer> chunk = numbers.subList(from, Math.min(numbers.size(), from + CHUNK));
			for (int n : chunk) {
				try {
					tx.executeWithoutResult(t -> {
						if (sw) {
							licenses.create(bodies.get(n), true);
						}
						else {
							hardware.create(bodies.get(n), true);
						}
					});
					created++;
				}
				catch (RuntimeException ex) {
					errors.add(error(n, null, "ROW_FAILED", ex.getMessage()));
				}
			}
		}
		out.put("created", created);
		out.put("errors", errors);
		return out;
	}

	// ------------------------------------------------------------------ hardware row

	private Map<String, Object> hardware(Row r, Lookups lk, List<Map<String, Object>> errors, Set<String> seen) {
		Map<String, String> v = r.v();
		Map<String, Object> b = new LinkedHashMap<>();
		UUID type = lookup(lk.hwTypes(), v.get("type"), r, "Type", true, errors);
		b.put("typeId", type);
		require(v, r, "model", "Model", errors);
		require(v, r, "serial", "Serial", errors);
		b.put("model", v.get("model"));
		b.put("serial", v.get("serial"));
		if (v.get("serial") != null && type != null && !seen.add(type + "|" + v.get("serial").toLowerCase(Locale.ROOT))) {
			errors.add(error(r.number(), "Serial", "SERIAL_TAKEN", "The same serial appears twice in the file"));
		}
		UUID ownership = v.get("ownership") == null ? lk.hwOwnership().get("company") : lookup(lk.hwOwnership(), v.get("ownership"), r, "Ownership", true, errors);
		b.put("ownershipId", ownership);
		UUID status = v.get("status") == null ? null : lookup(lk.hwStatuses(), v.get("status"), r, "Status", true, errors);
		b.put("statusId", status);
		if (v.get("location") != null) {
			b.put("locationId", lookup(lk.locations(), v.get("location"), r, "Location", true, errors));
		}
		if (v.get("tag") != null) {
			b.put("tag", v.get("tag"));
		}
		b.put("purchasedOn", date(v.get("purchase date"), r, "Purchase date", errors));
		b.put("warrantyUntil", date(v.get("warranty until"), r, "Warranty until", errors));
		b.put("purchaseCost", paise(v.get("cost"), r, "Cost", errors));
		String assignee = v.get("assigned to");
		if (assignee != null) {
			UUID e = lk.employees().get(assignee.toLowerCase(Locale.ROOT));
			if (e == null) {
				errors.add(error(r.number(), "Assigned to", "NOT_FOUND", "No employee with code '" + assignee + "'"));
			}
			b.put("employeeId", e);
			b.put("statusId", lk.assignedStatus());
		}
		Map<String, Object> specValues = new LinkedHashMap<>();
		Map<String, String> labelToKey = type == null ? Map.of() : lk.specIndex().getOrDefault(lk.hwTypeNames().getOrDefault(type, "").toLowerCase(Locale.ROOT), Map.of());
		Set<String> known = Set.of("tag", "type", "model", "serial", "status", "ownership", "location", "purchase date", "cost", "warranty until", "assigned to", "since");
		v.forEach((k, val) -> {
			if (!known.contains(k)) {
				specValues.put(labelToKey.getOrDefault(k, k), val);
			}
		});
		b.put("specs", specValues);
		return b;
	}

	private Map<String, Object> software(Row r, Lookups lk, List<Map<String, Object>> errors) {
		Map<String, String> v = r.v();
		Map<String, Object> b = new LinkedHashMap<>();
		require(v, r, "application", "Application", errors);
		b.put("name", v.get("application"));
		b.put("vendor", v.get("vendor"));
		b.put("categoryId", lookup(lk.swCategories(), v.get("category"), r, "Category", true, errors));
		b.put("typeId", lookup(lk.swTypes(), v.get("license type"), r, "License type", true, errors));
		if (v.get("seats") != null) {
			if (!v.get("seats").matches("\\d{1,6}")) {
				errors.add(error(r.number(), "Seats", "INVALID_FORMAT", "Seats is a whole number"));
			}
			else {
				b.put("seats", Integer.parseInt(v.get("seats")));
			}
		}
		b.put("costPerSeat", paise(v.get("cost per seat"), r, "Cost per seat", errors));
		b.put("termStart", date(v.get("start"), r, "Start", errors));
		b.put("renewsOn", date(v.get("renews"), r, "Renews", errors));
		b.put("autoRenew", v.get("auto-renew") != null && v.get("auto-renew").matches("(?i)y|yes|true|1"));
		String owner = v.get("owner");
		UUID ownerId = owner == null ? null : lk.employees().get(owner.toLowerCase(Locale.ROOT));
		if (ownerId == null) {
			errors.add(error(r.number(), "Owner", owner == null ? "REQUIRED" : "NOT_FOUND", owner == null ? "Owner is required" : "No employee with code '" + owner + "'"));
		}
		b.put("ownerId", ownerId);
		List<UUID> holders = new ArrayList<>();
		if (v.get("holders") != null) {
			for (String code : v.get("holders").split("[;|]")) {
				UUID h = lk.employees().get(code.trim().toLowerCase(Locale.ROOT));
				if (h == null) {
					errors.add(error(r.number(), "Holders", "NOT_FOUND", "No employee with code '" + code.trim() + "'"));
				}
				else {
					holders.add(h);
				}
			}
		}
		b.put("holders", holders);
		return b;
	}

	// ------------------------------------------------------------------ helpers

	private static void require(Map<String, String> v, Row r, String key, String column, List<Map<String, Object>> errors) {
		if (!v.containsKey(key)) {
			errors.add(error(r.number(), column, "REQUIRED", column + " is required"));
		}
	}

	private static UUID lookup(Map<String, UUID> map, String name, Row r, String column, boolean required, List<Map<String, Object>> errors) {
		if (name == null) {
			if (required) {
				errors.add(error(r.number(), column, "REQUIRED", column + " is required"));
			}
			return null;
		}
		UUID id = map.get(name.trim().toLowerCase(Locale.ROOT));
		if (id == null) {
			errors.add(error(r.number(), column, "NOT_FOUND", "'" + name + "' is not set up in the configuration"));
		}
		return id;
	}

	private static LocalDate date(String s, Row r, String column, List<Map<String, Object>> errors) {
		if (s == null) {
			return null;
		}
		try {
			return LocalDate.parse(s);
		}
		catch (DateTimeParseException ex) {
			errors.add(error(r.number(), column, "INVALID_FORMAT", "Use the date format yyyy-mm-dd"));
			return null;
		}
	}

	/** Rupees (with optional decimals) to paise. */
	private static Long paise(String s, Row r, String column, List<Map<String, Object>> errors) {
		if (s == null) {
			return null;
		}
		try {
			return new BigDecimal(s.replace(",", "").replace("₹", "").trim()).multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).longValueExact();
		}
		catch (NumberFormatException | ArithmeticException ex) {
			errors.add(error(r.number(), column, "INVALID_FORMAT", "Enter the amount in rupees"));
			return null;
		}
	}

	private static Map<String, Object> error(int row, String field, String code, String message) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("row", row);
		m.put("field", field);
		m.put("code", code);
		m.put("message", message);
		return m;
	}

	private final class Lookups {

		private Map<String, UUID> hwTypes;
		private Map<String, UUID> hwOwnership;
		private Map<String, UUID> hwStatuses;
		private Map<String, UUID> locations;
		private Map<String, UUID> employees;
		private Map<String, UUID> swCategories;
		private Map<String, UUID> swTypes;

		private Map<String, UUID> load(String sql) {
			Map<String, UUID> m = new HashMap<>();
			jdbc.sql(sql).param("org", currentUser.orgId()).query((rs, i) -> {
				m.putIfAbsent(rs.getString(2).toLowerCase(Locale.ROOT), rs.getObject(1, UUID.class));
				return 0;
			}).list();
			return m;
		}

		Map<String, UUID> hwTypes() {
			return hwTypes != null ? hwTypes : (hwTypes = load("select id, name from asset_type where org_id = :org and kind = 'HARDWARE' and active"));
		}

		Map<String, UUID> hwOwnership() {
			return hwOwnership != null ? hwOwnership : (hwOwnership = load("select id, name from asset_ownership where org_id = :org and active"));
		}

		Map<String, UUID> hwStatuses() {
			return hwStatuses != null ? hwStatuses : (hwStatuses = load("select id, name from asset_status where org_id = :org and kind = 'HARDWARE' and active"));
		}

		UUID assignedStatus() {
			return jdbc.sql("select id from asset_status where org_id = :org and kind = 'HARDWARE' and system_key = 'ASSIGNED'").param("org", currentUser.orgId()).query(UUID.class).optional().orElse(null);
		}

		Map<String, UUID> locations() {
			return locations != null ? locations : (locations = load("select id, name from location where org_id = :org and active"));
		}

		Map<String, UUID> employees() {
			return employees != null ? employees : (employees = load("select id, employee_code from employee where org_id = :org and archived_at is null and status <> 'EXITED'"));
		}

		Map<String, UUID> swCategories() {
			return swCategories != null ? swCategories : (swCategories = load("select id, name from asset_category where org_id = :org and kind = 'SOFTWARE' and active"));
		}

		Map<String, UUID> swTypes() {
			return swTypes != null ? swTypes : (swTypes = load("select id, name from asset_type where org_id = :org and kind = 'SOFTWARE' and active"));
		}

		private Map<String, Map<String, String>> specIndex;
		private Map<UUID, String> hwTypeNames;

		Map<String, Map<String, String>> specIndex() {
			if (specIndex == null) {
				specIndex = new HashMap<>();
				specs.fields("asset_hardware", null).forEach(d -> specIndex.computeIfAbsent(d.sectionKey(), k -> new HashMap<>()).put(d.label().toLowerCase(Locale.ROOT), d.key()));
			}
			return specIndex;
		}

		Map<UUID, String> hwTypeNames() {
			if (hwTypeNames == null) {
				hwTypeNames = new HashMap<>();
				hwTypes().forEach((name, id) -> hwTypeNames.put(id, name));
			}
			return hwTypeNames;
		}
	}
}
