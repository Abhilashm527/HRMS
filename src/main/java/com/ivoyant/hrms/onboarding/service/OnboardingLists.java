package com.ivoyant.hrms.onboarding.service;

import com.ivoyant.hrms.common.forms.JdbcTableList;
import com.ivoyant.hrms.common.forms.JdbcTableList.Col;
import com.ivoyant.hrms.common.forms.JdbcTableList.Rules;
import com.ivoyant.hrms.common.forms.TableBackedList;
import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.common.web.ErrorItem;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Settings → Configuration → Onboarding configuration: hire form steps, Microsoft 365 rules, invite emails, documents and the checklist,
 * each in its own table. An employment type column holds the type's id (blank means "All types"). Whole-list rules (a default rule, a
 * default template, locked steps) and the allowed {tokens} are checked with the cell rules.
 */
@Configuration
class OnboardingLists {

	private static final Pattern TOKEN = Pattern.compile("\\{([A-Za-z]+)}");
	private static final Set<String> GROUP_TOKENS = Set.of("department", "location");
	private static final Set<String> INVITE_TOKENS = Set.of("first", "title", "doj", "location", "manager", "upn", "docs", "company");

	private static final String KIND_READ = "case t.kind when 'FIELDS' then 'Fields' when 'M365' then 'Microsoft 365' else 'Documents & invite' end";
	private static final String KIND_WRITE = "case cast(:kind as text) when 'Fields' then 'FIELDS' when 'Microsoft 365' then 'M365' else 'DOCS_INVITE' end";
	private static final String PHASE_READ = "case t.phase when 'BEFORE_DAY_1' then 'Before day 1' when 'DAY_1' then 'Day 1' else 'First 30 days' end";
	private static final String PHASE_WRITE = "case cast(:phase as text) when 'Before day 1' then 'BEFORE_DAY_1' when 'Day 1' then 'DAY_1' else 'FIRST_30_DAYS' end";
	private static final String SLUG = "lower(regexp_replace(cast(:name as text), '[^A-Za-z0-9]+', '_', 'g'))";

	private final JdbcClient jdbc;
	private final CurrentUser user;

	OnboardingLists(JdbcClient jdbc, CurrentUser user) {
		this.jdbc = jdbc;
		this.user = user;
	}

	private TableBackedList list(String key, int order, String table, String nameCol, List<Col> cols, Rules rules) {
		return new JdbcTableList(jdbc, user, "onboarding", key, order, table, null, Map.of(), "t.sort, t.name", nameCol, cols, rules);
	}

	@Bean
	TableBackedList onboardingSteps() {
		return list("steps", 100, "onboarding_step", "t.name",
				List.of(Col.integer("order", "sort"), Col.text("name", "name"), Col.text("desc", "description"),
						Col.sql("kind", "kind", KIND_READ, KIND_WRITE), Col.bool("active", "active"),
						Col.sql("lock", "locked", "t.locked", "false").once(), Col.hidden("key", SLUG)),
				(rows, all, errors) -> {
					// the steps that come built in (Personal, Job, Documents & invite) stay on, whatever the draft says
					List<String> locked = jdbc.sql("select id::text from onboarding_step where org_id = :org and locked").param("org", user.orgId())
							.query(String.class).list();
					for (String id : locked) {
						int at = indexOf(rows, id);
						if (at < 0) {
							errors.add(ErrorItem.cell("steps", 0, "name", "STEP_LOCKED: a built-in step can't be removed"));
						}
						else if (Boolean.FALSE.equals(rows.get(at).get("active"))) {
							errors.add(ErrorItem.cell("steps", at, "active", "STEP_LOCKED: " + rows.get(at).get("name") + " is built in and can't be switched off"));
						}
					}
				});
	}

	@Bean
	TableBackedList onboardingM365() {
		return list("m365", 110, "m365_rule", "t.name",
				List.of(Col.text("name", "name"), Col.uuid("etype", "employment_type_id"), Col.text("fmt", "upn_format"), Col.text("license", "license_sku"),
						Col.sql("groups", "groups", "coalesce(array_to_string(array(select jsonb_array_elements_text(t.groups)), ', '), '')",
								"to_jsonb(array(select trim(x) from unnest(string_to_array(coalesce(cast(:groups as text), ''), ',')) x where trim(x) <> ''))"),
						Col.bool("reset", "force_reset"), Col.bool("active", "active")),
				(rows, all, errors) -> {
					for (int i = 0; i < rows.size(); i++) {
						String bad = badToken(rows.get(i).get("groups"), GROUP_TOKENS);
						if (bad != null) {
							errors.add(ErrorItem.cell("m365", i, "groups", "BAD_TOKEN: {" + bad + "} isn't allowed here (use {department} or {location})"));
						}
					}
					if (rows.stream().noneMatch(r -> !Boolean.FALSE.equals(r.get("active")) && blank(r.get("etype")))) {
						errors.add(ErrorItem.cell("m365", Math.max(0, rows.size() - 1), "etype", "NEED_DEFAULT_RULE: keep one active rule for All types, so every hire gets an account"));
					}
				});
	}

	@Bean
	TableBackedList onboardingInvites() {
		return list("invites", 120, "invite_template", "t.name",
				List.of(Col.text("name", "name"), Col.uuid("etype", "employment_type_id"), Col.text("subject", "subject"), Col.text("body", "body"),
						Col.sql("due", "due_days_before", "cast(t.due_days_before as text)", "cast(cast(:due as text) as int)"),
						Col.sql("remind", "remind_every_days", "coalesce(cast(t.remind_every_days as text), 'Never')",
								"case when :remind is null or cast(:remind as text) = 'Never' then null else cast(cast(:remind as text) as int) end"),
						Col.sql("cc", "cc_manager", "case when t.cc_manager then 'Manager' else '' end",
								"(coalesce(lower(cast(:cc as text)), '') like '%manager%')"),
						Col.bool("active", "active")),
				(rows, all, errors) -> {
					for (int i = 0; i < rows.size(); i++) {
						for (String col : List.of("subject", "body")) {
							String bad = badToken(rows.get(i).get(col), INVITE_TOKENS);
							if (bad != null) {
								errors.add(ErrorItem.cell("invites", i, col, "BAD_TOKEN: {" + bad + "} isn't a known placeholder (use "
										+ "{first} {title} {doj} {location} {manager} {upn} {docs} {company})"));
							}
						}
					}
					if (rows.stream().noneMatch(r -> !Boolean.FALSE.equals(r.get("active")) && blank(r.get("etype")))) {
						errors.add(ErrorItem.cell("invites", Math.max(0, rows.size() - 1), "etype", "NEED_DEFAULT_TEMPLATE: keep one active email for All types"));
					}
				});
	}

	@Bean
	TableBackedList onboardingDocuments() {
		return list("docs", 130, "onboarding_document", "t.name",
				List.of(Col.text("name", "name"), Col.text("desc", "hint"),
						Col.sql("group", "doc_group", "t.doc_group", "coalesce(nullif(trim(cast(:group as text)), ''), 'Other')"),
						Col.uuid("applies", "employment_type_id"), Col.bool("req", "required"), Col.bool("active", "active"),
						Col.sql("key", "key", "t.key", "coalesce(nullif(" + SLUG.replace(":name", ":key") + ", ''), " + SLUG + ")").once()),
				null);
	}

	@Bean
	TableBackedList onboardingChecklist() {
		return list("checklist", 140, "onboarding_task_template", "t.name",
				List.of(Col.text("name", "name"), Col.sql("phase", "phase", PHASE_READ, PHASE_WRITE),
						Col.sql("owner", "owner", "case t.owner when 'IT' then 'IT' else initcap(lower(t.owner)) end", "upper(cast(:owner as text))"), Col.bool("active", "active")),
				null);
	}

	private static int indexOf(List<Map<String, Object>> rows, String id) {
		for (int i = 0; i < rows.size(); i++) {
			if (id.equalsIgnoreCase(String.valueOf(rows.get(i).get("id")))) {
				return i;
			}
		}
		return -1;
	}

	private static boolean blank(Object o) {
		return o == null || o.toString().isBlank();
	}

	/** The first {placeholder} that is not allowed, or null. */
	static String badToken(Object text, Set<String> allowed) {
		if (blank(text)) {
			return null;
		}
		Matcher m = TOKEN.matcher(text.toString());
		Set<String> seen = new HashSet<>();
		while (m.find()) {
			String t = m.group(1);
			if (!allowed.contains(t.toLowerCase(Locale.ROOT)) && seen.add(t)) {
				return t;
			}
		}
		return null;
	}
}
