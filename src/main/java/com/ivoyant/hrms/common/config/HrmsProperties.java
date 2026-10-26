package com.ivoyant.hrms.common.config;

import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "hrms")
/** {@code bootstrapAdminEmail}: the first super admin; used once, while no super admin exists. */
public record HrmsProperties(UUID defaultOrgId, String bootstrapAdminEmail) {
}
