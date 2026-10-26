package com.ivoyant.hrms.org.service;

import java.util.Map;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Has the caller said yes to a risky change: {@code "confirm": true} in the body, or {@code ?force=true} / {@code ?confirm=true}. */
final class Confirmations {

	private Confirmations() {
	}

	static boolean requested(Map<String, Object> body) {
		if (body != null && "true".equalsIgnoreCase(String.valueOf(body.get("confirm")))) {
			return true;
		}
		if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a) {
			return "true".equalsIgnoreCase(a.getRequest().getParameter("force"))
					|| "true".equalsIgnoreCase(a.getRequest().getParameter("confirm"));
		}
		return false;
	}
}
