package com.ivoyant.hrms.common.web;

import java.util.List;

/** A risky change that needs the caller to repeat it with {@code ?force=true}; {@code breaks} says what it would affect. */
public class ConfirmRequiredException extends RuntimeException {

	private final transient List<String> breaks;

	public ConfirmRequiredException(String message, List<String> breaks) {
		super(message);
		this.breaks = breaks;
	}

	public List<String> getBreaks() {
		return breaks;
	}
}
