package com.ivoyant.hrms.employee.api;

import com.ivoyant.hrms.common.authz.RequiresMember;
import com.ivoyant.hrms.employee.service.FieldWriteService;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/employees/{id}")
public class FieldsController {

	private final FieldWriteService service;

	public FieldsController(FieldWriteService service) {
		this.service = service;
	}

	/** Body: {@code { "values": { key: value } }}. Edit fields are written, approval fields become change requests. */
	@PatchMapping("/fields")
	@RequiresMember
	@SuppressWarnings("unchecked")
	public Map<String, Object> patch(@PathVariable UUID id, @RequestBody Map<String, Object> body,
			@RequestHeader(value = "If-Match", required = false) String ifMatch) {
		Object values = body.get("values");
		return service.patch(id, values instanceof Map<?, ?> m ? (Map<String, Object>) m : body, ifMatch);
	}
}
