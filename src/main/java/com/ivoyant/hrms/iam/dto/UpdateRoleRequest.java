package com.ivoyant.hrms.iam.dto;

/** Partial update: null fields are left unchanged. */
public record UpdateRoleRequest(String name, String description) {
}
