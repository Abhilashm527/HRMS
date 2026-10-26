package com.ivoyant.hrms.common.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Without {@code JWT_ISSUER} Spring Boot creates no {@link JwtDecoder}, and the resource-server setup refuses to
 * start. This fallback lets the app boot (e.g. with the {@code local} profile) and rejects every bearer token.
 */
@Configuration
@ConditionalOnExpression("'${spring.security.oauth2.resourceserver.jwt.issuer-uri:}'.isEmpty()"
		+ " and '${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}'.isEmpty()")
class NoIssuerJwtConfig {

	@Bean
	JwtDecoder rejectAllJwtDecoder() {
		return token -> {
			throw new BadJwtException("JWT_ISSUER is not configured; bearer tokens are not accepted");
		};
	}
}
