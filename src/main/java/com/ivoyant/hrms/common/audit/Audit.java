package com.ivoyant.hrms.common.audit;

import com.ivoyant.hrms.common.security.CurrentUser;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** Convenience for services: publishes an {@link AuditEvent} stamped with the current org and actor. */
@Component
public class Audit {

	private final ApplicationEventPublisher publisher;
	private final CurrentUser currentUser;
	private final ObjectProvider<AuditPolicy> policy;

	public Audit(ApplicationEventPublisher publisher, CurrentUser currentUser, ObjectProvider<AuditPolicy> policy) {
		this.publisher = publisher;
		this.currentUser = currentUser;
		this.policy = policy;
	}

	public void created(String module, String entity, Object id, Object after) {
		publish(module, entity, id, "CREATE", null, after);
	}

	public void updated(String module, String entity, Object id, Object before, Object after) {
		publish(module, entity, id, "UPDATE", before, after);
	}

	public void deleted(String module, String entity, Object id, Object before) {
		publish(module, entity, id, "DELETE", before, null);
	}

	public void publish(String module, String entity, Object id, String action, Object before, Object after) {
		AuditPolicy p = policy.getIfAvailable();
		if (p != null && !p.record(module, entity)) {
			return;
		}
		publisher.publishEvent(new AuditEvent(currentUser.orgId(), currentUser.employeeId(), module, entity,
				String.valueOf(id), action, before, after));
	}
}
