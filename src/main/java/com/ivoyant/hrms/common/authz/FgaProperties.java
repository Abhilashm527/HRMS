package com.ivoyant.hrms.common.authz;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "openfga")
/** {@code apiToken}: the pre-shared key when the OpenFGA server is reachable from the internet (empty for a private one). */
public record FgaProperties(String apiUrl, String storeId, String authorizationModelId, String apiToken) {

	public boolean configured() {
		return storeId != null && !storeId.isBlank();
	}
}
