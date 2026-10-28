package com.ivoyant.hrms.assets.service;

import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.ErrorItem;
import com.ivoyant.hrms.common.web.UnprocessableException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Let AI fill this in": reads an invoice, order or renewal email and maps what it finds onto the wizard's fields, using only types,
 * categories and ownership kinds that exist in the configuration. This version uses text rules; anything it cannot place is listed
 * as unmatched. Nothing is saved.
 */
@Service
public class AssetExtractService {

	private static final Pattern SERIAL = Pattern.compile("(?i)(?:serial(?:\\s*(?:no\\.?|number|#))?|s/n|sn)\\s*[:#-]?\\s*([A-Z0-9][A-Z0-9-]{4,})");
	private static final Pattern MONEY = Pattern.compile("(?i)(?:₹|rs\\.?|inr)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)");
	private static final Pattern INVOICE = Pattern.compile("(?i)invoice\\s*(?:no\\.?|number|#)\\s*[:\\-]?\\s*([A-Za-z0-9/\\-]+)");
	private static final Pattern VENDOR = Pattern.compile("(?im)^\\s*(?:vendor|supplier|sold by|seller)\\s*[:\\-]\\s*(.+)$");
	private static final Pattern YEARS = Pattern.compile("(?i)(\\d{1,2})\\s*[- ]?\\s*(?:year|yr)s?\\s*(?:on-?site\\s*)?(?:warranty|prosupport|support)");
	private static final Pattern DATE_WORDS = Pattern.compile("(\\d{1,2})[ \\-]([A-Za-z]{3,9})[ ,\\-]+(\\d{4})");
	private static final Pattern DATE_ISO = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");
	private static final Pattern DATE_DMY = Pattern.compile("(\\d{1,2})[/.](\\d{1,2})[/.](\\d{4})");
	private static final Map<String, String> HW_ALIASES = Map.ofEntries(Map.entry("macbook", "laptop"), Map.entry("notebook", "laptop"), Map.entry("thinkpad", "laptop"),
			Map.entry("latitude", "laptop"), Map.entry("elitebook", "laptop"), Map.entry("display", "monitor"), Map.entry("iphone", "phone"), Map.entry("mobile", "phone"),
			Map.entry("smartphone", "phone"), Map.entry("workstation", "desktop"), Map.entry("headphone", "headset"));
	private static final Map<String, String> SW_CATEGORIES = Map.ofEntries(Map.entry("figma", "Design"), Map.entry("adobe", "Design"), Map.entry("canva", "Design"),
			Map.entry("slack", "Communication"), Map.entry("zoom", "Communication"), Map.entry("teams", "Communication"), Map.entry("github", "Development"),
			Map.entry("jira", "Development"), Map.entry("gitlab", "Development"), Map.entry("jetbrains", "Development"), Map.entry("office", "Productivity"),
			Map.entry("microsoft 365", "Productivity"), Map.entry("google workspace", "Productivity"), Map.entry("notion", "Productivity"), Map.entry("okta", "Security"),
			Map.entry("1password", "Security"), Map.entry("tally", "Finance"), Map.entry("quickbooks", "Finance"));

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;

	AssetExtractService(JdbcClient jdbc, CurrentUser currentUser) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
	}

	@Transactional(readOnly = true)
	public Map<String, Object> extract(String kind, String text) {
		if (text == null || text.isBlank()) {
			throw new UnprocessableException(List.of(ErrorItem.field("text", "REQUIRED", "Paste the invoice or order text")));
		}
		if (text.length() > 200_000) {
			throw new UnprocessableException(List.of(ErrorItem.field("text", "TOO_LARGE", "That is too much text")));
		}
		return "SOFTWARE".equalsIgnoreCase(kind) ? software(text) : hardware(text);
	}

	private Map<String, Object> hardware(String text) {
		Map<String, Object> fields = new LinkedHashMap<>();
		List<Map<String, Object>> mapped = new ArrayList<>();
		List<String> unmatched = new ArrayList<>();
		String lower = text.toLowerCase(Locale.ROOT);
		// type: a configured type named in the text, or an alias of one
		Map<UUID, String> types = new LinkedHashMap<>();
		jdbc.sql("select id, name from asset_type where org_id = :org and kind = 'HARDWARE' and active").param("org", currentUser.orgId())
				.query((rs, i) -> types.put(rs.getObject(1, UUID.class), rs.getString(2))).list();
		UUID typeId = null;
		for (Map.Entry<UUID, String> t : types.entrySet()) {
			if (lower.contains(t.getValue().toLowerCase(Locale.ROOT))) {
				typeId = t.getKey();
				break;
			}
		}
		if (typeId == null) {
			for (Map.Entry<String, String> a : HW_ALIASES.entrySet()) {
				if (lower.contains(a.getKey())) {
					for (Map.Entry<UUID, String> t : types.entrySet()) {
						if (t.getValue().equalsIgnoreCase(a.getValue())) {
							typeId = t.getKey();
						}
					}
					if (typeId != null) {
						break;
					}
				}
			}
		}
		if (typeId != null) {
			fields.put("typeId", typeId);
			mapped.add(map("Details", "Type", types.get(typeId)));
			jdbc.sql("select category_id from asset_type where id = :id").param("id", typeId).query(UUID.class).optional().ifPresent(c -> fields.put("categoryId", c));
		}
		String model = firstMatch(text, "(?im)^\\s*(?:model|description|item|product)\\s*[:\\-]\\s*(.+)$");
		if (model == null) {
			for (String line : text.split("\\R")) {
				if (line.matches("(?i).*\\b(dell|hp|lenovo|apple|asus|acer|samsung|lg|logitech|cisco|microsoft|jabra|poly|tp-link|ubiquiti)\\b.*") && line.length() < 120) {
					model = line.replaceAll("(?i)^(item|description)\\s*[:\\-]\\s*", "").trim();
					break;
				}
			}
		}
		if (model != null) {
			fields.put("model", model);
			mapped.add(map("Details", "Make & model", model));
		}
		Matcher s = SERIAL.matcher(text);
		if (s.find()) {
			fields.put("serial", s.group(1));
			mapped.add(map("Details", "Serial number", s.group(1)));
		}
		Long cost = amount(text);
		if (cost != null) {
			fields.put("purchaseCost", cost);
			mapped.add(map("Details", "Purchase cost", "₹" + cost / 100));
		}
		LocalDate purchased = dateAfter(text, "(?i)(?:invoice date|purchase date|order date|date of purchase|dated)");
		if (purchased != null) {
			fields.put("purchasedOn", purchased);
			mapped.add(map("Details", "Purchase date", purchased));
		}
		LocalDate warranty = dateAfter(text, "(?i)(?:warranty (?:until|till|ends?|valid till|expiry)|valid (?:until|till))");
		if (warranty == null && purchased != null) {
			Matcher y = YEARS.matcher(text);
			if (y.find()) {
				warranty = purchased.plusYears(Integer.parseInt(y.group(1))).minusDays(1);
			}
		}
		if (warranty != null) {
			fields.put("warrantyUntil", warranty);
			mapped.add(map("Details", "Warranty until", warranty));
		}
		String vendor = firstMatch(text, VENDOR);
		if (vendor != null) {
			fields.put("vendor", vendor);
			mapped.add(map("Ownership", "Vendor", vendor));
		}
		Matcher inv = INVOICE.matcher(text);
		if (inv.find()) {
			fields.put("invoiceNo", inv.group(1));
			mapped.add(map("Ownership", "Invoice number", inv.group(1)));
		}
		String ownershipName = lower.contains("lease") ? "Leased" : lower.contains("client") ? "Client" : "Company";
		jdbc.sql("select id from asset_ownership where org_id = :org and active and lower(name) = lower(:n)").param("org", currentUser.orgId()).param("n", ownershipName).query(UUID.class)
				.optional().ifPresent(o -> fields.put("ownershipId", o));
		mapped.add(map("Ownership", "Ownership type", ownershipName));
		for (String line : text.split("\\R")) {
			String l = line.trim();
			if (l.length() > 3 && l.length() < 100 && l.matches("(?i).*(support|service|installation|shipping|insurance).*") && unmatched.size() < 5) {
				unmatched.add(l);
			}
		}
		return result(fields, mapped, unmatched);
	}

	private Map<String, Object> software(String text) {
		Map<String, Object> fields = new LinkedHashMap<>();
		List<Map<String, Object>> mapped = new ArrayList<>();
		List<String> unmatched = new ArrayList<>();
		String lower = text.toLowerCase(Locale.ROOT);
		String name = firstMatch(text, "(?im)^\\s*(?:application|product|software|subscription|plan)\\s*[:\\-]\\s*(.+)$");
		if (name == null) {
			for (String known : SW_CATEGORIES.keySet()) {
				if (lower.contains(known)) {
					name = known.substring(0, 1).toUpperCase(Locale.ROOT) + known.substring(1);
					break;
				}
			}
		}
		if (name != null) {
			fields.put("name", name);
			mapped.add(map("Application", "Application", name));
		}
		String vendor = firstMatch(text, VENDOR);
		if (vendor != null) {
			fields.put("vendor", vendor);
			mapped.add(map("Application", "Vendor", vendor));
		}
		String categoryName = null;
		if (name != null) {
			for (Map.Entry<String, String> c : SW_CATEGORIES.entrySet()) {
				if (name.toLowerCase(Locale.ROOT).contains(c.getKey())) {
					categoryName = c.getValue();
				}
			}
		}
		if (categoryName != null) {
			String cn = categoryName;
			jdbc.sql("select id from asset_category where org_id = :org and kind = 'SOFTWARE' and active and lower(name) = lower(:n)").param("org", currentUser.orgId()).param("n", cn)
					.query(UUID.class).optional().ifPresent(c -> fields.put("categoryId", c));
			mapped.add(map("Application", "Category", cn));
		}
		String typeName = lower.contains("perpetual") ? "Perpetual" : lower.contains("open source") ? "Open source" : lower.contains("trial") ? "Trial"
				: lower.contains("enterprise") ? "Enterprise" : lower.contains("subscription") || lower.contains("per month") || lower.contains("monthly") || lower.contains("per user") ? "Subscription" : null;
		if (typeName != null) {
			String tn = typeName;
			jdbc.sql("select id from asset_type where org_id = :org and kind = 'SOFTWARE' and active and lower(name) = lower(:n)").param("org", currentUser.orgId()).param("n", tn)
					.query(UUID.class).optional().ifPresent(t -> fields.put("typeId", t));
			mapped.add(map("Application", "License type", tn));
		}
		Matcher seats = Pattern.compile("(?i)(\\d{1,5})\\s*(?:seats?|users?|licen[cs]es?)").matcher(text);
		if (seats.find()) {
			fields.put("seats", Integer.parseInt(seats.group(1)));
			mapped.add(map("Seats & billing", "Seats", seats.group(1)));
		}
		Long price = amount(text);
		if (price != null) {
			fields.put("costPerSeat", price);
			mapped.add(map("Seats & billing", "Price per seat", "₹" + price / 100));
		}
		LocalDate start = dateAfter(text, "(?i)(?:start date|contract start|effective|commence\\w*|order date|invoice date)");
		if (start != null) {
			fields.put("termStart", start);
			mapped.add(map("Seats & billing", "Contract start", start));
		}
		LocalDate renews = dateAfter(text, "(?i)(?:renew(?:al|s)?(?: date| on)?|valid (?:until|till)|expires?(?: on)?|end date)");
		if (renews != null) {
			fields.put("renewsOn", renews);
			mapped.add(map("Seats & billing", "Renewal date", renews));
		}
		if (lower.contains("auto-renew") || lower.contains("auto renew") || lower.contains("automatically renew")) {
			fields.put("autoRenew", true);
			mapped.add(map("Owner & status", "Auto-renew", "On"));
		}
		return result(fields, mapped, unmatched);
	}

	private Map<String, Object> result(Map<String, Object> fields, List<Map<String, Object>> mapped, List<String> unmatched) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("extractionId", UUID.randomUUID());
		out.put("fields", fields);
		out.put("mapped", mapped);
		out.put("unmatched", unmatched);
		return out;
	}

	private static Map<String, Object> map(String group, String key, Object value) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("group", group);
		m.put("key", key);
		m.put("value", String.valueOf(value));
		return m;
	}

	private static String firstMatch(String text, String regex) {
		return firstMatch(text, Pattern.compile(regex));
	}

	private static String firstMatch(String text, Pattern p) {
		Matcher m = p.matcher(text);
		return m.find() ? m.group(m.groupCount()).trim() : null;
	}

	/** The biggest amount in rupees, as paise (the invoice total is normally the largest figure). */
	private static Long amount(String text) {
		Matcher m = MONEY.matcher(text);
		long best = -1;
		while (m.find()) {
			try {
				long paise = Math.round(Double.parseDouble(m.group(1).replace(",", "")) * 100);
				best = Math.max(best, paise);
			}
			catch (NumberFormatException ignored) {
				// skip a figure that is not a number
			}
		}
		return best < 0 ? null : best;
	}

	private static LocalDate dateAfter(String text, String label) {
		Matcher l = Pattern.compile(label + "[^0-9A-Za-z]{0,12}(.{0,24})").matcher(text);
		while (l.find()) {
			LocalDate d = parseDate(l.group(1));
			if (d != null) {
				return d;
			}
		}
		return null;
	}

	private static LocalDate parseDate(String s) {
		Matcher iso = DATE_ISO.matcher(s);
		if (iso.find()) {
			return LocalDate.of(Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(2)), Integer.parseInt(iso.group(3)));
		}
		Matcher dmy = DATE_DMY.matcher(s);
		if (dmy.find()) {
			try {
				return LocalDate.of(Integer.parseInt(dmy.group(3)), Integer.parseInt(dmy.group(2)), Integer.parseInt(dmy.group(1)));
			}
			catch (java.time.DateTimeException ex) {
				return null;
			}
		}
		Matcher w = DATE_WORDS.matcher(s);
		if (w.find()) {
			String month = w.group(2).substring(0, 3);
			try {
				return LocalDate.parse(w.group(1) + " " + month + " " + w.group(3), DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH));
			}
			catch (DateTimeParseException ex) {
				return null;
			}
		}
		return null;
	}
}
