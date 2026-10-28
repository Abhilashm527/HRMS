package com.ivoyant.hrms.iam.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/** The token checker for {@code /api/**}: our own keys, our own issuer, and a session that has not been ended. */
@Configuration
class AuthConfig {

	@Bean
	JwtDecoder jwtDecoder(KeyService keys, TokenService tokens, AuthProps props) {
		JWKSource<SecurityContext> source = (selector, context) -> selector.select(keys.publicSet());
		DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
		processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, source));
		NimbusJwtDecoder decoder = new NimbusJwtDecoder(processor);
		OAuth2TokenValidator<Jwt> session = jwt -> {
			try {
				return tokens.sessionActive(UUID.fromString(jwt.getClaimAsString("sid"))) ? OAuth2TokenValidatorResult.success()
						: OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "The session was ended", null));
			}
			catch (RuntimeException ex) {
				return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "No session in the token", null));
			}
		};
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(props.issuer()), session));
		return decoder;
	}

	@Bean
	CorsConfigurationSource corsConfigurationSource(AuthProps props) {
		CorsConfiguration c = new CorsConfiguration();
		c.setAllowedOrigins(List.of(props.appBaseUrl()));
		c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		c.setAllowedHeaders(List.of("*"));
		c.setAllowCredentials(true);
		c.setMaxAge(3600L);
		UrlBasedCorsConfigurationSource s = new UrlBasedCorsConfigurationSource();
		s.registerCorsConfiguration("/**", c);
		return s;
	}
}
