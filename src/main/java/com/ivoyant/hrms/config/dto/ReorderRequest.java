package com.ivoyant.hrms.config.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * New order of an area: section ids in order, and for each section id the ordered field ids it contains
 * (moving a field to another section is allowed). Either part may be omitted.
 */
public record ReorderRequest(List<UUID> sections, Map<UUID, List<UUID>> fields) {
}
