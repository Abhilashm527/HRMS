package com.ivoyant.hrms.assets.api;

import com.ivoyant.hrms.assets.service.AssetConfigService;
import com.ivoyant.hrms.common.authz.RequiresMember;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only asset configuration for the asset screens (any member): editing is Settings → Configuration → Asset configuration. */
@RestController
@RequestMapping("/api/v1/assets/config")
public class AssetConfigController {

	private final AssetConfigService service;

	public AssetConfigController(AssetConfigService service) {
		this.service = service;
	}

	/** Active statuses, categories, types, ownership (hardware), ID formats and alerts. */
	@GetMapping("/{kind}")
	@RequiresMember
	public Map<String, Object> config(@PathVariable String kind) {
		return service.config(kind);
	}

	/** The extra fields of a hardware type, or of a software category. */
	@GetMapping("/{kind}/types/{id}/fields")
	@RequiresMember
	public List<Map<String, Object>> fields(@PathVariable String kind, @PathVariable UUID id) {
		return service.fields(kind, id);
	}
}
