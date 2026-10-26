package com.ivoyant.hrms.common.web;

import java.util.List;

public class ConflictException extends RuntimeException {

	private final transient List<String> details;

	public ConflictException(String message) {
		this(message, List.of());
	}

	/** {@code details} are returned under the {@code details} property, e.g. what a change would break. */
	public ConflictException(String message, List<String> details) {
		super(message);
		this.details = details;
	}

	public List<String> getDetails() {
		return details;
	}
}
