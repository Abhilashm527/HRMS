package com.ivoyant.hrms.employee.api;

import com.ivoyant.hrms.common.authz.RequiresMember;
import com.ivoyant.hrms.employee.service.ProfileAssembler;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** A profile as the caller may see it (see {@link ProfileAssembler}). */
@RestController
@RequestMapping("/api/v1/employees/{id}")
public class ProfileController {

	private final ProfileAssembler assembler;

	public ProfileController(ProfileAssembler assembler) {
		this.assembler = assembler;
	}

	@GetMapping("/profile")
	@RequiresMember
	public Map<String, Object> profile(@PathVariable UUID id, @RequestParam(required = false) String tab) {
		return assembler.profile(id, tab);
	}
}
