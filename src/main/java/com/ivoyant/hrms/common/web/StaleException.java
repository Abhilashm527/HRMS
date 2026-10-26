package com.ivoyant.hrms.common.web;

/** The caller edited an old version of a record ({@code If-Match} did not match). */
public class StaleException extends RuntimeException {

	public StaleException(String message) {
		super(message);
	}
}
