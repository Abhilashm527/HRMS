package com.ivoyant.hrms.common.security;

import com.ivoyant.hrms.common.config.HrmsProperties;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * The current employee id and org id.
 * <p>
 * With a JWT the employee id comes from the {@code employee_id} claim, falling back to the subject when that is a
 * UUID. Mapping an Entra {@code oid} to an employee through {@code app_user} is added with the iam module.
 */
@Component
public class CurrentUser {

	private final HrmsProperties properties;

	public CurrentUser(HrmsProperties properties) {
		this.properties = properties;
	}

	/** Employee id of the caller, or {@code null} when unauthenticated or not resolvable. */
	public UUID employeeId() {
		Object principal = principal();
		if (principal instanceof AppPrincipal app) {
			return app.employeeId();
		}
		if (principal instanceof Jwt jwt) {
			UUID claim = asUuid(jwt.getClaimAsString("employee_id"));
			return claim != null ? claim : asUuid(jwt.getSubject());
		}
		return null;
	}

	public UUID requireEmployeeId() {
		UUID id = employeeId();
		if (id == null) {
			throw new IllegalStateException("No authenticated employee");
		}
		return id;
	}

	public UUID orgId() {
		Object principal = principal();
		if (principal instanceof AppPrincipal app && app.orgId() != null) {
			return app.orgId();
		}
		if (principal instanceof Jwt jwt) {
			UUID claim = asUuid(jwt.getClaimAsString("org_id"));
			if (claim != null) {
				return claim;
			}
		}
		return properties.defaultOrgId();
	}

	private static Object principal() {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		return auth != null && auth.isAuthenticated() ? auth.getPrincipal() : null;
	}

	private static UUID asUuid(String value) {
		if (value == null) {
			return null;
		}
		try {
			return UUID.fromString(value);
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}
}
