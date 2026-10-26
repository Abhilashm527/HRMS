package com.ivoyant.hrms.common.authz;

import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.configuration.ClientConfiguration;
import dev.openfga.sdk.errors.FgaInvalidParameterException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration
@EnableConfigurationProperties(FgaProperties.class)
public class AuthzConfig {

	@Bean
	OpenFgaClient openFgaClient(FgaProperties props) throws FgaInvalidParameterException {
		ClientConfiguration config = new ClientConfiguration().apiUrl(props.apiUrl());
		if (props.configured()) {
			config.storeId(props.storeId());
		}
		if (StringUtils.hasText(props.authorizationModelId())) {
			config.authorizationModelId(props.authorizationModelId());
		}
		return new OpenFgaClient(config);
	}
}
