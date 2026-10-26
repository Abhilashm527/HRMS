package com.ivoyant.hrms.assets.service;

import com.ivoyant.hrms.assets.AssetIssuer;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
class AssetIssuerStub implements AssetIssuer {

	private static final Logger log = LoggerFactory.getLogger(AssetIssuerStub.class);

	@Override
	public void requestLaptop(UUID hireId) {
		log.info("laptop requested for hire {}", hireId);
	}
}
