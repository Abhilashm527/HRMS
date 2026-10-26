package com.ivoyant.hrms.org.service;

import com.ivoyant.hrms.common.authz.AuthzService;
import com.ivoyant.hrms.common.web.OrgArchivedException;
import com.ivoyant.hrms.org.OrgProfile;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** An archived organization is read-only: every write is refused (423) except a super admin's PATCH /api/v1/org. */
@Component
class ArchivedOrgInterceptor implements HandlerInterceptor {

	private final OrgProfile profile;
	private final AuthzService authz;

	ArchivedOrgInterceptor(OrgProfile profile, AuthzService authz) {
		this.profile = profile;
		this.authz = authz;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		String method = request.getMethod();
		boolean write = !(method.equals("GET") || method.equals("HEAD") || method.equals("OPTIONS"));
		if (write && request.getRequestURI().startsWith("/api/") && profile.isArchived()) {
			boolean reactivate = method.equals("PATCH") && request.getRequestURI().equals("/api/v1/org")
					&& authz.isSuperAdmin();
			if (!reactivate) {
				throw new OrgArchivedException();
			}
		}
		return true;
	}

	@Configuration
	static class Registration implements WebMvcConfigurer {

		private final ArchivedOrgInterceptor interceptor;

		Registration(ArchivedOrgInterceptor interceptor) {
			this.interceptor = interceptor;
		}

		@Override
		public void addInterceptors(InterceptorRegistry registry) {
			registry.addInterceptor(interceptor).addPathPatterns("/api/**");
		}
	}
}
