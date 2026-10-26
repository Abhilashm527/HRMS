package com.ivoyant.hrms.config.api;

import com.ivoyant.hrms.common.authz.RequiresMember;
import com.ivoyant.hrms.config.dto.ListSpecResponse;
import com.ivoyant.hrms.config.dto.ListsResponse;
import com.ivoyant.hrms.config.dto.SaveListsRequest;
import com.ivoyant.hrms.config.service.ConfigListService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Settings lookup lists. The screens edit a draft and save a whole module at once: {@code GET /{module}} returns
 * every list plus a version, {@code PUT /{module}} saves the draft (send the version as {@code If-Match}).
 */
@RestController
@RequestMapping("/api/v1/config/lists")
@RequiresMember
public class ConfigListController {

	private final ConfigListService service;

	public ConfigListController(ConfigListService service) {
		this.service = service;
	}

	@GetMapping("/{module}")
	public ListsResponse get(@PathVariable String module) {
		return service.get(module);
	}

	@PutMapping("/{module}")
	public ListsResponse save(@PathVariable String module, @Valid @RequestBody SaveListsRequest request,
			@RequestHeader(value = "If-Match", required = false) String ifMatch) {
		return service.save(module, ifMatch, request.lists());
	}

	@GetMapping("/{module}/spec")
	public ListSpecResponse spec(@PathVariable String module) {
		return service.spec(module);
	}

	/** One list, for dropdowns elsewhere in the app. */
	@GetMapping("/{module}/{listKey}")
	public List<Map<String, Object>> list(@PathVariable String module, @PathVariable String listKey) {
		return service.getList(module, listKey);
	}

	/** Example id for a format, e.g. {@code CMP-{TYPE}-####} with type LPT gives CMP-LPT-0001. */
	@GetMapping("/{module}/idformat/preview")
	public Map<String, String> preview(@PathVariable String module, @RequestParam String fmt,
			@RequestParam(required = false) String type) {
		return Map.of("example", ConfigListService.preview(fmt, type));
	}
}
