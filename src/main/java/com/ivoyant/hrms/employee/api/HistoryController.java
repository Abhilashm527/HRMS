package com.ivoyant.hrms.employee.api;

import com.ivoyant.hrms.common.authz.RequiresMember;
import com.ivoyant.hrms.employee.service.HistoryService;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/employees/{id}/history")
public class HistoryController {

	private final HistoryService history;

	public HistoryController(HistoryService history) {
		this.history = history;
	}

	@GetMapping
	@RequiresMember
	public Map<String, Object> list(@PathVariable UUID id, @RequestParam(required = false) String field,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to, @RequestParam(defaultValue = "0") int page) {
		return history.list(id, field, from, to, page);
	}
}
