package com.ivoyant.hrms.config.api;

import com.ivoyant.hrms.common.authz.RequiresMember;
import com.ivoyant.hrms.config.dto.AccessCell;
import com.ivoyant.hrms.config.dto.AccessEntry;
import com.ivoyant.hrms.config.dto.AccessMatrixPatch;
import com.ivoyant.hrms.config.dto.AccessMatrixResponse;
import com.ivoyant.hrms.config.dto.AreaDetail;
import com.ivoyant.hrms.config.dto.AreaOwnersResponse;
import com.ivoyant.hrms.config.dto.AreaSummary;
import com.ivoyant.hrms.config.dto.CreateFieldRequest;
import com.ivoyant.hrms.config.dto.CreateSectionRequest;
import com.ivoyant.hrms.config.dto.FieldDetail;
import com.ivoyant.hrms.config.dto.FieldUsage;
import com.ivoyant.hrms.config.dto.MetaResponse;
import com.ivoyant.hrms.config.dto.PutOwnersRequest;
import com.ivoyant.hrms.config.dto.ReorderRequest;
import com.ivoyant.hrms.config.dto.SectionDetail;
import com.ivoyant.hrms.config.service.ConfigService;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Settings → Fields &amp; forms. Who may configure an area is decided per area (OpenFGA
 * {@code config_area#can_configure}) inside the service. PATCH bodies are JSON merge patches: a key that is absent
 * is left alone, a key set to {@code null} is cleared.
 */
@RestController
@RequestMapping("/api/v1/config")
@RequiresMember
public class ConfigController {

	private static final String[] PATCH_TYPES = {"application/json", "application/merge-patch+json"};

	private final ConfigService service;

	public ConfigController(ConfigService service) {
		this.service = service;
	}

	// ---- areas ----

	/** Only the areas the caller may configure. */
	@GetMapping("/areas")
	public List<AreaSummary> areas() {
		return service.areas();
	}

	@GetMapping("/areas/{key}")
	public AreaDetail area(@PathVariable String key) {
		return service.area(key);
	}

	@PostMapping("/areas/{key}/reorder")
	public AreaDetail reorder(@PathVariable String key, @RequestBody ReorderRequest request) {
		return service.reorder(key, request);
	}

	// ---- sections ----

	@PostMapping("/areas/{key}/sections")
	@ResponseStatus(HttpStatus.CREATED)
	public SectionDetail createSection(@PathVariable String key, @RequestBody CreateSectionRequest request) {
		return service.createSection(key, request);
	}

	@PatchMapping(value = "/sections/{id}", consumes = {"application/json", "application/merge-patch+json"})
	public SectionDetail updateSection(@PathVariable UUID id, @RequestBody Map<String, Object> patch,
			@RequestHeader(value = "If-Match", required = false) String ifMatch) {
		return service.updateSection(id, patch, ifMatch);
	}

	@DeleteMapping("/sections/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteSection(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean force) {
		service.deleteSection(id, force);
	}

	// ---- fields ----

	@PostMapping("/areas/{key}/fields")
	@ResponseStatus(HttpStatus.CREATED)
	public FieldDetail createField(@PathVariable String key, @Valid @RequestBody CreateFieldRequest request) {
		return service.createField(key, request);
	}

	@PostMapping("/fields/{id}/duplicate")
	@ResponseStatus(HttpStatus.CREATED)
	public FieldDetail duplicateField(@PathVariable UUID id) {
		return service.duplicateField(id);
	}

	@PatchMapping(value = "/fields/{id}", consumes = {"application/json", "application/merge-patch+json"})
	public FieldDetail updateField(@PathVariable UUID id, @RequestBody Map<String, Object> patch,
			@RequestParam(defaultValue = "false") boolean force,
			@RequestHeader(value = "If-Match", required = false) String ifMatch) {
		return service.updateField(id, patch, force, ifMatch);
	}

	@DeleteMapping("/fields/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteField(@PathVariable UUID id, @RequestParam(defaultValue = "false") boolean force) {
		service.deleteField(id, force);
	}

	@PostMapping("/fields/{id}/restore")
	public FieldDetail restoreField(@PathVariable UUID id) {
		return service.restoreField(id);
	}

	@PostMapping("/sections/{id}/restore")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void restoreSection(@PathVariable UUID id) {
		service.restoreSection(id);
	}

	@GetMapping("/fields/{id}/usage")
	public FieldUsage usage(@PathVariable UUID id) {
		return service.usage(id);
	}

	/** Replaces the field's role rules. Roles left out go back to their defaults. */
	@PutMapping("/fields/{id}/access")
	public List<AccessCell> replaceAccess(@PathVariable UUID id, @Valid @RequestBody List<@Valid AccessEntry> request) {
		return service.replaceAccess(id, request);
	}

	// ---- access matrix ----

	@GetMapping("/areas/{key}/access-matrix")
	public AccessMatrixResponse matrix(@PathVariable String key, @RequestParam(defaultValue = "OTHERS") String side) {
		return service.matrix(key, side);
	}

	@PatchMapping("/areas/{key}/access-matrix")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void patchMatrix(@PathVariable String key, @Valid @RequestBody AccessMatrixPatch patch) {
		service.patchMatrix(key, patch);
	}

	// ---- owners and meta ----

	@GetMapping("/area-owners")
	public AreaOwnersResponse owners() {
		return service.owners();
	}

	/** Super admin only. */
	@PutMapping("/area-owners/{areaKey}")
	public AreaOwnersResponse putOwners(@PathVariable String areaKey, @Valid @RequestBody PutOwnersRequest request) {
		return service.putOwners(areaKey, request.roleIds());
	}

	@GetMapping("/meta")
	public ResponseEntity<MetaResponse> meta() {
		return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
				.body(service.meta());
	}
}
