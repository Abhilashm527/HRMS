package com.ivoyant.hrms.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Local-dev only: authenticates the request as the employee in the {@code X-Dev-User} header.
 * <p>
 * Deliberately not a Spring bean (so it is never auto-registered as a servlet filter); {@link SecurityConfig}
 * adds it to the chain only when the {@code local} profile is active.
 */
final class DevUserFilter extends OncePerRequestFilter {

	static final String HEADER = "X-Dev-User";

	private final UUID orgId;

	DevUserFilter(UUID orgId) {
		this.orgId = orgId;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String header = request.getHeader(HEADER);
		if (header != null && SecurityContextHolder.getContext().getAuthentication() == null) {
			try {
				UUID employeeId = UUID.fromString(header.trim());
				SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
						new AppPrincipal(employeeId, orgId), null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
			}
			catch (IllegalArgumentException ex) {
				response.sendError(HttpServletResponse.SC_BAD_REQUEST, HEADER + " must be an employee UUID");
				return;
			}
		}
		chain.doFilter(request, response);
	}
}
