package com.ivoyant.hrms.common.web;

import java.util.List;

/** Input that is well-formed but invalid; becomes 422 with every problem listed under {@code errors}. */
public class UnprocessableException extends RuntimeException {

	private final transient List<ErrorItem> errors;

	public UnprocessableException(List<ErrorItem> errors) {
		super(errors.isEmpty() ? "Validation failed" : errors.get(0).message());
		this.errors = errors;
	}

	public List<ErrorItem> getErrors() {
		return errors;
	}
}
