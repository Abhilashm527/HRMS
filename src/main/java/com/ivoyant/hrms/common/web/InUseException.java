package com.ivoyant.hrms.common.web;

import java.util.Map;

/**
 * Something cannot be deleted because other data still points at it; the client should deactivate it instead.
 * {@code usage} (optional) says how many of each kind of thing still use it.
 */
public class InUseException extends RuntimeException {

	private final transient Map<String, Object> usage;

	public InUseException(String message) {
		this(message, Map.of());
	}

	public InUseException(String message, Map<String, Object> usage) {
		super(message);
		this.usage = usage;
	}

	public Map<String, Object> getUsage() {
		return usage;
	}
}
