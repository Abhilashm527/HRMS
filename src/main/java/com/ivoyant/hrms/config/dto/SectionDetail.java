package com.ivoyant.hrms.config.dto;

import java.util.List;
import java.util.UUID;

public record SectionDetail(UUID id, String key, String name, String description, int sort, boolean system,
		boolean locked, boolean active, boolean showInPortal, int fieldCount, Long version, List<FieldDetail> fields) {
}
