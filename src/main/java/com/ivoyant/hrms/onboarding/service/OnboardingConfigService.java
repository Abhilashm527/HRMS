package com.ivoyant.hrms.onboarding.service;

import com.ivoyant.hrms.common.security.CurrentUser;
import com.ivoyant.hrms.onboarding.OnboardingConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

/** The onboarding configuration as the hire flow uses it. */
@Service
class OnboardingConfigService implements OnboardingConfig {

	private static final Pattern TOKEN = Pattern.compile("\\{([A-Za-z]+)}");

	private final JdbcClient jdbc;
	private final CurrentUser currentUser;

	OnboardingConfigService(JdbcClient jdbc, CurrentUser currentUser) {
		this.jdbc = jdbc;
		this.currentUser = currentUser;
	}

	@Override
	@Transactional(readOnly = true)
	public List<StepView> hireSteps() {
		return jdbc.sql("select id, key, name, kind, sort, locked, config_section_id from onboarding_step where org_id = :org and active order by sort, name")
				.param("org", currentUser.orgId()).query((rs, i) -> {
					UUID section = rs.getObject(7, UUID.class);
					return new StepView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5), rs.getBoolean(6),
							section == null ? List.of() : fields(section));
				}).list();
	}

	private List<FieldView> fields(UUID sectionId) {
		return jdbc.sql("select key, label, type, required, options::text from config_field where section_id = :s and active and deleted_at is null and type <> 'HEADING' order by sort")
				.param("s", sectionId).query((rs, i) -> new FieldView(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBoolean(4), options(rs.getString(5))))
				.list();
	}

	private static List<String> options(String json) {
		List<String> out = new ArrayList<>();
		if (json != null) {
			Matcher m = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
			while (m.find()) {
				out.add(m.group(1));
			}
		}
		return out;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<M365Rule> m365For(UUID employmentTypeId) {
		return jdbc.sql("""
				select id, name, employment_type_id, upn_format, license_sku, array_to_string(array(select jsonb_array_elements_text(groups)), '|'), force_reset
				from m365_rule where org_id = :org and active and (employment_type_id = :t or employment_type_id is null)
				order by (employment_type_id is null), sort limit 1""").param("org", currentUser.orgId()).param("t", employmentTypeId, java.sql.Types.OTHER)
				.query((rs, i) -> new M365Rule(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5),
						rs.getString(6).isEmpty() ? List.of() : List.of(rs.getString(6).split("\\|")), rs.getBoolean(7))).optional();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<InviteTemplate> inviteFor(UUID employmentTypeId) {
		return jdbc.sql("""
				select id, name, employment_type_id, subject, body, due_days_before, remind_every_days, cc_manager from invite_template
				where org_id = :org and active and (employment_type_id = :t or employment_type_id is null)
				order by (employment_type_id is null), created_at limit 1""").param("org", currentUser.orgId()).param("t", employmentTypeId, java.sql.Types.OTHER)
				.query((rs, i) -> new InviteTemplate(rs.getObject(1, UUID.class), rs.getString(2), rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5),
						rs.getInt(6), (Integer) rs.getObject(7), rs.getBoolean(8))).optional();
	}

	@Override
	@Transactional(readOnly = true)
	public List<DocumentView> documentsFor(UUID employmentTypeId) {
		return jdbc.sql("select id, key, name, hint, doc_group, employment_type_id, required from onboarding_document where org_id = :org and active "
				+ "and (employment_type_id is null or employment_type_id = :t) order by sort, name").param("org", currentUser.orgId())
				.param("t", employmentTypeId, java.sql.Types.OTHER).query((rs, i) -> new DocumentView(rs.getObject(1, UUID.class), rs.getString(2),
						rs.getString(3), rs.getString(4), rs.getString(5), rs.getObject(6, UUID.class), rs.getBoolean(7))).list();
	}

	@Override
	@Transactional(readOnly = true)
	public List<TaskTemplate> checklist() {
		return jdbc.sql("select id, name, phase, owner from onboarding_task_template where org_id = :org and active order by case phase when 'BEFORE_DAY_1' then 1 when 'DAY_1' then 2 else 3 end, sort")
				.param("org", currentUser.orgId()).query((rs, i) -> new TaskTemplate(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4))).list();
	}

	@Override
	public RenderedInvite renderInvite(InviteTemplate template, Map<String, String> values) {
		return new RenderedInvite(fill(template.subject(), values), fill(template.body(), values));
	}

	/** Unknown or missing placeholders become empty text; every value is escaped, so a name can't inject markup. */
	static String fill(String text, Map<String, String> values) {
		Matcher m = TOKEN.matcher(text);
		StringBuilder out = new StringBuilder();
		while (m.find()) {
			String v = values.get(m.group(1).toLowerCase());
			m.appendReplacement(out, Matcher.quoteReplacement(v == null ? "" : HtmlUtils.htmlEscape(v)));
		}
		m.appendTail(out);
		return out.toString();
	}
}
