package com.ivoyant.hrms.common.audit;

import java.util.UUID;

/**
 * Published by services on every create/update/delete; persisted to {@code audit_log} by {@link AuditLogWriter}
 * in the publisher's transaction.
 */
public record AuditEvent(UUID orgId, UUID actorId, String module, String entity, String entityId, String action,
		Object before, Object after) {
}
