package com.ivoyant.hrms.common.audit;

import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
class AuditLogWriter {

	private final JdbcClient jdbc;
	private final JsonMapper json;

	AuditLogWriter(JdbcClient jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	@EventListener
	void on(AuditEvent event) {
		jdbc.sql("""
				insert into audit_log (org_id, actor_id, module, entity, entity_id, action, before, after)
				values (:org, :actor, :module, :entity, :entityId, :action, cast(:before as jsonb), cast(:after as jsonb))
				""")
				.param("org", event.orgId())
				.param("actor", event.actorId())
				.param("module", event.module())
				.param("entity", event.entity())
				.param("entityId", event.entityId())
				.param("action", event.action())
				.param("before", toJson(event.before()))
				.param("after", toJson(event.after()))
				.update();
	}

	private String toJson(Object value) {
		return value == null ? null : json.writeValueAsString(value);
	}
}
