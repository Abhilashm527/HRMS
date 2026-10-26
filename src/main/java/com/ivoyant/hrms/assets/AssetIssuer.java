package com.ivoyant.hrms.assets;

import java.util.UUID;

/** Lets other modules ask the assets module to prepare equipment for a person. */
public interface AssetIssuer {

	/** Requests a laptop for a new hire (a no-op until the asset register can issue assets). */
	void requestLaptop(UUID hireId);
}
