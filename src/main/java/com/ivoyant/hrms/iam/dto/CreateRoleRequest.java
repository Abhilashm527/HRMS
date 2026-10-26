package com.ivoyant.hrms.iam.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreateRoleRequest(
		@NotBlank @Pattern(regexp = "[a-z][a-z0-9_]*", message = "must be lower_snake_case") String key,
		@NotBlank String name,
		String description) {
}
