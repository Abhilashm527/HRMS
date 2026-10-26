package com.ivoyant.hrms.employee.api;

import com.ivoyant.hrms.common.authz.RequiresMember;
import com.ivoyant.hrms.employee.service.DirectoryService;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The employee directory: search and filters, grouping, the split-view preview. */
@RestController
@RequestMapping("/api/v1/employees")
public class DirectoryController {

	private final DirectoryService directory;

	public DirectoryController(DirectoryService directory) {
		this.directory = directory;
	}

	@GetMapping
	@RequiresMember
	public Map<String, Object> list(@RequestParam(required = false) String q, @RequestParam(required = false) UUID departmentId,
			@RequestParam(required = false) UUID locationId, @RequestParam(required = false) String status, @RequestParam(required = false) UUID managerId,
			@RequestParam(defaultValue = "false") boolean includeExited, @RequestParam(required = false) String group,
			@RequestParam(required = false) String sort, @RequestParam(defaultValue = "false") boolean desc, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "25") int size, @RequestParam(required = false) String fields) {
		return directory.list(new DirectoryService.Query(q, departmentId, locationId, status, managerId, includeExited, group, sort, desc, page, size,
				fields == null || fields.isBlank() ? null : new LinkedHashSet<>(Arrays.asList(fields.split(",")))));
	}

	@GetMapping("/filters")
	@RequiresMember
	public Map<String, Object> filters(@RequestParam(defaultValue = "false") boolean includeExited) {
		return directory.filters(includeExited);
	}

	@GetMapping("/{id}/preview")
	@RequiresMember
	public Map<String, Object> preview(@PathVariable UUID id) {
		return directory.preview(id);
	}
}
