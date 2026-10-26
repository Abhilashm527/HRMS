package com.ivoyant.hrms.config.dto;

import java.util.List;
import java.util.Map;

/** All lists of a settings module and its version (send it back as {@code If-Match} when saving). */
public record ListsResponse(String module, long version, Map<String, List<Map<String, Object>>> lists) {
}
