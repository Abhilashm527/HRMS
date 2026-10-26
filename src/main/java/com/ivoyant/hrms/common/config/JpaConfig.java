package com.ivoyant.hrms.common.config;

import com.ivoyant.hrms.common.security.CurrentUser;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
@EnableScheduling
@EnableConfigurationProperties(HrmsProperties.class)
public class JpaConfig {

	@Bean
	AuditorAware<UUID> auditorAware(CurrentUser currentUser) {
		return () -> Optional.ofNullable(currentUser.employeeId());
	}
}
