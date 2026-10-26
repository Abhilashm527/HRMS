package com.ivoyant.hrms.config.dto;

/** {@code key} is optional: it defaults to a slug of the name. */
public record CreateSectionRequest(String name, String key, String description) {
}
