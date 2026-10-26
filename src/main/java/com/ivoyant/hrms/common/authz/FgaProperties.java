package com.ivoyant.hrms.common.authz;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "openfga")
public record FgaProperties(String apiUrl, String storeId, String authorizationModelId) {

	public boolean configured() {
		return storeId != null && !storeId.isBlank();
	}
}
