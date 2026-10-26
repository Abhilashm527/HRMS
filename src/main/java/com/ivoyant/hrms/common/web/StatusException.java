package com.ivoyant.hrms.common.web;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** An error with its own HTTP status and code (401 INVITE_EXPIRED, 410 ALREADY_JOINED, 413, 415, 429 ...). */
public class StatusException extends RuntimeException {

	private final HttpStatus status;
	private final String code;
	private final transient Map<String, Object> extra;

	public StatusException(HttpStatus status, String code, String message) {
		this(status, code, message, Map.of());
	}

	public StatusException(HttpStatus status, String code, String message, Map<String, Object> extra) {
		super(message);
		this.status = status;
		this.code = code;
		this.extra = extra;
	}

	public HttpStatus getStatus() {
		return status;
	}

	public String getCode() {
		return code;
	}

	public Map<String, Object> getExtra() {
		return extra;
	}
}
