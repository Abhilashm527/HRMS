package com.ivoyant.hrms.common.web;

import java.util.List;

/** A 409 that carries its own error code (LEVEL_INVERSION, MULTIPLE_TOPS, OUT_OF_BAND ...) instead of plain CONFLICT. */
public class CodedConflictException extends ConflictException {

	private final String code;

	public CodedConflictException(String code, String message) {
		super(message);
		this.code = code;
	}

	public CodedConflictException(String code, String message, List<String> details) {
		super(message, details);
		this.code = code;
	}

	public String getCode() {
		return code;
	}
}
