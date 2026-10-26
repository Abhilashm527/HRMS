package com.ivoyant.hrms.common.audit;

/**
 * Decides whether an audit entry is written. Implemented by the org module from the organization's audit settings, so
 * {@code common} needs no dependency on it.
 */
public interface AuditPolicy {

	boolean record(String module, String entity);
}
