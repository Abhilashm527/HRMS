package com.ivoyant.hrms.config.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Column definitions of every lookup list (SETTINGS_CONFIG_API.md §6.2): what the settings screens show and what a
 * save is validated against. Column types: text, mono (code, shown monospace), number, toggle, select, color, icon.
 */
public final class ListSpecs {

	public record Col(String key, String label, String type, List<String> options, String ref, String refBy,
			boolean required, boolean unique) {

		Col req() {
			return new Col(key, label, type, options, ref, refBy, true, unique);
		}

		Col uniq() {
			return new Col(key, label, type, options, ref, refBy, required, true);
		}
	}

	public record Spec(String module, String key, String label, List<Col> columns) {

		public Col column(String key) {
			return columns.stream().filter(c -> c.key().equals(key)).findFirst().orElse(null);
		}
	}

	public static final List<String> COLORS = List.of("0", "1", "2", "3", "4", "5");
	public static final List<String> ICONS = List.of("laptop", "monitor", "phone", "headphones", "globe", "box", "apps",
			"file", "briefcase", "users", "wallet");
	private static final List<String> FIELD_TYPES = List.of("Text", "Long text", "Number", "Date", "Dropdown", "Yes / No",
			"Email", "Phone", "Department", "Employee", "Location", "Employment type");

	private static final Map<String, Map<String, Spec>> SPECS = new LinkedHashMap<>();

	static {
		module("onboarding",
				spec("departments", "Departments", text("name", "Name").req(), mono("code", "Code").req().uniq(),
						ref("head", "Head", "employee", "id"), ref("parent", "Parent", "departments", "id"),
						text("cc", "Cost centre"), ref("loc", "Location", "location", "id"), toggle("active", "Active")),
				spec("designations", "Positions", text("name", "Name").req(), mono("grade", "Grade").uniq(),
						number("lvl", "Level"), select("track", "Track", "Executive", "Management", "Individual contributor"),
						number("bmin", "Band min (₹ L)"), number("bmax", "Band max (₹ L)"),
						ref("rep", "Reports to", "designations", "id"), number("minExp", "Min experience"),
						toggle("appr", "Approves"), ref("jl", "Level & grade", "levels", "id"), toggle("active", "Active")),
				spec("titles", "Titles", text("name", "Name").req(), mono("code", "Code").uniq(),
						ref("pos", "Position", "designations", "id"), ref("dept", "Department", "departments", "id"),
						text("desc", "Description"), toggle("active", "Active")),
				spec("cfields", "Custom fields", select("ent", "Applies to", "Employee", "Department", "Position", "Title"),
						text("name", "Name").req(), mono("key", "Key").uniq(),
						select("ftype", "Type", "Text", "Number", "Date", "Dropdown", "Multi-select", "Yes / No", "Email", "Phone"),
						text("opts", "Options"), toggle("req", "Required"), toggle("emp", "Employee sees"),
						select("acc", "Visible to", "Everyone", "Managers & HR", "Admins only"), toggle("mask", "Masked"),
						toggle("active", "Active")),
				spec("levels", "Levels & grades", mono("code", "Code").req().uniq(), text("name", "Name").req(),
						number("min", "Min years"), number("max", "Max years"), toggle("appr", "Approves"),
						toggle("active", "Active")),
				spec("teams", "Teams", text("name", "Name").req(), ref("dept", "Department", "departments", "id"),
						ref("lead", "Lead", "employee", "id"), toggle("active", "Active")),
				spec("projects", "Projects", text("name", "Name").req(), mono("code", "Code").req().uniq(),
						text("client", "Client"), ref("owner", "Owner", "employee", "id"),
						select("status", "Status", "Planned", "Active", "On hold", "Completed"), toggle("active", "Active")),
				spec("emptypes", "Employment types", text("name", "Name").req(), mono("code", "Code").uniq(),
						number("prob", "Probation (months)"), number("notice", "Notice (days)"), toggle("leave", "Leave"),
						toggle("payroll", "Payroll"), toggle("active", "Active")),
				spec("steps", "Hire form steps", number("order", "Order").req().uniq(), text("name", "Name").req(),
						text("desc", "Description"), select("kind", "Kind", "Fields", "Microsoft 365", "Documents & invite"),
						toggle("active", "Active"), toggle("lock", "Locked")),
				spec("fields", "Hire form fields", ref("scope", "Step", "steps", "name"), text("name", "Name").req(),
						select("ftype", "Type", FIELD_TYPES.toArray(String[]::new)), text("opts", "Options"),
						toggle("req", "Required"), toggle("active", "Active"), mono("key", "Key").uniq(),
						toggle("lock", "Locked")),
				spec("m365", "Microsoft 365 rules", text("name", "Name").req(),
						ref("etype", "Employment type", "emptypes", "id"),
						select("fmt", "Username format", "first.last", "firstlast", "f.last", "first"),
						select("license", "Licence", "Microsoft 365 Business Basic", "Microsoft 365 Business Standard",
								"Microsoft 365 Business Premium", "Microsoft 365 E3"),
						text("groups", "Groups"), toggle("reset", "Force password reset"), toggle("active", "Active")),
				spec("invites", "Invite emails", text("name", "Name").req(),
						ref("etype", "Employment type", "emptypes", "id"), text("subject", "Subject"),
						text("body", "Body"), select("due", "Due (days)", "1", "2", "3", "5", "7"),
						select("remind", "Remind (days)", "1", "2", "3", "5", "Never"), text("cc", "Cc"),
						toggle("active", "Active")),
				spec("portal", "Employee portal fields", text("section", "Section"), text("name", "Name").req(),
						select("ftype", "Type", "Text", "Number", "Date", "Phone", "Email", "Dropdown", "Textarea", "PAN", "IFSC",
								"Aadhaar", "Account number"),
						text("opts", "Options"), toggle("req", "Required"), toggle("active", "Active"),
						mono("key", "Key").uniq()),
				spec("docs", "Documents", text("name", "Name").req(), text("desc", "Description"), text("group", "Group"),
						ref("applies", "Applies to", "emptypes", "id"), toggle("req", "Required"),
						toggle("active", "Active"), mono("key", "Key").uniq()),
				spec("checklist", "Checklist tasks", text("name", "Name").req(),
						select("phase", "Phase", "Before day 1", "Day 1", "First 30 days"),
						select("owner", "Owner", "Candidate", "People", "IT", "Manager", "Operations", "Payroll", "Finance"),
						toggle("active", "Active")));
		module("hardware",
				statuses(), categories(),
				spec("types", "Types", text("name", "Name").req(), ref("cat", "Category", "categories", "id"),
						icon("icon", "Icon"), number("age", "Max age (years)"), toggle("active", "Active")),
				spec("ownership", "Ownership", text("name", "Name").req(), text("fields", "Fields on create"),
						toggle("returnReq", "Return required"), toggle("active", "Active")),
				spec("idformat", "ID format", ref("name", "Ownership", "ownership", "name").req(),
						mono("fmt", "Format").req(), toggle("active", "Active")),
				spec("fields", "Fields", ref("scope", "Asset type", "types", "name"), text("name", "Name").req(),
						select("ftype", "Type", "Text", "Number", "Date", "Dropdown", "Yes / No"), text("opts", "Options"),
						toggle("req", "Required"), toggle("active", "Active")),
				alerts());
		module("software",
				statuses(), categories(),
				spec("types", "Types", text("name", "Name").req(),
						select("billing", "Billing", "Monthly", "Annual", "One-time", "Free"),
						select("unit", "Unit", "Per seat", "Per device", "Site-wide"), toggle("active", "Active")),
				spec("idformat", "ID format", text("name", "Licence type").req(), mono("fmt", "Format").req(),
						toggle("active", "Active")),
				spec("fields", "Fields", ref("scope", "Category", "categories", "name"), text("name", "Name").req(),
						select("ftype", "Type", "Text", "Number", "Date", "Dropdown", "Yes / No"), text("opts", "Options"),
						toggle("req", "Required"), toggle("active", "Active")),
				alerts());
	}

	private ListSpecs() {
	}

	public static Map<String, Spec> module(String module) {
		return SPECS.get(module);
	}

	public static boolean knownModule(String module) {
		return SPECS.containsKey(module);
	}

	private static Spec statuses() {
		return spec("statuses", "Statuses", text("name", "Name").req(), color("color", "Colour"),
				toggle("assignable", "Assignable"), toggle("active", "Active"));
	}

	private static Spec categories() {
		return spec("categories", "Categories", text("name", "Name").req(), icon("icon", "Icon"),
				toggle("active", "Active"));
	}

	private static Spec alerts() {
		return spec("alerts", "Alerts", text("name", "Name").req(),
				select("days", "Days before", "7", "10", "15", "30", "45", "60", "90"), text("event", "Event"),
				toggle("inapp", "In-app"), toggle("email", "Email"));
	}

	private static void module(String module, Spec... specs) {
		Map<String, Spec> byKey = new LinkedHashMap<>();
		for (Spec s : specs) {
			byKey.put(s.key(), new Spec(module, s.key(), s.label(), s.columns()));
		}
		SPECS.put(module, byKey);
	}

	private static Spec spec(String key, String label, Col... cols) {
		return new Spec(null, key, label, List.of(cols));
	}

	private static Col text(String key, String label) {
		return new Col(key, label, "text", List.of(), null, null, false, false);
	}

	private static Col mono(String key, String label) {
		return new Col(key, label, "mono", List.of(), null, null, false, false);
	}

	private static Col number(String key, String label) {
		return new Col(key, label, "number", List.of(), null, null, false, false);
	}

	private static Col toggle(String key, String label) {
		return new Col(key, label, "toggle", List.of(), null, null, false, false);
	}

	private static Col color(String key, String label) {
		return new Col(key, label, "color", COLORS, null, null, false, false);
	}

	private static Col icon(String key, String label) {
		return new Col(key, label, "icon", ICONS, null, null, false, false);
	}

	private static Col select(String key, String label, String... options) {
		return new Col(key, label, "select", List.of(options), null, null, false, false);
	}

	private static Col ref(String key, String label, String ref, String by) {
		return new Col(key, label, "select", new ArrayList<>(), ref, by, false, false);
	}
}
