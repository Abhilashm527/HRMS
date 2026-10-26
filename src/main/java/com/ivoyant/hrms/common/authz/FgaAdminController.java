package com.ivoyant.hrms.common.authz;

import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/fga")
public class FgaAdminController {

	private final FgaSyncService sync;

	public FgaAdminController(FgaSyncService sync) {
		this.sync = sync;
	}

	/** Re-derives every OpenFGA tuple from the database (idempotent, additive). */
	@PostMapping("/sync")
	@RequiresSuperAdmin
	public Map<String, Integer> sync() {
		return Map.of("queued", sync.syncAll());
	}
}
