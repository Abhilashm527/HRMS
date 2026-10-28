package com.ivoyant.hrms.iam.auth;

import com.ivoyant.hrms.common.config.HrmsProperties;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Security events are always written, even when Organization → Audit log is off. */
@Component
public class SecurityAudit {

	private final JdbcClient jdbc;
	private final HrmsProperties props;
	private final ObjectMapper json;

	SecurityAudit(JdbcClient jdbc, HrmsProperties props, ObjectMapper json) {
		this.jdbc = jdbc;
		this.props = props;
		this.json = json;
	}

	public void log(String event, UUID employeeId, String subject, Map<String, Object> details) {
		jdbc.sql("""
				insert into audit_log (org_id, actor_id, module, entity, entity_id, action, after) values (:org, :actor, 'auth', 'Security', :subject, :action, cast(:after as jsonb))""")
				.param("org", props.defaultOrgId()).param("actor", employeeId, java.sql.Types.OTHER).param("subject", subject).param("action", event)
				.param("after", details == null ? null : json.writeValueAsString(details)).update();
	}
}
