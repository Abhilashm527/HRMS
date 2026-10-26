package com.ivoyant.hrms.org.dto;

import java.util.UUID;

/** For pickers: active departments with their path, e.g. "Leadership › Engineering". */
public record DepartmentOption(UUID id, String name, String code, String path) {
}
